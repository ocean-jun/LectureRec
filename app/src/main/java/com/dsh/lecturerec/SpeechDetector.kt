package com.dsh.lecturerec

/**
 * 逐帧判断「这一帧是不是人声」。
 *
 * 抽成接口有两个目的：
 *  1. 让切句状态机（VadSegmenter）与具体检测算法解耦，可以脱离 ONNX 做确定性单元测试；
 *  2. Silero 模型加载失败时能无缝回退到能量法，不至于整个 App 不可用。
 */
interface SpeechDetector {

    /** 该帧为人声的概率 0..1；返回 null 表示当前无法判定（按静音处理）。 */
    fun probability(frame: ShortArray): Double?

    /** 进入语音的阈值。 */
    val startThreshold: Double

    /** 维持语音的阈值。低于进入阈值，形成迟滞，避免句子被抖动切断。 */
    val keepThreshold: Double

    /** 供日志与界面显示的引擎名。 */
    val name: String

    fun reset() {}

    fun close() {}
}
