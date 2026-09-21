package com.dsh.lecturerec

import android.content.Context
import android.util.Log

/** 按设置创建 VAD 引擎，并在 Silero 不可用时静默回退。 */
object DetectorFactory {

    const val AUTO = "auto"
    const val SILERO = "silero"
    const val ENERGY = "energy"

    val LABELS = listOf("自动（优先 Silero）", "只用 Silero", "只用能量法")

    fun keyOf(index: Int): String = when (index) {
        1 -> SILERO
        2 -> ENERGY
        else -> AUTO
    }

    fun indexOf(key: String): Int = when (key) {
        SILERO -> 1
        ENERGY -> 2
        else -> 0
    }

    fun create(context: Context, preferred: String): SpeechDetector {
        if (preferred == ENERGY) {
            Log.i(TAG, "VAD 引擎: energy（用户指定）")
            return EnergyDetector()
        }

        val silero = SileroDetector.tryCreate(context)
        if (silero == null) {
            val why = if (preferred == SILERO) "用户指定了 silero 但加载失败" else "自动选择"
            Log.w(TAG, "VAD 引擎: energy（$why）")
            return EnergyDetector()
        }

        Log.i(TAG, "VAD 引擎: ${silero.name}")
        return FallbackDetector(silero, EnergyDetector())
    }

    private const val TAG = "LectureRec"
}
