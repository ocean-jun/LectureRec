package com.dsh.lecturerec

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/** 一段待上传的语音（VAD 切出来的一句话）。 */
class Utterance(
    val id: Long,
    val pcm: ByteArray,
    val startMs: Long,
    val endMs: Long,
    private val sampleRate: Int = 16000
) {
    val durationMs: Long get() = Pcm.durationMs(pcm.size, sampleRate)
}

/**
 * 把队列里**时间上连续**的若干句攒成一批，一次请求发出去。
 *
 * 为什么必须攒：实测硅基流动免费档每个请求固定要等 30 秒上下，跟音频多长基本无关。
 * 一个 2 秒的片段也要等 30 秒 —— 等于 15 倍浪费。攒到 15 秒再发，
 * 同样的等待能搬运 7 倍多的音频；而且拼起来的是连续语音，ASR 的上下文更完整。
 *
 * 收工条件（任意一个满足就发车）：
 *  1. 攒够 [targetMs] 音频；
 *  2. 句子数达到 [maxUtterances]；
 *  3. **说话人停顿超过 [idleGapMs]** —— 实测里人说话的间隔是 1~3 秒，
 *     窗口太短会把本该合并的句子拆成一批批小请求，攒批就白做了；
 *  4. 从第一句起等满 [maxWaitMs]，保证延迟有上界。
 *
 * 实现细节：先用 `receiveCatching` **阻塞**等第一句，保证永远不会发出空批；
 * 补齐用 `tryReceive` 轮询，而不是 `withTimeout` 取消一个进行中的 `receive` ——
 * 取消 receive 有丢元素的语义风险，而 20ms 轮询的开销完全可以忽略。
 */
class BatchFormer(
    private val targetMs: Long = 15_000L,
    private val maxUtterances: Int = 20,
    private val idleGapMs: Long = 3_000L,
    private val maxWaitMs: Long = 20_000L,
    private val pollMs: Long = 20L
) {

    /** 取出一批（可能只有一句）。队列已关闭且为空时返回空列表。 */
    suspend fun take(queue: Channel<Utterance>): List<Utterance> {
        val batch = ArrayList<Utterance>(8)

        val first = queue.receiveCatching().getOrNull() ?: return emptyList()
        batch.add(first)

        var accMs = first.durationMs
        val startAt = System.currentTimeMillis()
        var lastAddAt = startAt

        while (batch.size < maxUtterances && accMs < targetMs) {
            val now = System.currentTimeMillis()
            if (now - lastAddAt >= idleGapMs) break   // 说话人停了
            if (now - startAt >= maxWaitMs) break     // 延迟上界

            val result = queue.tryReceive()
            val next = result.getOrNull()
            if (next == null) {
                if (result.isClosed) break
                delay(pollMs)
                continue
            }

            batch.add(next)
            accMs += next.durationMs
            lastAddAt = System.currentTimeMillis()
        }
        return batch
    }
}
