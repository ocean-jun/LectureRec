package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 并发上传后，片段完成的顺序是乱的。文件必须按**录音顺序**写，
 * 否则导出的笔记时间戳会来回跳 —— 这是实测踩过的回归。
 */
class TranscriptWriterTest {

    private fun seg(id: Long, state: SegState) = Segment(
        id = id,
        startMs = id * 1000,
        endMs = id * 1000 + 500,
        text = "第${id}段",
        state = state
    )

    @Test
    fun writesContiguousDoneSegments() {
        val all = listOf(seg(1, SegState.DONE), seg(2, SegState.DONE), seg(3, SegState.DONE))
        val w = TranscriptWriter.pendingWrites(all, 0)

        assertEquals(listOf(1L, 2L, 3L), w.segments.map { it.id })
        assertEquals(3L, w.cursor)
    }

    @Test
    fun stopsAtTheFirstUnsettledSegment() {
        // 第 2 段还在上传中：绝不能先写第 3 段，否则顺序就乱了
        val all = listOf(
            seg(1, SegState.DONE),
            seg(2, SegState.UPLOADING),
            seg(3, SegState.DONE)
        )
        val w = TranscriptWriter.pendingWrites(all, 0)

        assertEquals("只能写到卡住的那一段之前", listOf(1L), w.segments.map { it.id })
        assertEquals(1L, w.cursor)
    }

    @Test
    fun stopsAtPendingSegmentsToo() {
        val all = listOf(seg(1, SegState.DONE), seg(2, SegState.PENDING), seg(3, SegState.DONE))
        val w = TranscriptWriter.pendingWrites(all, 0)
        assertEquals(listOf(1L), w.segments.map { it.id })
    }

    @Test
    fun skipsFailedSegmentsWithoutBlockingLaterOnes() {
        // 失败片段的音频已留底，用户可手动重传；它不应该把后面的内容永远堵住
        val all = listOf(
            seg(1, SegState.DONE),
            seg(2, SegState.FAILED),
            seg(3, SegState.DONE)
        )
        val w = TranscriptWriter.pendingWrites(all, 0)

        assertEquals("失败段跳过，后面的照写", listOf(1L, 3L), w.segments.map { it.id })
        assertEquals(3L, w.cursor)
    }

    @Test
    fun doesNotRewriteAlreadyWrittenSegments() {
        val all = listOf(seg(1, SegState.DONE), seg(2, SegState.DONE), seg(3, SegState.DONE))
        val w = TranscriptWriter.pendingWrites(all, 2)

        assertEquals(listOf(3L), w.segments.map { it.id })
        assertEquals(3L, w.cursor)
    }

    @Test
    fun nothingNewGivesEmptyBatchAndKeepsCursor() {
        val all = listOf(seg(1, SegState.DONE), seg(2, SegState.UPLOADING))
        val w = TranscriptWriter.pendingWrites(all, 1)

        assertTrue(w.segments.isEmpty())
        assertEquals("游标不该倒退", 1L, w.cursor)
    }

    @Test
    fun outOfOrderCompletionStillWritesInRecordingOrder() {
        // 模拟并发下 3 先完成、2 后完成
        val afterThree = listOf(
            seg(1, SegState.DONE),
            seg(2, SegState.UPLOADING),
            seg(3, SegState.DONE)
        )
        val first = TranscriptWriter.pendingWrites(afterThree, 0)
        assertEquals(listOf(1L), first.segments.map { it.id })

        val afterTwo = listOf(
            seg(1, SegState.DONE),
            seg(2, SegState.DONE),
            seg(3, SegState.DONE)
        )
        val second = TranscriptWriter.pendingWrites(afterTwo, first.cursor)
        assertEquals("这一段应补写 2 和 3，且 2 在前", listOf(2L, 3L), second.segments.map { it.id })
    }
}
