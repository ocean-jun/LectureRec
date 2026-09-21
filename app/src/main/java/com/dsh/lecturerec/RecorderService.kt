package com.dsh.lecturerec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 常驻前台服务：息屏、切后台、锁屏都继续录音转写。
 *
 * 链路：AudioRecord → VAD 切句 → 顺序上传转写 → 逐句落盘。
 * 上传用单消费者队列，保证文本顺序与录音顺序一致。
 *
 * 排查真机问题时：adb logcat -s LectureRec:D
 */
class RecorderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runner: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var settings: AppSettings
    private lateinit var store: TranscriptStore

    @Volatile private var running = false
    @Volatile private var finishing = false
    @Volatile private var sessionFile: File? = null
    @Volatile private var sessionStamp = 0L
    @Volatile private var lastNotifyAt = 0L

    /** 已组成批次但还没出结果的音频，收尾超时时用来兜底留底。 */
    private val pending = ConcurrentHashMap<Long, ByteArray>()

    /** 已经按顺序写进文件的最后一批编号。并发下完成顺序是乱的，靠它保证写文件顺序正确。 */
    @Volatile private var writeCursor = 0L

    private val seq = AtomicLong(0)
    private val batchSeq = AtomicLong(0)
    private val queued = AtomicInteger(0)
    private val failed = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        store = TranscriptStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> requestStop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        running = false
        runner?.cancel()
        releaseWakeLock()
        scope.cancel()
        TranscriptRepo.setStatus { it.copy(recording = false) }
        super.onDestroy()
    }

    // ---------------- 录制 ----------------

    private fun startRecording() {
        if (running) {
            Log.w(TAG, "startRecording ignored: already running")
            return
        }
        running = true

        // 清掉上一次会话（可能是启动时从文件恢复出来的）：
        //  1. 新会话写的是新文件，新旧混在一起会让导出串味；
        //  2. 更要紧的是**批次编号会撞车** —— 恢复出来的片段占了 1..N，新批次又从 1 开始，
        //     按序写文件的逻辑会把旧片段误判成"该补写的历史内容"，把上一堂课写进新文件。
        //     这是实测踩过的坑（日志里出现过"按序写入 63 段"，而那一轮其实只录了 3 批）。
        TranscriptRepo.clear()
        seq.set(0)
        batchSeq.set(0)
        writeCursor = 0L
        finishing = false
        pending.clear()

        Log.i(
            TAG,
            "startRecording model=${settings.asrModel} base=${settings.asrBaseUrl} " +
                "keySet=${settings.asrApiKey.isNotBlank()} hotwords=${settings.hotwordPrompt().length}字"
        )

        TranscriptRepo.setStatus {
            it.copy(
                recording = true,
                startedAt = System.currentTimeMillis(),
                failed = RetryStore.count(this)
            )
        }
        promoteToForeground(getString(R.string.state_recording), "正在初始化麦克风")
        acquireWakeLock()
        sessionStamp = System.currentTimeMillis()
        writeCursor = 0L
        sessionFile = store.newSession(settings.courseTitle)
        Log.i(TAG, "session file = ${sessionFile?.absolutePath}")

        runner = scope.launch {
            val queue = Channel<Utterance>(Channel.UNLIMITED)
            val uploader = launch { uploadLoop(queue) }
            try {
                captureLoop(queue)
            } catch (t: Throwable) {
                Log.e(TAG, "capture loop crashed", t)
                TranscriptRepo.setStatus { it.copy(lastError = t.message ?: t.toString()) }
            } finally {
                queue.close()
                // 收尾必须有上界：万一某个请求卡住（读超时 180 秒 × 3 次重试），
                // 用户会一直看到「正在收尾…」而停不下来。
                // 超时就把还没传完的音频转成留底，一个字节都不丢。
                val drained = withTimeoutOrNull(DRAIN_TIMEOUT_MS) { uploader.join() }
                if (drained == null) {
                    Log.w(TAG, "收尾超时（${DRAIN_TIMEOUT_MS / 1000} 秒），剩余音频转为留底")
                    uploader.cancel()
                    persistPending(queue)
                }
            }
            teardown()
        }
    }

    private fun requestStop() {
        // 收尾中再按一次停止，绝不能直接 teardown —— 那会打断上传、丢掉还没传完的音频
        if (finishing) {
            Log.i(TAG, "requestStop ignored: 正在收尾")
            return
        }
        if (!running) {
            teardown()
            return
        }
        Log.i(TAG, "requestStop")
        running = false
        finishing = true
        // captureLoop 会在下一帧退出；finally 里关闭队列，并等 uploader 把剩下的片段传完
        TranscriptRepo.setStatus { it.copy(recording = false, finishing = true) }
        notify("正在收尾…", "等待剩余片段上传完成")
    }

    private suspend fun captureLoop(queue: Channel<Utterance>) = withContext(Dispatchers.IO) {
        var capture = AudioCapture()
        var detector: SpeechDetector? = null
        var vadName = "?"
        var speechRatio = 0.0
        val frame = ShortArray(FRAME_SAMPLES)
        var frameIndex = 0L
        var reconnectAttempts = 0
        val t0 = System.currentTimeMillis()
        try {
            // 先把麦克风开起来。Silero 模型加载要 200ms 以上，
            // 放在这之前的话，用户一按下按钮就说话，第一个字会被直接丢掉。
            // 现在这段时间的音频先进 AudioRecord 的内部缓冲（≥1 秒），随后照样读得到。
            capture.start()
            Log.i(TAG, "AudioRecord started (+${System.currentTimeMillis() - t0}ms)")

            val det = DetectorFactory.create(this@RecorderService, settings.vadEngine)
            detector = det
            val vad = VadSegmenter(det)
            vadName = vad.detectorName
            Log.i(TAG, "VAD 就绪: $vadName (+${System.currentTimeMillis() - t0}ms)")

            while (isActive && running) {
                val n = capture.readFrame(frame)
                if (n < 0) {
                    // 来电、其它应用抢占麦克风、系统回收都可能让读取中断。
                    // 课堂上绝不能因此静默停录，所以先尝试重开麦克风。
                    if (reconnectAttempts >= MAX_RECONNECT) {
                        Log.e(TAG, "readFrame kept failing, giving up")
                        TranscriptRepo.setStatus {
                            it.copy(lastError = "麦克风连续读取失败，录音已停止（已录内容已保存）")
                        }
                        break
                    }
                    reconnectAttempts++
                    Log.w(TAG, "readFrame failed, reopen mic ($reconnectAttempts/$MAX_RECONNECT)")
                    runCatching { capture.stop() }
                    delay(300L * reconnectAttempts)
                    capture = AudioCapture()
                    runCatching { capture.start() }
                    continue
                }
                reconnectAttempts = 0
                frameIndex++
                vad.accept(frame)?.let { pcm -> enqueue(pcm, frameIndex, queue) }
            }
            vad.finish()?.let { pcm -> enqueue(pcm, frameIndex, queue) }
            speechRatio = vad.speechRatio
        } finally {
            capture.stop()
            runCatching { detector?.close() }
            Log.i(
                TAG,
                "capture ended: ${frameIndex * FRAME_MS / 1000}s 音频, VAD=$vadName, " +
                    "有效语音占比 ${(speechRatio * 100).toInt()}%"
            )
        }
    }

    private suspend fun enqueue(pcm: ByteArray, frameIndex: Long, queue: Channel<Utterance>) {
        val endMs = frameIndex * FRAME_MS
        val durMs = Pcm.durationMs(pcm.size, SAMPLE_RATE)
        val startMs = maxOf(0L, endMs - durMs)
        val id = seq.incrementAndGet()

        // 这里只进队列，不建界面条目 —— 真正上传的是「批次」，一条对应一次请求
        Log.d(TAG, "utterance #$id start=${startMs}ms dur=${durMs}ms bytes=${pcm.size}")
        queued.incrementAndGet()
        publishStatus(force = true)
        queue.send(Utterance(id, pcm, startMs, endMs, SAMPLE_RATE))
    }

    private suspend fun uploadLoop(queue: Channel<Utterance>) {
        val asr = AsrEngineFactory.create(settings)
        val concurrency = settings.uploadConcurrency.coerceIn(1, 8)
        Log.i(TAG, "ASR 协议: ${AsrEngineFactory.describe(settings.asrBaseUrl)} model=${settings.asrModel}")
        Log.i(
            TAG,
            "上传策略: 并发 $concurrency 路, 每批目标 ${BATCH_TARGET_MS / 1000}s / " +
                "停顿 ${BATCH_IDLE_GAP_MS}ms 即发 / 最多 $BATCH_MAX_UTTERANCES 句"
        )

        val former = BatchFormer(
            targetMs = BATCH_TARGET_MS,
            maxUtterances = BATCH_MAX_UTTERANCES,
            idleGapMs = BATCH_IDLE_GAP_MS,
            maxWaitMs = BATCH_MAX_WAIT_MS
        )
        val gate = Semaphore(concurrency)

        coroutineScope {
            while (isActive) {
                val batch = former.take(queue)
                if (batch.isEmpty()) break

                queued.addAndGet(-batch.size)
                val bid = batchSeq.incrementAndGet()
                val pcm = Pcm.concat(batch.map { it.pcm })
                val audioMs = Pcm.durationMs(pcm.size, SAMPLE_RATE)

                TranscriptRepo.add(
                    Segment(
                        id = bid,
                        startMs = batch.first().startMs,
                        endMs = batch.last().endMs
                    )
                )
                Log.i(
                    TAG,
                    "批次 #$bid 合并 ${batch.size} 句 / ${audioMs}ms 音频 / ${pcm.size / 1024}KB"
                )
                publishStatus(force = true)

                val wav = Wav.wrap(pcm, SAMPLE_RATE)
                pending[bid] = wav

                // 先占坑再放行，保证在途请求数不超过设定值
                gate.acquire()
                launch {
                    try {
                        processBatch(asr, bid, wav, batch.size)
                    } finally {
                        gate.release()
                    }
                }
            }
        }
    }

    private suspend fun processBatch(asr: AsrEngine, bid: Long, wav: ByteArray, mergedCount: Int) {
        TranscriptRepo.update(bid) { it.copy(state = SegState.UPLOADING) }
        publishStatus(force = false)

        var text: String? = null
        var lastErr: String? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                text = asr.transcribe(wav)
                break
            } catch (t: Throwable) {
                lastErr = t.message ?: t.toString()
                Log.w(TAG, "批次 #$bid 第 $attempt 次失败: $lastErr")
                if (!RetryPolicy.isRetryable(t)) {
                    Log.w(TAG, "批次 #$bid 属于不可重试的错误，跳过退避等待")
                    break
                }
                if (attempt < MAX_ATTEMPTS) delay(1200L * attempt)
            }
        }

        val ok = text
        if (ok != null) {
            Log.i(TAG, "批次 #$bid ok (${ok.length}字, 含 $mergedCount 句): ${ok.take(30)}")
            TranscriptRepo.update(bid) { it.copy(text = ok, state = SegState.DONE) }
        } else {
            failed.incrementAndGet()
            Log.e(TAG, "批次 #$bid FAILED: $lastErr")
            // 整批 WAV 留底，可手动重传；文件名带会话时间戳，避免跨课堂覆盖
            RetryStore.save(this, RetryStore.fileName(sessionStamp, bid), wav)
            TranscriptRepo.update(bid) { it.copy(state = SegState.FAILED) }
            TranscriptRepo.setStatus { it.copy(lastError = lastErr) }
        }

        pending.remove(bid)

        // 完成顺序可能是乱的，必须按录音顺序写文件，否则导出的笔记时间戳会来回跳
        flushInOrder()
        publishStatus(force = true)
    }

    /** 收尾超时的兜底：把没传完的音频全部留底，绝不静默丢掉课堂内容。 */
    private fun persistPending(queue: Channel<Utterance>) {
        var saved = 0

        for ((bid, wav) in pending) {
            RetryStore.save(this, RetryStore.fileName(sessionStamp, bid), wav)
            TranscriptRepo.update(bid) { it.copy(state = SegState.FAILED) }
            failed.incrementAndGet()
            saved++
        }
        pending.clear()

        // 还在队列里、连批次都没组上的句子
        while (true) {
            val u = queue.tryReceive().getOrNull() ?: break
            RetryStore.save(
                this,
                RetryStore.fileName(sessionStamp, RETRY_UTTERANCE_OFFSET + u.id),
                Wav.wrap(u.pcm, SAMPLE_RATE)
            )
            saved++
        }

        if (saved > 0) {
            Log.w(TAG, "收尾超时：$saved 段音频已留底")
            TranscriptRepo.setStatus {
                it.copy(lastError = "收尾超时，$saved 段音频已留底，可在菜单里重传")
            }
        }
    }

    private fun flushInOrder() {
        val file = sessionFile ?: return
        val batch = TranscriptWriter.pendingWrites(TranscriptRepo.snapshot(), writeCursor)
        if (batch.segments.isNotEmpty()) {
            Log.d(TAG, "按序写入 ${batch.segments.size} 段 (游标 $writeCursor -> ${batch.cursor})")
            for (seg in batch.segments) store.appendSegment(file, seg, settings.timestampMode)
        }
        writeCursor = batch.cursor
    }

    private fun publishStatus(force: Boolean) {
        val q = maxOf(0, queued.get())
        TranscriptRepo.setStatus { it.copy(queued = q, failed = failed.get()) }

        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyAt < NOTIFY_MIN_INTERVAL_MS) return
        lastNotifyAt = now

        val st = TranscriptRepo.status.value
        val elapsed = if (st.startedAt > 0) now - st.startedAt else 0L
        val sub = buildString {
            append("已转写 ").append(TranscriptRepo.doneCount()).append(" 句")
            if (q > 0) append(" · 待上传 ").append(q)
            if (st.failed > 0) append(" · 失败 ").append(st.failed)
        }
        notify("正在听课 · ${TranscriptStore.fmt(elapsed)}", sub)
    }

    private fun teardown() {
        running = false
        finishing = false
        releaseWakeLock()
        val err = TranscriptRepo.status.value.lastError
        Log.i(TAG, "teardown error=$err done=${TranscriptRepo.doneCount()} failed=${failed.get()}")
        TranscriptRepo.setStatus {
            it.copy(recording = false, finishing = false, queued = maxOf(0, queued.get()))
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        // 录音因故中断必须让用户看得见，否则会以为一直在录
        if (err != null) runCatching { notifyStopped(err) }
        stopSelf()
    }

    private fun notifyStopped(reason: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("录音已停止")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 2,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
        nm.notify(STOPPED_NOTIF_ID, n)
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        ch.description = getString(R.string.channel_desc)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.btn_stop), stop)
            .build()
    }

    private fun promoteToForeground(title: String, text: String) {
        val n = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notify(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification(title, text)) }
    }

    // ---------------- 电源 ----------------

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LectureRec:record").apply {
            setReferenceCounted(false)
            runCatching { acquire(MAX_WAKE_MS) }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.dsh.lecturerec.action.START"
        const val ACTION_STOP = "com.dsh.lecturerec.action.STOP"

        /** adb logcat -s LectureRec:D */
        private const val TAG = "LectureRec"

        private const val CHANNEL_ID = "lecture_rec"
        private const val NOTIF_ID = 1001
        private const val STOPPED_NOTIF_ID = 1002
        private const val SAMPLE_RATE = 16000
        private const val FRAME_MS = 20L
        private const val FRAME_SAMPLES = 320
        private const val MAX_ATTEMPTS = 3
        private const val MAX_RECONNECT = 5

        /** 收尾最多等这么久，之后就把没传完的音频转留底 —— 保证「停止」一定能停下来。 */
        private const val DRAIN_TIMEOUT_MS = 20_000L

        /** 队列里没成批的句子留底时用的编号偏移，避免和批次编号撞车。 */
        private const val RETRY_UTTERANCE_OFFSET = 1_000_000L
        /**
         * 攒批参数。
         *
         * 这几个值原本是按「每请求固定 30 秒」调的 —— 那时攒得越大越划算。
         * 换成 TeleSpeechASR 后单次请求只要 0.8 秒，攒批的收益没了、只剩下纯延迟，
         * 所以整体压小：首次出字从约 4.6 秒降到约 2.2 秒。
         *
         * 断句用的 600ms 静音阈值（VadSegmenter.endSilenceMs）没有动 ——
         * 降它会从句子中间切断，反而损失 ASR 上下文。
         */
        private const val BATCH_TARGET_MS = 8_000L
        private const val BATCH_MAX_UTTERANCES = 12
        private const val BATCH_IDLE_GAP_MS = 800L
        private const val BATCH_MAX_WAIT_MS = 10_000L
        private const val MAX_WAKE_MS = 8 * 60 * 60 * 1000L
        private const val NOTIFY_MIN_INTERVAL_MS = 2500L

        fun start(ctx: Context) {
            val i = Intent(ctx, RecorderService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP)
            runCatching { ctx.startService(i) }
        }
    }
}
