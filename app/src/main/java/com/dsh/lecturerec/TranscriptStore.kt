package com.dsh.lecturerec

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每句话一落盘，进程被杀也不丢内容。
 * 写在自己应用的外部目录，不需要任何存储权限；导出走 FileProvider 分享。
 */
class TranscriptStore(context: Context) {

    private val dir: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "transcripts"
    ).apply { mkdirs() }

    /** 上一次写进文件的片段开始时间，用于判断「跨分钟」；换会话要清掉。 */
    private var lastWrittenStartMs: Long? = null

    fun newSession(courseTitle: String): File {
        lastWrittenStartMs = null
        val stamp = stamp()
        val name = if (courseTitle.isBlank()) {
            "$stamp 课堂记录.md"
        } else {
            "$stamp ${courseTitle.sanitize()}.md"
        }
        val f = File(dir, name)
        if (!f.exists()) {
            f.writeText("# $stamp ${courseTitle.ifBlank { "课堂记录" }}\n\n")
        }
        return f
    }

    /**
     * 追加一段。时间戳按 [mode] 决定：
     *  - EVERY：每段都带 `[00:12:34]`
     *  - MINUTE：只在跨分钟时插一个 `[00:12]`，中间的段落没有前缀，读起来是连贯的大段
     *  - NONE：完全不带
     */
    fun appendSegment(file: File, seg: Segment, mode: String) {
        val text = (seg.polished ?: seg.text).trim()
        if (text.isEmpty()) return

        val prefix = if (Timestamps.shouldStamp(mode, seg.startMs, lastWrittenStartMs)) {
            Timestamps.label(mode, seg.startMs) + " "
        } else {
            ""
        }
        runCatching { file.appendText("$prefix$text\n\n") }
        lastWrittenStartMs = seg.startMs
    }

    /** 保存 AI 校对后的整篇结果，另存为新文件，不覆盖原始记录。 */
    fun savePolished(source: File?, text: String): File {
        val base = source?.nameWithoutExtension ?: "${stamp()} 课堂记录"
        val f = File(dir, "${base}_校对.md")
        f.writeText(text)
        return f
    }

    fun latest(): File? =
        dir.listFiles()?.filter { it.isFile && it.extension == "md" }?.maxByOrNull { it.lastModified() }

    /**
     * 从最近一次记录文件把内容恢复回界面。
     * App 被系统杀掉、或用户重新打开时，不用去翻 Android/data 目录就能接着看。
     */
    fun restoreLatest(): Int {
        val f = latest() ?: return 0
        if (f.nameWithoutExtension.endsWith("校对")) return 0

        val segs = parseSegments(f.readText())
        if (segs.isEmpty()) return 0
        TranscriptRepo.replaceAll(segs)
        return segs.size
    }

    fun dir(): File = dir

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.CHINA).format(Date())

    private fun String.sanitize(): String =
        replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").take(40)

    companion object {
        /**
         * 行首可能是 `[00:12:34]`（每段模式），也可能是 `[00:12]`（每分钟模式），
         * 还可能**完全没有时间戳**（每分钟模式下的大部分行）。三种都要认。
         */
        private val LINE = Regex("^\\[(\\d{2}):(\\d{2})(?::(\\d{2}))?\\]\\s*(.*)$")

        /**
         * 纯函数，便于单元测试：把落盘的 Markdown 解析回片段。
         *
         * 没有时间戳的行是**正文**（不是脏数据），沿用最近一次见到的时间戳。
         * 只有空行和 `#` 标题会被丢掉。
         */
        fun parseSegments(content: String): List<Segment> {
            val segs = ArrayList<Segment>()
            var id = 1L
            var currentMs = 0L

            for (raw in content.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                val m = LINE.find(line)
                val text: String
                if (m != null) {
                    val h = m.groupValues[1].toLong()
                    val mi = m.groupValues[2].toLong()
                    val sec = m.groupValues[3].let { if (it.isEmpty()) 0L else it.toLong() }
                    currentMs = (h * 3600 + mi * 60 + sec) * 1000
                    text = m.groupValues[4].trim()
                } else {
                    text = line
                }
                if (text.isEmpty()) continue

                segs.add(
                    Segment(
                        id = id++,
                        startMs = currentMs,
                        endMs = currentMs,
                        text = text,
                        state = SegState.DONE
                    )
                )
            }
            return segs
        }

        fun fmt(ms: Long): String {
            val total = ms / 1000
            return String.format(
                Locale.US, "%02d:%02d:%02d",
                total / 3600, (total % 3600) / 60, total % 60
            )
        }
    }
}
