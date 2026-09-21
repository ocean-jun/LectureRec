package com.dsh.lecturerec

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.lecturerec.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: SegmentAdapter
    private lateinit var settings: AppSettings
    private lateinit var store: TranscriptStore

    private var failedOnDisk = 0

    /** 上一次按「停止」的时刻，用于识别"以为没停下又按一次"的误触。 */
    private var lastStopAtMs = 0L

    private val retrySeq = AtomicLong(-1)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                RecorderService.start(this)
            } else {
                toast(getString(R.string.perm_need_mic))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        settings = AppSettings(this)
        store = TranscriptStore(this)

        // 上次的记录（App 被系统杀掉或重新打开）自动回到界面
        if (TranscriptRepo.snapshot().isEmpty() && !TranscriptRepo.status.value.recording) {
            val restored = runCatching { store.restoreLatest() }.getOrDefault(0)
            if (restored > 0) toast(getString(R.string.restored, restored))
        }

        adapter = SegmentAdapter(settings.timestampMode) { seg ->
            val t = seg.polished ?: seg.text
            if (t.isNotBlank()) copyText(t)
        }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.btnRecord.setOnClickListener {
            when (RecorderUi.actionFor(TranscriptRepo.status.value)) {
                // 收尾期必须忽略点击：否则这里会启动一次全新录音
                RecorderUi.Action.IGNORE -> Unit
                RecorderUi.Action.STOP -> {
                    lastStopAtMs = SystemClock.elapsedRealtime()
                    RecorderService.stop(this)
                }
                RecorderUi.Action.START -> {
                    // 刚停下又要点开始 —— 多半是"以为没停下、又按了一次"，忽略
                    if (RecorderUi.isWithinRestartCooldown(
                            SystemClock.elapsedRealtime(), lastStopAtMs
                        )
                    ) {
                        toast(getString(R.string.just_stopped))
                    } else {
                        ensureMicAndStart()
                    }
                }
            }
        }
        b.btnCopyAll.setOnClickListener { copyAll() }
        b.btnPolish.setOnClickListener { runPolish() }

        observe()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页回来时，时间戳显示方式可能已经改了
        adapter.setTimestampMode(settings.timestampMode)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TranscriptRepo.segments.collect { list ->
                        val before = adapter.itemCount
                        adapter.submit(list)
                        b.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        if (list.size > before) b.list.scrollToPosition(list.size - 1)
                    }
                }
                launch {
                    TranscriptRepo.status.collect { st ->
                        failedOnDisk = RetryStore.count(this@MainActivity)
                        refreshText(st)
                    }
                }
                launch {
                    while (true) {
                        delay(1000)
                        // 只在录音时刷新，且文本没变就不写回。
                        // 否则每秒都会触发一次布局：既费电，也会让界面永远进不了 idle 状态
                        // （真机验证时 uiautomator dump 因此一直报 "could not get idle state"）。
                        val st = TranscriptRepo.status.value
                        if (st.recording) refreshText(st)
                    }
                }
            }
        }
    }

    private fun refreshText(st: RecStatus) {
        val btn = when {
            st.finishing -> getString(R.string.btn_finishing)
            st.recording -> getString(R.string.btn_stop)
            else -> getString(R.string.btn_start)
        }
        if (b.btnRecord.text?.toString() != btn) b.btnRecord.text = btn
        // 收尾期禁用按钮，避免被误当成「可以开始下一次」
        if (b.btnRecord.isEnabled == st.finishing) b.btnRecord.isEnabled = !st.finishing

        val label = getString(if (st.recording) R.string.state_recording else R.string.state_idle)
        if (b.stateText.text?.toString() != label) b.stateText.text = label

        val elapsed = if (st.startedAt > 0) System.currentTimeMillis() - st.startedAt else 0L
        val sb = StringBuilder()
        if (st.recording || elapsed > 0) sb.append("时长 ").append(TranscriptStore.fmt(elapsed)).append("   ")
        sb.append("已转写 ").append(TranscriptRepo.doneCount()).append(" 句")
        if (st.queued > 0) sb.append(" · 待上传 ").append(st.queued)

        // 本次失败和历史留底分开说：
        // 以前只显示一个「失败 N」，把两者的数量混在一起，读起来像"App 坏了"，
        // 而它实际的意思是"有 N 段音频留在本地、还没转成功"，而且接了 Key 之后是能补回来的。
        if (st.failed > 0) sb.append(" · 本次失败 ").append(st.failed)
        if (failedOnDisk > 0) {
            sb.append("\n").append(getString(R.string.retry_pending_hint, failedOnDisk))
        }
        st.lastError?.let { sb.append("\n").append(it.take(160)) }

        val detail = sb.toString()
        if (b.detailText.text?.toString() != detail) b.detailText.text = detail
    }

    private fun confirmClearFailed() {
        val n = RetryStore.count(this)
        if (n == 0) {
            toast(getString(R.string.retry_none))
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_clear_failed, n))
            .setPositiveButton(R.string.clear) { _, _ ->
                RetryStore.list(this).forEach { RetryStore.delete(it) }
                failedOnDisk = 0
                refreshText(TranscriptRepo.status.value)
                toast(getString(R.string.cleared_failed))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 录音 ----------------

    private fun ensureMicAndStart() {
        val need = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)

        if (need.isEmpty()) RecorderService.start(this) else permLauncher.launch(need.toTypedArray())
    }

    // ---------------- 复制 ----------------

    private fun copyAll() {
        val t = TranscriptRepo.plainText()
        if (t.isBlank()) {
            toast(getString(R.string.nothing_to_copy))
            return
        }
        copyText(t)
    }

    private fun copyText(t: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("transcript", t))
        toast(getString(R.string.copied, t.length))
    }

    // ---------------- AI 校对 ----------------

    private fun runPolish() {
        val text = TranscriptRepo.plainText()
        if (text.isBlank()) {
            toast(getString(R.string.nothing_to_copy))
            return
        }
        if (settings.polishApiKey.isBlank()) {
            toast(getString(R.string.settings_missing_key))
            return
        }
        toast(getString(R.string.polishing))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PolishClient(settings.polishBaseUrl, settings.polishApiKey, settings.polishModel)
                        .polish(text, settings.hotwordPrompt(), settings.courseTitle)
                }
            }
            result
                .onSuccess { showPolished(it) }
                .onFailure { toast(getString(R.string.polish_failed, it.message ?: "")) }
        }
    }

    private fun showPolished(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            val p = (24 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
        }
        val scroll = ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle(R.string.polish_result_title)
            .setView(scroll)
            .setPositiveButton(R.string.btn_copy_all) { _, _ -> copyText(text) }
            .setNeutralButton(R.string.save) { _, _ -> savePolished(text) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun savePolished(text: String) {
        val f = store.savePolished(store.latest(), text)
        toast("已保存：${f.name}")
    }

    // ---------------- 失败重传 ----------------

    private fun retryFailed() {
        val files = RetryStore.list(this)
        if (files.isEmpty()) {
            toast(getString(R.string.retry_none))
            return
        }
        toast(getString(R.string.retry_started, files.size))
        lifecycleScope.launch {
            val asr = AsrEngineFactory.create(settings)
            val sessionFile = store.latest()
            var ok = 0
            withContext(Dispatchers.IO) {
                for (f in files) {
                    runCatching {
                        val text = asr.transcribe(f.readBytes())
                        if (text.isNotBlank()) {
                            val seg = Segment(
                                id = retrySeq.getAndDecrement(),
                                startMs = 0L,
                                endMs = 0L,
                                text = text,
                                state = SegState.DONE
                            )
                            TranscriptRepo.add(seg)
                            if (sessionFile != null) {
                                store.appendSegment(sessionFile, seg, settings.timestampMode)
                            }
                        }
                        RetryStore.delete(f)
                        ok++
                    }
                }
            }
            failedOnDisk = RetryStore.count(this@MainActivity)
            toast(getString(R.string.retry_done, ok, files.size))
            refreshText(TranscriptRepo.status.value)
        }
    }

    // ---------------- 导出 ----------------

    private fun export() {
        val f: File? = store.latest()
        if (f == null || !f.exists()) {
            toast(getString(R.string.no_file_yet))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, getString(R.string.menu_export)))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_clear)
            .setPositiveButton(R.string.clear) { _, _ -> TranscriptRepo.clear() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 菜单 ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            R.id.action_retry -> {
                retryFailed()
                return true
            }
            R.id.action_export -> {
                export()
                return true
            }
            R.id.action_clear -> {
                confirmClear()
                return true
            }
            R.id.action_clear_failed -> {
                confirmClearFailed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
