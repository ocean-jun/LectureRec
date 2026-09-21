package com.dsh.lecturerec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拼接偏移量写错会直接毁掉音频，必须在单测里钉死。 */
class PcmTest {

    @Test
    fun concatPreservesOrderAndContent() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5)
        val c = byteArrayOf(6)

        val joined = Pcm.concat(listOf(a, b, c))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), joined)
    }

    @Test
    fun singleChunkIsReturnedWithoutCopy() {
        val a = byteArrayOf(9, 8, 7)
        assertSame("只有一段时不该多拷一份", a, Pcm.concat(listOf(a)))
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertEquals(0, Pcm.concat(emptyList()).size)
    }

    @Test
    fun handlesChunksOfVeryDifferentSizes() {
        val big = ByteArray(10_000) { 7 }
        val small = byteArrayOf(1)
        val joined = Pcm.concat(listOf(big, small, big))

        assertEquals(20_001, joined.size)
        assertEquals(7.toByte(), joined[0])
        assertEquals(7.toByte(), joined[9_999])
        assertEquals(1.toByte(), joined[10_000])
        assertEquals(7.toByte(), joined[10_001])
        assertEquals(7.toByte(), joined[20_000])
    }

    @Test
    fun durationMatchesSixteenKhzMonoSixteenBit() {
        // 16kHz / 单声道 / 16bit = 每秒 32000 字节
        assertEquals(1000L, Pcm.durationMs(32_000))
        assertEquals(500L, Pcm.durationMs(16_000))
        assertEquals(0L, Pcm.durationMs(0))
    }

    @Test
    fun concatDoesNotMutateInputs() {
        val a = byteArrayOf(1, 2)
        val b = byteArrayOf(3, 4)
        Pcm.concat(listOf(a, b))
        assertArrayEquals(byteArrayOf(1, 2), a)
        assertArrayEquals(byteArrayOf(3, 4), b)
        assertTrue(a.size == 2 && b.size == 2)
    }
}
