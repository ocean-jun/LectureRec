package com.dsh.lecturerec

import kotlin.math.sqrt

/**
 * 能量法：帧 RMS 对比自适应噪声底。
 *
 * 零依赖、零体积，但只能区分「响」和「不响」—— 翻书、挪椅子、空调气流只要够响就会被判成语音，
 * 而这些片段发给 ASR 往往会换来一段幻觉文本。所以它只作为 Silero 不可用时的兜底。
 *
 * 阈值取 0.99 / 0.01，是为了复现改造前那套「rms > high 起判、rms > 0.55*high 维持」的行为，
 * 保证真机上已经验证过的表现不会因为这次重构而变。
 */
class EnergyDetector : SpeechDetector {

    private var noise = 300.0

    override val startThreshold = 0.99
    override val keepThreshold = 0.01
    override val name = "energy"

    override fun probability(frame: ShortArray): Double {
        val rms = rms(frame)

        val high = maxOf(noise * 3.0, 420.0)
        val low = high * 0.55
        val p = ((rms - low) / (high - low)).coerceIn(0.0, 1.0)

        // 只在判定为静音时跟踪噪声底，否则说话声会把底噪抬上去、越录越钝
        if (p < 0.5) noise = noise * 0.97 + rms * 0.03

        return p
    }

    override fun reset() {
        noise = 300.0
    }

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) {
            val v = s.toDouble()
            sum += v * v
        }
        return sqrt(sum / frame.size)
    }
}
