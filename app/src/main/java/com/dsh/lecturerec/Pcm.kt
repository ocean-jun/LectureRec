package com.dsh.lecturerec

/**
 * 裸 PCM 小工具。
 *
 * 单独抽出来是因为拼接时的偏移量算错会**直接毁掉音频**（听起来就是错位/杂音），
 * 而这种错误在真机上很难看出来，必须靠单元测试钉住。
 */
object Pcm {

    /** 按顺序拼接。只有一段时直接返回它，不做多余拷贝。 */
    fun concat(chunks: List<ByteArray>): ByteArray {
        if (chunks.isEmpty()) return ByteArray(0)
        if (chunks.size == 1) return chunks[0]

        var total = 0
        for (c in chunks) total += c.size

        val out = ByteArray(total)
        var offset = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, offset, c.size)
            offset += c.size
        }
        return out
    }

    /** 16bit 单声道下的时长。 */
    fun durationMs(bytes: Int, sampleRate: Int = 16000): Long =
        bytes.toLong() * 1000L / (sampleRate * 2L)
}
