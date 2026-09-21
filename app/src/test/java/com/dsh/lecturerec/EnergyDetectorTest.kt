package com.dsh.lecturerec

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 能量法兜底引擎。它只在 Silero 不可用时上场，但真机上的行为已经被验证过，
 * 这次重构成「概率 + 阈值」的形式后必须保持一致。
 */
class EnergyDetectorTest {

    private fun tone(amp: Int, n: Int = 320) = ShortArray(n) {
        (amp * sin(2 * PI * 220.0 * it / 16000)).toInt().toShort()
    }

    private fun silence() = ShortArray(320)

    @Test
    fun silenceScoresBelowStartThreshold() {
        val d = EnergyDetector()
        repeat(50) {
            assertTrue(d.probability(silence()) < d.startThreshold)
        }
    }

    @Test
    fun loudSpeechFromAQuietBaselineCrossesStartThreshold() {
        val d = EnergyDetector()
        repeat(25) { d.probability(silence()) }      // 让噪声底收敛
        assertTrue(d.probability(tone(3000)) >= d.startThreshold)
    }

    @Test
    fun thresholdsPreserveTheOriginalHandTunedBehaviour() {
        // 重构前是「rms > 3*noise 起判、rms > 0.55*3*noise 维持」，
        // 换算成概率就是 0.99 / 0.01。钉住它，别再漂移。
        val d = EnergyDetector()
        assertTrue("startThreshold=${d.startThreshold}", d.startThreshold > 0.98)
        assertTrue("keepThreshold=${d.keepThreshold}", d.keepThreshold < 0.02)
    }

    @Test
    fun constantLoudNoiseIsTreatedAsSpeech() {
        // 说明性测试：能量法只在判定为静音时才跟踪噪声底，
        // 所以持续的高强度噪声会被一直当成语音 —— 这正是它不如 Silero 的地方。
        val d = EnergyDetector()
        repeat(200) { d.probability(tone(2000)) }
        assertTrue(d.probability(tone(2000)) >= d.startThreshold)
    }

    @Test
    fun quietGapBringsProbabilityBackDown() {
        val d = EnergyDetector()
        repeat(100) { d.probability(tone(2000)) }

        var last = 1.0
        repeat(200) { last = d.probability(silence()) }
        assertTrue("安静后应重新判为静音, p=$last", last < d.startThreshold)
    }

    @Test
    fun resetRestoresInitialNoiseFloor() {
        val d = EnergyDetector()
        repeat(300) { d.probability(silence()) }     // 把噪声底压到很低
        d.reset()
        // reset 后底噪回到初值，同样的弱声音不应被当成语音
        assertTrue(d.probability(tone(300)) < d.startThreshold)
    }
}
