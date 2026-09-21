package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降级逻辑：Silero 在某个机型上跑不动时，绝不能整堂课一句都录不下来。
 */
class FallbackDetectorTest {

    private class Scripted(
        override val startThreshold: Double,
        override val keepThreshold: Double,
        override val name: String,
        private val values: List<Double?>
    ) : SpeechDetector {
        private var i = 0
        override fun probability(frame: ShortArray): Double? {
            val idx = i++
            return if (idx < values.size) values[idx] else values.lastOrNull() ?: 0.0
        }
    }

    private val frame = ShortArray(320)

    private fun primary(values: List<Double?>) =
        Scripted(0.5, 0.35, "silero-v5", values)

    private fun fallback() =
        Scripted(0.99, 0.01, "energy", List(100) { 0.9 })

    @Test
    fun degradesAfterConsecutiveFailures() {
        val d = FallbackDetector(primary(List(10) { null }), fallback(), maxConsecutiveFailures = 3)

        assertFalse(d.degraded)
        assertNull(d.probability(frame))
        assertNull(d.probability(frame))
        assertNull(d.probability(frame))

        assertTrue("连续 3 次失败后应降级", d.degraded)
        assertEquals(0.9, d.probability(frame)!!, 1e-9)
        assertEquals("energy", d.name)
    }

    @Test
    fun thresholdsFollowTheActiveDetector() {
        val d = FallbackDetector(primary(List(10) { null }), fallback(), maxConsecutiveFailures = 2)
        assertEquals(0.5, d.startThreshold, 1e-9)

        d.probability(frame)
        d.probability(frame)

        assertTrue(d.degraded)
        // 切换后阈值必须跟着换，否则两种引擎的概率尺度不同，切句会全乱
        assertEquals(0.99, d.startThreshold, 1e-9)
        assertEquals(0.01, d.keepThreshold, 1e-9)
    }

    @Test
    fun occasionalFailuresDoNotDegrade() {
        val values = ArrayList<Double?>()
        repeat(20) {
            values.add(null)
            values.add(0.8)
        }
        val d = FallbackDetector(primary(values), fallback(), maxConsecutiveFailures = 3)

        repeat(40) { d.probability(frame) }
        assertFalse("零星失败不应降级", d.degraded)
    }

    @Test
    fun recoveryResetsTheFailureCounter() {
        // 失败两次 -> 成功一次 -> 再失败两次，不应触发降级
        val values = listOf<Double?>(null, null, 0.9, null, null, 0.9, null, null, 0.9)
        val d = FallbackDetector(primary(values), fallback(), maxConsecutiveFailures = 3)

        repeat(values.size) { d.probability(frame) }
        assertFalse("中间成功过就不该累计到降级", d.degraded)
    }
}
