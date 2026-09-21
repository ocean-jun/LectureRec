package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Silero 的窗口重切。
 *
 * 采集帧是 320 样本（20ms），模型要的是 576（64 上下文 + 512 新增），
 * 两者不是整数倍关系 —— 这块缓冲一旦算错，喂给模型的就是错位音频，概率也就没意义了。
 */
class SileroWindowerTest {

    private fun constFrame(v: Short, n: Int = 320) = ShortArray(n) { v }

    @Test
    fun windowSizeMatchesSileroConvention() {
        assertEquals(576, SileroWindower().windowSize)     // 64 上下文 + 512 新增
    }

    @Test
    fun emitsNothingUntilBlockIsFull() {
        val w = SileroWindower()
        assertTrue("320 < 512，不该出窗口", w.push(constFrame(1)).isEmpty())
        assertEquals("攒到 512 应出窗口", 1, w.push(constFrame(2)).size)
    }

    @Test
    fun firstWindowHasZeroContext() {
        val w = SileroWindower()
        w.push(constFrame(100))
        val win = w.push(constFrame(200)).single()

        assertEquals(576, win.size)
        for (i in 0 until 64) assertEquals("首块上下文应为 0", 0f, win[i], 0f)
        assertEquals(100f / 32768f, win[64], 1e-6f)
        assertEquals(200f / 32768f, win[575], 1e-6f)
    }

    @Test
    fun contextIsTailOfPreviousBlock() {
        val w = SileroWindower(shift = 4, contextSize = 2)
        val first = w.push(shortArrayOf(1, 2, 3, 4)).single()
        assertEquals(6, first.size)
        assertEquals(0f, first[0], 0f)
        assertEquals(0f, first[1], 0f)

        val second = w.push(shortArrayOf(5, 6, 7, 8)).single()
        // 上下文 = 上一块末尾 2 个 = 3, 4
        assertEquals(3f / 32768f, second[0], 1e-6f)
        assertEquals(4f / 32768f, second[1], 1e-6f)
        assertEquals(5f / 32768f, second[2], 1e-6f)
    }

    @Test
    fun oversizedFrameYieldsMultipleWindowsAndKeepsRemainder() {
        // 回归测试：早期版本凑出第一个窗口就 return，会把同一帧里剩下的样本静默丢掉
        val w = SileroWindower(shift = 4, contextSize = 0)
        val wins = w.push(ShortArray(10) { (it + 1).toShort() })

        assertEquals("10 个样本应凑出 2 个 4 样本窗口", 2, wins.size)
        assertEquals(4, wins[0].size)
        assertEquals(1f / 32768f, wins[0][0], 1e-6f)
        assertEquals(5f / 32768f, wins[1][0], 1e-6f)

        // 剩下 2 个样本（9,10）应保留在缓冲里，下次补齐
        val last = w.push(shortArrayOf(11, 12)).single()
        assertEquals(9f / 32768f, last[0], 1e-6f)
        assertEquals(10f / 32768f, last[1], 1e-6f)
    }

    @Test
    fun resetClearsPendingSamplesAndContext() {
        val w = SileroWindower(shift = 4, contextSize = 2)
        w.push(shortArrayOf(1, 2, 3, 4))
        w.reset()

        val win = w.push(shortArrayOf(9, 9, 9, 9)).single()
        assertEquals("reset 后上下文应清零", 0f, win[0], 0f)
        assertEquals(9f / 32768f, win[2], 1e-6f)
    }
}
