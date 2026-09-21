package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切句状态机的测试。
 *
 * 用「脚本化概率」的假检测器驱动，而不是合成音频 —— 这样每个断句行为都是确定的，
 * 也把测试与 Silero / 能量法各自的调参彻底解耦。
 */
class VadSegmenterTest {

    private val frameSamples = 320          // 20ms @ 16k

    /** 按给定概率序列逐帧回答；序列用完返回 0。 */
    private class Scripted(
        private val script: List<Double>,
        override val startThreshold: Double = 0.5,
        override val keepThreshold: Double = 0.3
    ) : SpeechDetector {
        override val name = "scripted"
        private var i = 0
        override fun probability(frame: ShortArray): Double =
            if (i < script.size) script[i++] else 0.0
    }

    private fun seq(vararg parts: Pair<Double, Int>): List<Double> =
        parts.flatMap { (p, n) -> List(n) { p } }

    private fun framesOf(bytes: ByteArray) = bytes.size / 2 / frameSamples

    private fun run(
        script: List<Double>,
        minSpeechMs: Int = 320,
        maxSpeechMs: Int = 25000
    ): Pair<List<ByteArray>, VadSegmenter> {
        val v = VadSegmenter(
            Scripted(script),
            minSpeechMs = minSpeechMs,
            maxSpeechMs = maxSpeechMs
        )
        val out = ArrayList<ByteArray>()
        val frame = ShortArray(frameSamples)
        for (p in script) v.accept(frame)?.let { out.add(it) }
        return out to v
    }

    @Test
    fun allSilenceProducesNothing() {
        val (out, v) = run(seq(0.0 to 200))
        assertTrue(out.isEmpty())
        assertNull(v.finish())
        assertEquals(0.0, v.speechRatio, 1e-9)
    }

    @Test
    fun singleBurstProducesExactlyOneSegment() {
        val (out, _) = run(seq(0.0 to 25, 0.9 to 75, 0.0 to 50))

        assertEquals(1, out.size)
        // 12 帧 preroll + 75 帧语音 - 30 帧尾部静音 = 84 帧
        assertEquals("切出的帧数不对", 84, framesOf(out[0]))
    }

    @Test
    fun probabilityBelowStartThresholdNeverStartsSpeech() {
        // 0.4 高于维持阈值 0.3，但低于进入阈值 0.5 —— 不应该起录
        val (out, _) = run(seq(0.4 to 300))
        assertTrue("低于进入阈值不应起录", out.isEmpty())
    }

    @Test
    fun hysteresisKeepsSpeechAcrossADipLongerThanSilenceWindow() {
        // 中间 40 帧（800ms）跌到 0.45：低于进入阈值但高于维持阈值。
        // 若迟滞失效，这 40 帧会超过 30 帧的断句阈值，把一句话切成两段。
        val (out, _) = run(seq(0.0 to 20, 0.9 to 40, 0.45 to 40, 0.9 to 40, 0.0 to 60))

        assertEquals("迟滞失效，句子被切断了", 1, out.size)
        assertEquals(129, framesOf(out[0]))
    }

    @Test
    fun shortBlipIsRejected() {
        // 验证「咳嗽 / 敲桌子」这类短促声音被丢掉
        val (out, _) = run(seq(0.0 to 25, 0.9 to 30, 0.0 to 60), minSpeechMs = 1500)
        assertTrue("低于最小时长的片段应被丢弃", out.isEmpty())
    }

    @Test
    fun continuousSpeechIsForceCutToBoundLength() {
        // 老师连讲不停时必须有硬切分，否则单段会无限长
        val (out, _) = run(seq(0.0 to 25, 0.9 to 200), maxSpeechMs = 1000)

        assertTrue("应产生多次强制切分，实际 ${out.size}", out.size >= 2)
        for (seg in out) {
            assertTrue("单段过长: ${framesOf(seg)} 帧", framesOf(seg) <= 50)
        }
    }

    @Test
    fun finishFlushesOpenSegment() {
        val script = seq(0.0 to 25, 0.9 to 60)
        val v = VadSegmenter(Scripted(script))
        val frame = ShortArray(frameSamples)
        for (p in script) v.accept(frame)

        val tail = v.finish()
        assertTrue("finish() 应吐出未闭合的语音", tail != null)
        assertTrue("吐出的帧数 = ${framesOf(tail!!)}", framesOf(tail) >= 30)
    }

    @Test
    fun nullProbabilityIsTreatedAsSilence() {
        // 检测器彻底不可用时，宁可一句不录，也不要往 ASR 灌垃圾
        val v = VadSegmenter(object : SpeechDetector {
            override val startThreshold = 0.5
            override val keepThreshold = 0.3
            override val name = "always-null"
            override fun probability(frame: ShortArray): Double? = null
        })
        val frame = ShortArray(frameSamples)
        repeat(300) { assertNull(v.accept(frame)) }
        assertNull(v.finish())
    }

    @Test
    fun speechRatioReflectsTrimmedSilence() {
        val (_, v) = run(seq(0.0 to 25, 0.9 to 75, 0.0 to 50))
        // 150 帧里 84 帧被判为语音
        assertTrue("speechRatio=${v.speechRatio}", v.speechRatio in 0.5..0.6)
    }
}
