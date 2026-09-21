package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampsTest {

    @Test
    fun noneModeNeverStamps() {
        assertFalse(Timestamps.shouldStamp(Timestamps.NONE, 0, null))
        assertFalse(Timestamps.shouldStamp(Timestamps.NONE, 120_000, 1_000))
    }

    @Test
    fun everyModeAlwaysStamps() {
        assertTrue(Timestamps.shouldStamp(Timestamps.EVERY, 0, null))
        assertTrue(Timestamps.shouldStamp(Timestamps.EVERY, 5_000, 4_000))
    }

    @Test
    fun minuteModeStampsTheFirstSegment() {
        assertTrue("首段必须有时间戳", Timestamps.shouldStamp(Timestamps.MINUTE, 0, null))
        assertTrue(Timestamps.shouldStamp(Timestamps.MINUTE, 30_000, null))
    }

    @Test
    fun minuteModeStampsOnlyOnMinuteChange() {
        val m = Timestamps.MINUTE
        // 同一分钟内不再重复标
        assertFalse(Timestamps.shouldStamp(m, 10_000, 1_000))
        assertFalse(Timestamps.shouldStamp(m, 59_999, 1_000))
        // 跨到下一分钟才标
        assertTrue(Timestamps.shouldStamp(m, 60_000, 59_999))
        assertTrue(Timestamps.shouldStamp(m, 125_000, 62_000))
    }

    @Test
    fun minuteModeHandlesHourRollover() {
        val m = Timestamps.MINUTE
        assertFalse(Timestamps.shouldStamp(m, 3_599_000, 3_540_000))   // 都在第 59 分钟
        assertTrue(Timestamps.shouldStamp(m, 3_600_000, 3_599_000))    // 进入第 60 分钟
    }

    @Test
    fun labelsMatchTheMode() {
        assertEquals("[00:01:23]", Timestamps.label(Timestamps.EVERY, 83_000))
        assertEquals("[00:01]", Timestamps.label(Timestamps.MINUTE, 83_000))
        assertEquals("[01:00]", Timestamps.label(Timestamps.MINUTE, 3_600_000))
        assertEquals("[00:00]", Timestamps.label(Timestamps.MINUTE, 0))
    }

    @Test
    fun modeKeyRoundTripsThroughSpinnerIndex() {
        for (key in listOf(Timestamps.EVERY, Timestamps.MINUTE, Timestamps.NONE)) {
            assertEquals(key, Timestamps.keyOf(Timestamps.indexOf(key)))
        }
        // 未知值应落回默认（每分钟），而不是崩掉
        assertEquals(Timestamps.MINUTE, Timestamps.keyOf(Timestamps.indexOf("垃圾值")))
        assertEquals(1, Timestamps.indexOf("垃圾值"))
    }
}
