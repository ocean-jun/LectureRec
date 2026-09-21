package com.dsh.lecturerec

import android.util.Log

/**
 * 主检测器连续失败时自动切换到兜底检测器。
 *
 * 为什么需要它：如果 Silero 在某个机型上跑不动（NNAPI/CPU 差异、内存不足），
 * 我们绝不能让整堂课一句都录不下来。连续 10 次拿不到概率就永久改用能量法。
 *
 * 阈值是**按当前生效的检测器**动态给出的，否则切换后阈值不匹配会导致切句全乱。
 */
class FallbackDetector(
    private val primary: SpeechDetector,
    private val fallback: SpeechDetector,
    private val maxConsecutiveFailures: Int = 10
) : SpeechDetector {

    private var active: SpeechDetector = primary
    private var failures = 0

    override val startThreshold: Double get() = active.startThreshold
    override val keepThreshold: Double get() = active.keepThreshold
    override val name: String get() = active.name

    /** 是否已经降级。 */
    val degraded: Boolean get() = active === fallback

    override fun probability(frame: ShortArray): Double? {
        val p = active.probability(frame)

        if (p == null) {
            if (active === primary && ++failures >= maxConsecutiveFailures) {
                Log.w(TAG, "主检测器连续失败 $failures 次，降级到 ${fallback.name}")
                runCatching { primary.close() }
                active = fallback
            }
        } else {
            failures = 0
        }
        return p
    }

    override fun reset() {
        active.reset()
        fallback.reset()
    }

    override fun close() {
        runCatching { primary.close() }
        runCatching { fallback.close() }
    }

    private companion object {
        const val TAG = "LectureRec"
    }
}
