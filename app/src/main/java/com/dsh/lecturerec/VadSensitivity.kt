package com.dsh.lecturerec

/**
 * 收音灵敏度。用户按环境自己选 —— 手机离讲台的距离、教室的安静程度差异太大，
 * 一个写死的阈值不可能在所有场景都对。
 *
 * 实现上是**直接乘在检测器自带的阈值上**，所以对 Silero 和能量法都有效：
 *  - 小于 1 → 阈值更低 → 远距离小声也触发
 *  - 大于 1 → 更保守 → 只在明确是人声时才录
 */
object VadSensitivity {

    /** 乘数：[高灵敏度, 默认, 低灵敏度] */
    val OPTIONS = listOf(0.6, 1.0, 1.4)

    val LABELS = listOf(
        "高（手机离得远 / 说话小声）",
        "中（默认）",
        "低（教室嘈杂，减少误触发）"
    )

    /**
     * 默认取「高」。
     *
     * 理由：课堂就是这个用法 —— 手机在桌上、老师在三五米外，电平天然比贴脸说话低一截。
     * 而实测证明降阈值是安全的：有条 rms=4108 的噪声（椅子/敲击）Silero 只给 0.06 的概率，
     * 而正常说话给 0.61。阈值降到 0.30 不会放进前者，只会接住原本漏在 0.3~0.5 之间的真人声。
     */
    const val DEFAULT = 0.6

    fun valueOf(index: Int): Double = OPTIONS.getOrElse(index) { DEFAULT }

    fun indexOf(value: Double): Int {
        val i = OPTIONS.indexOfFirst { kotlin.math.abs(it - value) < 0.001 }
        return if (i >= 0) i else OPTIONS.indexOf(DEFAULT)
    }
}
