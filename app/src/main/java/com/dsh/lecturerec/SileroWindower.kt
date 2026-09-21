package com.dsh.lecturerec

/**
 * 把任意长度的采集帧重切成 Silero 需要的窗口。纯逻辑，可脱离 ONNX 单元测试。
 *
 * Silero VAD 在 16 kHz 下的约定（见 silero-vad 的 OnnxWrapper）：
 *  - 模型每次接收 **576** 个样本 = 前一块末尾 64 个样本（上下文）+ 本块新增 512 个样本；
 *  - 也就是「步进 512、重叠 64」。上下文必须由调用方维护，模型自己不留。
 *
 * 我们采集的是 320 样本（20 ms）一帧，两者不是整数倍关系，所以必须缓冲。
 */
class SileroWindower(
    private val shift: Int = 512,
    private val contextSize: Int = 64
) {
    private val block = ShortArray(shift)
    private var fill = 0
    private var context = ShortArray(contextSize)

    /** 模型窗口长度（576 @16k）。 */
    val windowSize: Int get() = contextSize + shift

    /**
     * 送入一帧，返回本次凑出的所有窗口。
     *
     * 之所以返回列表而不是单个窗口：如果某一帧比 [shift] 还长，一次 push 可能凑出多个窗口，
     * 早期版本在这里直接 return 会把剩余样本静默丢掉。
     * 常见情况（无窗口）返回 `emptyList()`，不产生额外对象。
     */
    fun push(frame: ShortArray): List<FloatArray> {
        val out = ArrayList<FloatArray>(1)
        var src = 0
        while (src < frame.size) {
            val n = minOf(shift - fill, frame.size - src)
            System.arraycopy(frame, src, block, fill, n)
            fill += n
            src += n

            if (fill == shift) {
                out.add(buildWindow())
                fill = 0
            }
        }
        return out
    }

    /** 上下文 + 本块，归一化到 [-1,1)。 */
    private fun buildWindow(): FloatArray {
        val out = FloatArray(windowSize)
        for (i in 0 until contextSize) out[i] = context[i] / 32768f
        for (i in 0 until shift) out[contextSize + i] = block[i] / 32768f

        // 下一块的上下文 = 本块最后 contextSize 个样本
        System.arraycopy(block, shift - contextSize, context, 0, contextSize)
        return out
    }

    fun reset() {
        fill = 0
        context = ShortArray(contextSize)
    }
}
