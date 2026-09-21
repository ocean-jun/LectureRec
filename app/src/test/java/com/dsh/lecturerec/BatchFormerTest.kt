package com.dsh.lecturerec

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 攒批逻辑。
 *
 * 这是性能优化的核心：把「每请求固定开销」摊到更多音频上。
 * 攒多攒少、会不会丢句、会不会无限等，都必须确定。
 */
class BatchFormerTest {

    /** 造一句指定时长的语音（16kHz / 单声道 / 16bit = 32 字节每毫秒）。 */
    private fun utterance(id: Long, durationMs: Long): Utterance =
        Utterance(id, ByteArray((durationMs * 32).toInt()), 0L, durationMs)

    private fun former(
        targetMs: Long = 15_000,
        maxU: Int = 20,
        idleGapMs: Long = 100,
        maxWaitMs: Long = 20_000
    ) = BatchFormer(
        targetMs = targetMs,
        maxUtterances = maxU,
        idleGapMs = idleGapMs,
        maxWaitMs = maxWaitMs,
        pollMs = 5
    )

    @Test
    fun closedEmptyChannelYieldsNothing() = runBlocking {
        val q = Channel<Utterance>()
        q.close()
        assertTrue(former().take(q).isEmpty())
    }

    @Test
    fun singleUtteranceIsReturnedAlone() = runBlocking {
        val q = Channel<Utterance>(Channel.UNLIMITED)
        q.send(utterance(1, 1000))

        val batch = former(idleGapMs = 60).take(q)
        assertEquals(1, batch.size)
        assertEquals(1L, batch[0].id)
    }

    @Test
    fun accumulatesUntilTargetDuration() = runBlocking {
        val q = Channel<Utterance>(Channel.UNLIMITED)
        repeat(20) { q.send(utterance(it + 1L, 1000)) }   // 每句 1 秒

        val batch = former(targetMs = 5000, idleGapMs = 500).take(q)
        assertEquals("攒到 5 秒就该停", 5, batch.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), batch.map { it.id })
    }

    @Test
    fun respectsMaxUtteranceCount() = runBlocking {
        val q = Channel<Utterance>(Channel.UNLIMITED)
        repeat(30) { q.send(utterance(it + 1L, 500)) }    // 每句 0.5 秒，靠句数封顶

        val batch = former(targetMs = 60_000, maxU = 6, idleGapMs = 500).take(q)
        assertEquals("不能超过句数上限", 6, batch.size)
    }

    @Test
    fun closesTheBatchAfterAnIdleGapEvenIfTargetIsNotReached() = runBlocking {
        // 这正是实测里"批次太碎"的反面：目标没攒够，但人停下来了就该发车
        val q = Channel<Utterance>(Channel.UNLIMITED)
        q.send(utterance(1, 1000))
        q.send(utterance(2, 1000))

        val started = System.currentTimeMillis()
        val batch = former(targetMs = 60_000, idleGapMs = 150).take(q)
        val elapsed = System.currentTimeMillis() - started

        assertEquals(2, batch.size)
        assertTrue("停顿后应及时发车，实际 $elapsed ms", elapsed < 1000)
    }

    @Test
    fun doesNotWaitForeverWhenNothingFollows() = runBlocking {
        val q = Channel<Utterance>(Channel.UNLIMITED)
        q.send(utterance(1, 500))

        val started = System.currentTimeMillis()
        val batch = former(targetMs = 15_000, idleGapMs = 120).take(q)
        val elapsed = System.currentTimeMillis() - started

        assertEquals(1, batch.size)
        assertTrue("不该无限等下去，实际 $elapsed ms", elapsed < 1500)
    }

    @Test
    fun keepsAccumulatingWhileUtterancesKeepArriving() = runBlocking {
        // 句子之间的间隔小于空闲阈值 -> 应该继续攒，而不是一句一发
        val q = Channel<Utterance>(Channel.UNLIMITED)
        val producer = launch {
            repeat(10) {
                q.send(utterance(it + 1L, 1000))
                delay(40)
            }
        }
        val batch = former(targetMs = 5000, idleGapMs = 300, maxWaitMs = 10_000).take(q)
        producer.cancel()

        assertEquals("间隔小于空闲阈值就该继续攒", 5, batch.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), batch.map { it.id })
    }

    @Test
    fun maxWaitBoundsTheLatency() = runBlocking {
        // 句子一直来、永远攒不满目标 -> 也必须有个上界，不能无限攒
        val q = Channel<Utterance>(Channel.UNLIMITED)
        val producer = launch {
            repeat(50) {
                q.send(utterance(it + 1L, 100))
                delay(30)
            }
        }
        val started = System.currentTimeMillis()
        val batch = former(targetMs = 100_000, idleGapMs = 10_000, maxWaitMs = 200).take(q)
        val elapsed = System.currentTimeMillis() - started
        producer.cancel()

        assertTrue("不能无限攒，实际 $elapsed ms", elapsed < 1500)
        assertTrue("攒到延迟上界就该发，实际 ${batch.size} 句", batch.size in 1..20)
    }

    @Test
    fun picksUpUtterancesArrivingDuringTheIdleWindow() = runBlocking {
        val q = Channel<Utterance>(Channel.UNLIMITED)
        q.send(utterance(1, 1000))

        val producer = launch {
            delay(30)
            q.send(utterance(2, 1000))
            delay(30)
            q.send(utterance(3, 1000))
        }
        val batch = former(targetMs = 3000, idleGapMs = 400, maxWaitMs = 5000).take(q)
        producer.join()

        assertEquals("窗口内到达的句子不能丢", listOf(1L, 2L, 3L), batch.map { it.id })
    }

    @Test
    fun leavesTheRestInTheQueue() = runBlocking {
        val q = Channel<Utterance>(Channel.UNLIMITED)
        repeat(10) { q.send(utterance(it + 1L, 1000)) }

        val f = former(targetMs = 3000, idleGapMs = 500)
        val first = f.take(q)
        val second = f.take(q)

        assertEquals(listOf(1L, 2L, 3L), first.map { it.id })
        assertEquals("剩下的应留在队列里等下一次", listOf(4L, 5L, 6L), second.map { it.id })
    }
}
