package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：失败片段文件名曾经只用段 id（每个会话都从 1 开始），
 * 结果新课堂的失败片段会覆盖上一堂课的留底音频 —— 静默的数据丢失。
 */
class RetryStoreTest {

    @Test
    fun namesAreUniqueAcrossSegmentsAndSessions() {
        val sameSession1 = RetryStore.fileName(1_700_000_000_000L, 1L)
        val sameSession2 = RetryStore.fileName(1_700_000_000_000L, 2L)
        val nextSession1 = RetryStore.fileName(1_700_000_060_000L, 1L)

        assertEquals(3, setOf(sameSession1, sameSession2, nextSession1).size)
        assertTrue(sameSession1.endsWith(".wav"))
    }

    @Test
    fun namesSortChronologically() {
        // 重传是按文件名排序处理的，顺序必须等于录音顺序
        val older = RetryStore.fileName(1_700_000_000_000L, 9L)
        val newer = RetryStore.fileName(1_700_000_060_000L, 1L)
        assertTrue("$older 应排在 $newer 之前", older < newer)
    }

    @Test
    fun segmentOrderIsPreservedWithinASession() {
        val first = RetryStore.fileName(1_700_000_000_000L, 1L)
        val tenth = RetryStore.fileName(1_700_000_000_000L, 10L)
        assertTrue("$first 应排在 $tenth 之前", first < tenth)
    }
}
