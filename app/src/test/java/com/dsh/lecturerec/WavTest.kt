package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Test

/** WAV 头写错的话，整个上传链路都会失败，所以逐字段校验。 */
class WavTest {

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    @Test
    fun headerMatchesPcmPayload() {
        val pcm = ByteArray(3200)               // 100ms @ 16k/16bit/mono
        val w = Wav.wrap(pcm)

        assertEquals(44 + pcm.size, w.size)
        assertEquals("RIFF", String(w, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(w, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(w, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(w, 36, 4, Charsets.US_ASCII))

        assertEquals(36 + pcm.size, le32(w, 4))
        assertEquals(16, le32(w, 16))           // fmt chunk 长度
        assertEquals(1, le16(w, 20))            // PCM
        assertEquals(1, le16(w, 22))            // 单声道
        assertEquals(16000, le32(w, 24))        // 采样率
        assertEquals(32000, le32(w, 28))        // 字节率 = 16000 * 1 * 2
        assertEquals(2, le16(w, 32))            // block align
        assertEquals(16, le16(w, 34))           // 位深
        assertEquals(pcm.size, le32(w, 40))     // data 长度
    }

    @Test
    fun payloadIsCopiedVerbatim() {
        val pcm = ByteArray(100) { it.toByte() }
        val w = Wav.wrap(pcm)
        for (i in pcm.indices) {
            assertEquals(pcm[i], w[44 + i])
        }
    }
}
