package com.dsh.lecturerec

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 把裸 PCM 包成 WAV，便于直接走 /v1/audio/transcriptions。 */
object Wav {

    fun wrap(pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bits: Int = 16): ByteArray {
        val blockAlign = channels * bits / 8
        val byteRate = sampleRate * blockAlign
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)                       // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)

        val out = ByteArray(44 + pcm.size)
        System.arraycopy(header.array(), 0, out, 0, 44)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }
}
