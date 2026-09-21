package com.dsh.lecturerec

/**
 * 把「逐帧的人声概率」切成一句一句的话。
 *
 * 与检测算法解耦：具体概率由 [SpeechDetector] 给出（Silero 或能量法），
 * 这里只负责迟滞、预卷、断句、最短/最长时长这些状态机逻辑。
 *
 * 目的有两个：
 *  1. 把长录音切成「一句一段」单独上传，边界自然，也绕开了转写接口对单文件时长的限制；
 *  2. 掐掉静音段不发送 —— 课堂上老师不可能全程说话，这一条通常能省下三成左右的开销。
 */
class VadSegmenter(
    private val detector: SpeechDetector,
    private val sampleRate: Int = 16000,
    private val frameMs: Int = 20,
    private val startSpeechMs: Int = 80,
    private val endSilenceMs: Int = 600,
    private val minSpeechMs: Int = 320,
    private val maxSpeechMs: Int = 25000,
    private val prerollMs: Int = 240
) {
    private val startFrames = maxOf(1, startSpeechMs / frameMs)
    private val endSilenceFrames = maxOf(1, endSilenceMs / frameMs)
    private val minSpeechFrames = maxOf(1, minSpeechMs / frameMs)
    private val maxSpeechFrames = maxOf(1, maxSpeechMs / frameMs)
    private val prerollFrames = maxOf(0, prerollMs / frameMs)

    private val preroll = ArrayDeque<ShortArray>()
    private val utterance = ArrayList<ShortArray>()

    private var inSpeech = false
    private var voicedRun = 0
    private var silenceRun = 0

    private var totalFrames = 0L
    private var speechFrames = 0L

    /** 有效语音占比，用来向用户展示「掐掉了多少静音」。 */
    val speechRatio: Double
        get() = if (totalFrames == 0L) 0.0 else speechFrames.toDouble() / totalFrames

    /** 底层检测引擎名，写日志用。 */
    val detectorName: String get() = detector.name

    /**
     * 送入一帧。若这一帧正好闭合了一个完整语句，返回该语句的裸 PCM 字节，否则返回 null。
     */
    fun accept(frame: ShortArray): ByteArray? {
        totalFrames++
        // 检测器无法判定时按静音处理：宁可不发，也不要往 ASR 灌垃圾
        val p = detector.probability(frame) ?: 0.0

        if (!inSpeech) {
            if (p >= detector.startThreshold) {
                voicedRun++
                if (voicedRun >= startFrames) {
                    inSpeech = true
                    silenceRun = 0
                    // 把 preroll 补回去，否则会吃掉第一个字
                    for (f in preroll) utterance.add(f)
                    preroll.clear()
                    utterance.add(frame.copyOf())
                }
            } else {
                voicedRun = 0
                if (prerollFrames > 0) {
                    preroll.addLast(frame.copyOf())
                    while (preroll.size > prerollFrames) preroll.removeFirst()
                }
            }
            return null
        }

        utterance.add(frame.copyOf())
        if (p >= detector.keepThreshold) silenceRun = 0 else silenceRun++

        val tooLong = utterance.size >= maxSpeechFrames
        val closed = silenceRun >= endSilenceFrames
        return if (tooLong || closed) flush(force = tooLong) else null
    }

    /** 停止录音时调用，吐出最后一段未闭合的语音。 */
    fun finish(): ByteArray? = if (inSpeech) flush(force = true) else null

    private fun flush(force: Boolean): ByteArray? {
        val frames = utterance.toList()
        utterance.clear()
        preroll.clear()
        inSpeech = false
        voicedRun = 0
        silenceRun = 0

        if (frames.isEmpty()) return null

        // 正常闭合时把尾巴上的静音切掉；强制切分（超长）则全保留
        val end = if (force) frames.size else maxOf(1, frames.size - endSilenceFrames)
        if (end < minSpeechFrames) return null          // 太短，多半是咳嗽/敲桌子

        speechFrames += end.toLong()

        val frameSamples = frames[0].size
        val out = ByteArray(end * frameSamples * 2)
        var o = 0
        for (i in 0 until end) {
            for (s in frames[i]) {
                val v = s.toInt()
                out[o++] = (v and 0xFF).toByte()
                out[o++] = ((v shr 8) and 0xFF).toByte()
            }
        }
        return out
    }
}
