package com.dsh.lecturerec

/** 一轮该写入文件的内容，以及写入后的游标。 */
data class WriteBatch(val segments: List<Segment>, val cursor: Long)

/**
 * 决定「按录音顺序，接下来该写哪几段进文件」。
 *
 * 为什么需要它：上传改成并发之后，片段**完成的先后顺序是乱的**。
 * 如果按完成顺序往文件里追加，导出的笔记时间戳会来回跳（实测出现过
 * `[00:00:17]` 后面跟着 `[00:00:16]`、再跟着 `[00:00:01]`）。
 *
 * 规则：
 *  - 只写连续的、已经进入最终状态的片段；
 *  - 中间卡着一个还在排队/上传中的，就**停在它前面等** —— 保证顺序；
 *  - 失败的片段跳过但不阻塞后面的（它的音频已经留底，用户可以手动重传）。
 */
object TranscriptWriter {

    fun pendingWrites(all: List<Segment>, cursor: Long): WriteBatch {
        val out = ArrayList<Segment>()
        var c = cursor

        for (seg in all) {
            if (seg.id <= c) continue
            when (seg.state) {
                SegState.DONE -> {
                    out.add(seg)
                    c = seg.id
                }
                SegState.FAILED -> {
                    c = seg.id          // 跳过，但不拖住后面的
                }
                else -> break           // PENDING / UPLOADING：还没轮到它，等
            }
        }
        return WriteBatch(out, c)
    }
}
