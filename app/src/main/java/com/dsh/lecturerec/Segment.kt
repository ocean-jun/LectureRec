package com.dsh.lecturerec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SegState { PENDING, UPLOADING, DONE, FAILED }

data class Segment(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String = "",
    val state: SegState = SegState.PENDING,
    val polished: String? = null
)

data class RecStatus(
    val recording: Boolean = false,
    /**
     * 已经按下停止、但还在把已录内容传完的「收尾期」。
     *
     * 必须有这个状态：收尾期里 recording 已经是 false，如果界面据此显示「开始听课」，
     * 用户一按就会**启动一次全新录音** —— 表现就是「停止不了」。实测踩过。
     */
    val finishing: Boolean = false,
    val startedAt: Long = 0L,
    val queued: Int = 0,
    val failed: Int = 0,
    val lastError: String? = null
)

/**
 * 进程内单例：Service 写，Activity 读。
 * 转写文本量不大，用 StateFlow 直接暴露不可变快照即可。
 */
object TranscriptRepo {

    private val _segments = MutableStateFlow<List<Segment>>(emptyList())
    val segments: StateFlow<List<Segment>> = _segments.asStateFlow()

    private val _status = MutableStateFlow(RecStatus())
    val status: StateFlow<RecStatus> = _status.asStateFlow()

    @Synchronized
    fun add(seg: Segment) {
        _segments.value = _segments.value + seg
    }

    @Synchronized
    fun update(id: Long, transform: (Segment) -> Segment) {
        _segments.value = _segments.value.map { if (it.id == id) transform(it) else it }
    }

    fun setStatus(transform: (RecStatus) -> RecStatus) {
        _status.update(transform)
    }

    fun snapshot(): List<Segment> = _segments.value

    fun find(id: Long): Segment? = _segments.value.firstOrNull { it.id == id }

    fun doneCount(): Int = _segments.value.count { it.state == SegState.DONE }

    fun plainText(usePolished: Boolean = true): String =
        _segments.value
            .map { if (usePolished) (it.polished ?: it.text) else it.text }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()

    fun clear() {
        _segments.value = emptyList()
        _status.value = RecStatus()
    }
    /** 用从磁盘恢复出来的片段整体替换（只在没有进行中的录音时调用）。 */
    @Synchronized
    fun replaceAll(list: List<Segment>) {
        _segments.value = list
    }
}

/**
 * 主界面录音按钮该做什么。
 *
 * 单独抽出来是为了能单元测试 —— 实测踩过的 bug 就出在这个判断上：
 * 收尾期里 recording 已经是 false，按钮若据此启动新录音，表现就是「停止不了」。
 */
object RecorderUi {

    enum class Action { START, STOP, IGNORE }

    /**
     * 停止后这段时间内的「开始」点击一律忽略。
     *
     * 为什么需要：实测收尾只要 73ms，用户要是以为没停下、再按一次，
     * 那时按钮已经合法地变回「开始听课」了 —— 于是又开了一次录音。
     * 正常人不会在停止 1.5 秒内重新开始，所以这期间的点击一定是误触。
     */
    const val RESTART_COOLDOWN_MS = 1500L

    fun actionFor(status: RecStatus): Action = when {
        status.finishing -> Action.IGNORE
        status.recording -> Action.STOP
        else -> Action.START
    }

    fun isWithinRestartCooldown(nowMs: Long, lastStopAtMs: Long): Boolean =
        lastStopAtMs > 0L && nowMs - lastStopAtMs < RESTART_COOLDOWN_MS
}
