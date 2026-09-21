package com.dsh.lecturerec

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 上传失败的语音片段落盘暂存，避免网络抖一下就永久丢失课堂内容。
 * 对应界面上「重传失败片段」。
 */
object RetryStore {

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "failed").apply { mkdirs() }

    /**
     * 文件名必须全局唯一。段 id 在每个会话里都从 1 重新开始，
     * 只用 id 命名会让新课堂的失败片段覆盖掉上一堂课的留底音频。
     * 前缀用会话开始时间戳，既保证唯一，按名排序也仍是时间顺序。
     */
    fun fileName(sessionStamp: Long, segmentId: Long): String =
        String.format(Locale.US, "%013d_%06d.wav", sessionStamp, segmentId)

    fun save(ctx: Context, fileName: String, wav: ByteArray) {
        runCatching { File(dir(ctx), fileName).writeBytes(wav) }
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile && it.extension == "wav" }?.sortedBy { it.name }
            ?: emptyList()

    fun delete(f: File) {
        runCatching { f.delete() }
    }

    fun count(ctx: Context): Int = list(ctx).size
}
