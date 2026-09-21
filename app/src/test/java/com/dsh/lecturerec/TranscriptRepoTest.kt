package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 复制全文依赖这里的顺序与拼接规则，必须保证「按录音顺序、优先取校对稿」。 */
class TranscriptRepoTest {

    @Before
    fun setUp() {
        TranscriptRepo.clear()
    }

    @Test
    fun plainTextKeepsRecordingOrder() {
        TranscriptRepo.add(Segment(1, 0, 1000, text = "第一句"))
        TranscriptRepo.add(Segment(2, 1000, 2000, text = "第二句"))
        TranscriptRepo.add(Segment(3, 2000, 3000, text = "第三句"))

        assertEquals("第一句\n第二句\n第三句", TranscriptRepo.plainText())
    }

    @Test
    fun polishedTextWinsOverRawText() {
        TranscriptRepo.add(Segment(1, 0, 1000, text = "薛定恶方程"))
        TranscriptRepo.add(Segment(2, 1000, 2000, text = "第二句", polished = "薛定谔方程"))

        // 第 1 段没有校对稿 → 用原文；第 2 段有校对稿 → 用校对稿
        assertEquals("薛定恶方程\n薛定谔方程", TranscriptRepo.plainText())
        // 关掉校对稿偏好后，全部回到 ASR 原始输出
        assertEquals("薛定恶方程\n第二句", TranscriptRepo.plainText(usePolished = false))
    }

    @Test
    fun blankSegmentsAreSkipped() {
        TranscriptRepo.add(Segment(1, 0, 1000, text = "有内容"))
        TranscriptRepo.add(Segment(2, 1000, 2000, text = "   "))
        TranscriptRepo.add(Segment(3, 2000, 3000, text = "也有内容"))

        assertEquals("有内容\n也有内容", TranscriptRepo.plainText())
    }

    @Test
    fun updateReplacesOnlyTargetSegment() {
        TranscriptRepo.add(Segment(1, 0, 1000))
        TranscriptRepo.add(Segment(2, 1000, 2000))

        TranscriptRepo.update(2) { it.copy(text = "补上了", state = SegState.DONE) }

        assertEquals("", TranscriptRepo.find(1)?.text)
        assertEquals("补上了", TranscriptRepo.find(2)?.text)
        assertEquals(SegState.DONE, TranscriptRepo.find(2)?.state)
        assertEquals(1, TranscriptRepo.doneCount())
    }
}
