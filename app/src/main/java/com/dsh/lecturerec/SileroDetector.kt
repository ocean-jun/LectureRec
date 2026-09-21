package com.dsh.lecturerec

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD（ONNX Runtime）。
 *
 * 相比能量法，它能真正区分「人声」和「响亮但不是人声的噪声」—— 翻书、挪椅子、空调气流
 * 在能量法下都会触发，而发给 ASR 后往往会换来一段幻觉文本。教室里这类噪声非常多。
 *
 * 模型签名在不同版本间不一样，这里在运行时自适应，不靠猜：
 *  - **v5**：inputs `input` / `state`[2,1,128] / `sr`，outputs `output` / `stateN`
 *  - **v4**：inputs `input` / `sr` / `h`[2,1,64] / `c`[2,1,64]，outputs `output` / `hn` / `cn`
 *
 * 16 kHz 下模型窗口是「上一块末尾 64 样本 + 本块 512 样本」= 576，由 [SileroWindower] 负责重切。
 *
 * 任何一步失败都只返回 null，由 [FallbackDetector] 决定是否降级，绝不抛异常打断录音。
 */
class SileroDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val isV5: Boolean,
    private val hidden: Int
) : SpeechDetector {

    override val startThreshold = 0.5
    override val keepThreshold = 0.35

    override val name: String = if (isV5) "silero-v5" else "silero-v4"

    private val windower = SileroWindower()
    private var lastProb = 0.0

    private val stateA = FloatArray(2 * hidden)
    private val stateB: FloatArray? = if (isV5) null else FloatArray(2 * hidden)

    /** 一个模型窗口覆盖 32 ms，中间的采集帧沿用上一次的概率。 */
    override fun probability(frame: ShortArray): Double? {
        val windows = windower.push(frame)
        if (windows.isEmpty()) return lastProb
        return try {
            for (w in windows) lastProb = infer(w)
            lastProb
        } catch (t: Throwable) {
            Log.e(TAG, "silero 推理失败", t)
            null
        }
    }

    override fun reset() {
        windower.reset()
        stateA.fill(0f)
        stateB?.fill(0f)
        lastProb = 0.0
    }

    override fun close() {
        runCatching { session.close() }
        // OrtEnvironment 是进程级单例，这里不关
    }

    private fun infer(window: FloatArray): Double {
        val audio = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(window), longArrayOf(1, window.size.toLong())
        )
        val sr = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf(1)
        )
        val sA = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(stateA), longArrayOf(2, 1, hidden.toLong())
        )
        val sB = stateB?.let {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(it), longArrayOf(2, 1, hidden.toLong()))
        }

        try {
            val inputs = HashMap<String, OnnxTensor>()
            inputs["input"] = audio
            inputs["sr"] = sr
            if (isV5) {
                inputs["state"] = sA
            } else {
                inputs["h"] = sA
                inputs["c"] = sB!!
            }

            session.run(inputs).use { out ->
                @Suppress("UNCHECKED_CAST")
                val prob = (out.get(0).value as Array<FloatArray>)[0][0].toDouble()
                copyState(out.get(1), stateA)
                if (!isV5) copyState(out.get(2), stateB!!)
                return prob
            }
        } finally {
            runCatching { audio.close() }
            runCatching { sr.close() }
            runCatching { sA.close() }
            runCatching { sB?.close() }
        }
    }

    /** 把 [2,1,hidden] 的输出张量摊回一维状态缓冲。 */
    private fun copyState(value: OnnxValue, dst: FloatArray) {
        @Suppress("UNCHECKED_CAST")
        val arr = (value as OnnxTensor).value as Array<Array<FloatArray>>
        var k = 0
        for (i in arr.indices) {
            for (j in arr[i].indices) {
                for (m in arr[i][j].indices) {
                    if (k < dst.size) dst[k++] = arr[i][j][m]
                }
            }
        }
    }

    companion object {
        private const val TAG = "LectureRec"
        private const val MODEL_ASSET = "silero_vad.onnx"
        private const val SAMPLE_RATE = 16000L

        /** 加载模型。任何一步失败返回 null，由调用方回退到能量法。 */
        fun tryCreate(context: Context): SileroDetector? = try {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)      // 单路音频用不着多线程，省电
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(bytes, opts)
            val names = session.inputNames
            Log.i(TAG, "silero 模型已载入，输入张量: $names")

            val isV5 = names.contains("state")
            val isV4 = names.contains("h") && names.contains("c")

            if (!isV5 && !isV4) {
                Log.e(TAG, "无法识别的 silero 模型输入名: $names")
                runCatching { session.close() }
                null
            } else {
                // 把模型对音频长度的约束打出来，方便在真机上核对窗口假设
                runCatching {
                    val info = session.inputInfo["input"]?.info
                    if (info is TensorInfo) {
                        Log.i(TAG, "silero 音频输入形状: ${info.shape.toList()}")
                    }
                }
                Log.i(TAG, "silero 变体: ${if (isV5) "v5" else "v4"}")
                SileroDetector(env, session, isV5, if (isV5) 128 else 64)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "silero 加载失败，将回退到能量法", t)
            null
        }
    }
}
