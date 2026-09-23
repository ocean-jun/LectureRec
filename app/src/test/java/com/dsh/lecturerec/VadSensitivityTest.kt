package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收音灵敏度。
 *
 * 用户反馈「要很大声才能触发录音」，这一项就是给他调的旋钮：
 * 阈值直接乘在检测器自带值上，所以对 Silero 和能量法都有效。
 */
class VadSensitivityTest {

    private class Fixed(
        private val p: Double,
        override val startThreshold: Double = 0.5,
        override val keepThreshold: Double = 0.35
    ) : SpeechDetector {
        override val name = "fixed"
        override fun probability(frame: ShortArray): Double = p
    }

    /** 概率可中途改，用来模拟「先说后静」。 */
    private class Adjustable(
        override val startThreshold: Double = 0.5,
        override val keepThreshold: Double = 0.35
    ) : SpeechDetector {
        override val name = "adjustable"
        var p = 0.0
        override fun probability(frame: ShortArray): Double = p
    }

    private val frame = ShortArray(320)

    /** 说 1 秒（50 帧）再静音 1.2 秒，返回切出几段。 */
    private fun segmentsAt(sensitivity: Double, speechP: Double): Int {
        val det = Adjustable()
        val v = VadSegmenter(det, sensitivity = sensitivity)
        var segments = 0

        det.p = speechP
        repeat(50) { v.accept(frame) }
        det.p = 0.0                     // 真的静下来，句子才闭得上
        repeat(60) { if (v.accept(frame) != null) segments++ }
        return segments
    }

    @Test
    fun defaultSensitivityKeepsDetectorThresholds() {
        val v = VadSegmenter(Fixed(0.9))
        assertEquals(0.5, v.startThreshold, 1e-9)
        assertEquals(0.35, v.keepThreshold, 1e-9)
    }

    @Test
    fun highSensitivityLowersTheTriggerPoint() {
        val v = VadSegmenter(Fixed(0.9), sensitivity = 0.6)
        assertEquals(0.30, v.startThreshold, 1e-9)
        assertEquals(0.21, v.keepThreshold, 1e-9)
    }

    @Test
    fun weakSpeechTriggersOnlyAtHighSensitivity() {
        // 概率 0.35：默认阈值 0.5 起不来；灵敏度「高」时阈值降到 0.30，就能起来
        assertEquals("默认灵敏度不该被 0.35 触发", 0, segmentsAt(1.0, 0.35))
        assertTrue("高灵敏度应该能触发", segmentsAt(0.6, 0.35) > 0)
    }

    @Test
    fun lowSensitivityRejectsMarginalAudio() {
        // 灵敏度「低」→ 阈值 0.7，0.6 的概率不该触发
        assertEquals(0, segmentsAt(1.4, 0.6))
    }

    @Test
    fun thresholdsAreClampedToSaneRange() {
        // 极端参数不该把阈值推到 0 或 1 之外
        assertTrue(VadSegmenter(Fixed(0.9), sensitivity = 0.001).startThreshold >= 0.02)
        assertTrue(VadSegmenter(Fixed(0.9), sensitivity = 99.0).startThreshold <= 0.98)
    }

    @Test
    fun keepThresholdNeverExceedsStartThreshold() {
        // 能量法的 keep 本来就远低于 start，缩放后必须仍然保持 keep <= start，
        // 否则迟滞逻辑会反过来，句子被立刻切断
        val v = VadSegmenter(
            Fixed(0.9, startThreshold = 0.99, keepThreshold = 0.01),
            sensitivity = 1.4
        )
        assertTrue("keep=${v.keepThreshold} start=${v.startThreshold}", v.keepThreshold <= v.startThreshold)
    }

    @Test
    fun spinnerOptionsRoundTrip() {
        for (i in VadSensitivity.OPTIONS.indices) {
            assertEquals(i, VadSensitivity.indexOf(VadSensitivity.valueOf(i)))
        }
        // 未知值应落回默认，而不是崩掉
        assertEquals(VadSensitivity.DEFAULT, VadSensitivity.valueOf(99), 1e-9)
        assertEquals(VadSensitivity.indexOf(VadSensitivity.DEFAULT), VadSensitivity.indexOf(12345.0))
    }

    @Test
    fun lastProbabilityIsExposedForDiagnostics() {
        val v = VadSegmenter(Fixed(0.77))
        v.accept(frame)
        assertEquals(0.77, v.lastProbability, 1e-9)
    }
}
