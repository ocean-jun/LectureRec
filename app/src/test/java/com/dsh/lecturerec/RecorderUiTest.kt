package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：实测出现过「停止不了」—— 按下停止后 recording 立刻变 false，
 * 但服务还在把已录内容传完；界面此时若显示「开始听课」，用户一按就**启动了一次全新录音**。
 */
class RecorderUiTest {

    @Test
    fun idleMeansStart() {
        assertEquals(
            RecorderUi.Action.START,
            RecorderUi.actionFor(RecStatus(recording = false, finishing = false))
        )
    }

    @Test
    fun recordingMeansStop() {
        assertEquals(
            RecorderUi.Action.STOP,
            RecorderUi.actionFor(RecStatus(recording = true, finishing = false))
        )
    }

    @Test
    fun finishingMeansIgnore() {
        // 关键用例：收尾期绝不能是 START，否则按一次就重新开录
        assertEquals(
            RecorderUi.Action.IGNORE,
            RecorderUi.actionFor(RecStatus(recording = false, finishing = true))
        )
    }

    @Test
    fun finishingWinsOverRecordingWhenBothSet() {
        // 理论上不会同时为真，但真出现时宁可忽略点击，也不要误操作
        assertEquals(
            RecorderUi.Action.IGNORE,
            RecorderUi.actionFor(RecStatus(recording = true, finishing = true))
        )
    }

    @Test
    fun finishingDefaultsToFalse() {
        assertEquals(false, RecStatus().finishing)
        assertEquals(RecorderUi.Action.START, RecorderUi.actionFor(RecStatus()))
    }

    @Test
    fun aQuickSecondTapAfterStopIsTreatedAsMisclick() {
        // 实测：收尾只要 73ms，用户 400ms 后补一刀就会重新开录
        val stoppedAt = 1_000_000L
        assertTrue(RecorderUi.isWithinRestartCooldown(stoppedAt + 400, stoppedAt))
        assertTrue(RecorderUi.isWithinRestartCooldown(stoppedAt + 1499, stoppedAt))
    }

    @Test
    fun aDeliberateRestartLaterIsAllowed() {
        val stoppedAt = 1_000_000L
        assertFalse(RecorderUi.isWithinRestartCooldown(stoppedAt + 1500, stoppedAt))
        assertFalse(RecorderUi.isWithinRestartCooldown(stoppedAt + 60_000, stoppedAt))
    }

    @Test
    fun neverStoppedMeansNoCooldown() {
        // 从没按过停止时不该被冷却挡住，否则第一次就用不了
        assertFalse(RecorderUi.isWithinRestartCooldown(1_000_000L, 0L))
    }
}
