package com.dsh.lecturerec

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 16 kHz / 单声道 / PCM-16 采集。用 AudioRecord 而不是 MediaRecorder，
 * 因为我们要边录边拿到原始 PCM 去做 VAD 切句，而不是录完再处理。
 */
class AudioCapture(val sampleRate: Int = 16000) {

    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IllegalStateException("设备不支持 16kHz 单声道录音")

        val bufSize = maxOf(minBuf * 2, sampleRate * 2)   // 至少 0.5 秒
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("AudioRecord 初始化失败，可能麦克风被其它应用占用")
        }
        r.startRecording()
        record = r
    }

    /** 阻塞读满一帧。正常返回 out.size，出错返回 -1。 */
    fun readFrame(out: ShortArray): Int {
        val r = record ?: return -1
        var total = 0
        while (total < out.size) {
            val n = r.read(out, total, out.size - total)
            if (n < 0) return -1
            if (n == 0) continue
            total += n
        }
        return total
    }

    fun stop() {
        val r = record ?: return
        record = null
        runCatching {
            if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
        }
        runCatching { r.release() }
    }
}
