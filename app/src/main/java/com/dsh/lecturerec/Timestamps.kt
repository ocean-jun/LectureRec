package com.dsh.lecturerec

import java.util.Locale

/**
 * 时间戳的显示方式。
 *
 * 课堂笔记是拿来读的，每段前面都挂一个 `[00:08:33]` 会把文字切得很碎。
 * 默认改成「每分钟只标一次」：跨分钟才插一个标记，中间的文字连成一片，
 * 同时保留「跳到第几分钟」的能力。
 */
object Timestamps {

    const val EVERY = "every"
    const val MINUTE = "minute"
    const val NONE = "none"

    const val DEFAULT = MINUTE

    val LABELS = listOf("每段都标时间", "每分钟标一次（推荐）", "不标时间")

    fun keyOf(index: Int): String = when (index) {
        0 -> EVERY
        2 -> NONE
        else -> MINUTE
    }

    fun indexOf(key: String): Int = when (key) {
        EVERY -> 0
        NONE -> 2
        else -> 1
    }

    /**
     * 这一段要不要落时间戳。
     *
     * [prevStartMs] 是上一段的开始时间，null 表示这是第一段。
     * 判断跨分钟用的是**会话内分钟序号**，不是墙上时钟的分钟。
     */
    fun shouldStamp(mode: String, startMs: Long, prevStartMs: Long?): Boolean = when (mode) {
        NONE -> false
        EVERY -> true
        else -> prevStartMs == null || startMs / 60_000L != prevStartMs / 60_000L
    }

    /** `[00:01]`（每分钟模式）或 `[00:01:23]`（每段模式）。 */
    fun label(mode: String, startMs: Long): String {
        val total = startMs / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (mode == EVERY) {
            String.format(Locale.US, "[%02d:%02d:%02d]", h, m, s)
        } else {
            String.format(Locale.US, "[%02d:%02d]", h, m)
        }
    }
}
