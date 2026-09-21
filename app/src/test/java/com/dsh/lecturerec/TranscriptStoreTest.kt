package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「恢复上次记录」依赖这个解析器；解析错了会把时间戳和正文搞乱。 */
class TranscriptStoreTest {

    @Test
    fun parsesEveryModeTimestamps() {
        val md = """
            # 2026-09-21_1430 高等数学

            [00:00:05] 今天我们讲拉格朗日乘子。
            [00:01:02] 先看一个例子。
        """.trimIndent()

        val segs = TranscriptStore.parseSegments(md)
        assertEquals(2, segs.size)
        assertEquals("今天我们讲拉格朗日乘子。", segs[0].text)
        assertEquals(5_000L, segs[0].startMs)
        assertEquals("先看一个例子。", segs[1].text)
        assertEquals(62_000L, segs[1].startMs)
    }

    @Test
    fun untimestampedLinesAreContentNotGarbage() {
        // 每分钟模式下大部分行没有时间戳 —— 它们都是正文，绝不能被丢掉
        val md = """
            # 标题

            [00:00] 第一句。
            第二句。
            第三句。
            [00:01] 跨分钟了。
            又一句。
        """.trimIndent()

        val segs = TranscriptStore.parseSegments(md)
        assertEquals(5, segs.size)
        assertEquals(
            listOf("第一句。", "第二句。", "第三句。", "跨分钟了。", "又一句。"),
            segs.map { it.text }
        )
        // 没有时间戳的行沿用最近一次见到的时间戳
        assertEquals(0L, segs[0].startMs)
        assertEquals(0L, segs[1].startMs)
        assertEquals(0L, segs[2].startMs)
        assertEquals(60_000L, segs[3].startMs)
        assertEquals(60_000L, segs[4].startMs)
    }

    @Test
    fun onlyHeadingsAndBlankLinesAreSkipped() {
        val md = """
            # 标题

            [00:00] 正文
            随便一行也是正文

        """.trimIndent()

        val segs = TranscriptStore.parseSegments(md)
        assertEquals(2, segs.size)
        assertEquals("正文", segs[0].text)
        assertEquals("随便一行也是正文", segs[1].text)
    }

    @Test
    fun handlesHourRollover() {
        val segs = TranscriptStore.parseSegments("[01:02:03] 内容")
        assertEquals(1, segs.size)
        assertEquals((1 * 3600 + 2 * 60 + 3) * 1000L, segs[0].startMs)
    }

    @Test
    fun roundTripThroughEveryModeFormat() {
        for (ms in listOf(0L, 1_000L, 59_000L, 60_000L, 3_599_000L, 3_600_000L, 7_199_000L)) {
            val line = "${Timestamps.label(Timestamps.EVERY, ms)} 正文"
            val parsed = TranscriptStore.parseSegments(line)
            assertEquals("解析后段数不对, ms=$ms", 1, parsed.size)
            assertEquals("时间戳还原失败, ms=$ms", ms, parsed[0].startMs)
        }
    }

    @Test
    fun roundTripThroughMinuteModeFormat() {
        // 每分钟模式精度只到分钟，还原出来是那一分钟的起点
        val parsed = TranscriptStore.parseSegments("[01:02] 正文")
        assertEquals(1, parsed.size)
        assertEquals((1 * 3600 + 2 * 60) * 1000L, parsed[0].startMs)
    }

    @Test
    fun restoredSegmentsAreMarkedDone() {
        val segs = TranscriptStore.parseSegments("[00:00:01] 内容")
        assertEquals(SegState.DONE, segs[0].state)
        assertEquals("内容", segs[0].text)
    }

    @Test
    fun emptyOrHeaderOnlyInputGivesNothing() {
        assertTrue(TranscriptStore.parseSegments("").isEmpty())
        assertTrue(TranscriptStore.parseSegments("# 只有标题\n\n").isEmpty())
    }
}
