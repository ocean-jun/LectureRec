package com.dsh.lecturerec

import android.content.Context
import android.content.SharedPreferences

data class Provider(val label: String, val baseUrl: String, val model: String)

object Presets {
    /**
     * 全部走 OpenAI 兼容的 /v1/audio/transcriptions 接口，换厂商只需改地址与模型名。
     *
     * **顺序有讲究**：第一项是默认值。实测（2026-09-21 晚，同一部手机同一网络）：
     *  - `TeleAI/TeleSpeechASR`：16 次请求全部 0.4~1.4 秒返回
     *  - `FunAudioLLM/SenseVoiceSmall`：平均约 30 秒，最慢 98 秒，免费档严重排队
     * 两者都免费。TeleSpeech 快 30 倍以上，代价是句末标点较少、个别词偏 —— 所以放在默认位。
     */
    val ASR = listOf(
        Provider("硅基流动 · TeleSpeechASR（免费 · 快）", "https://api.siliconflow.cn/v1", "TeleAI/TeleSpeechASR"),
        Provider("硅基流动 · SenseVoiceSmall（免费 · 标点更好但免费档很慢）", "https://api.siliconflow.cn/v1", "FunAudioLLM/SenseVoiceSmall"),
        Provider("Groq · whisper-large-v3-turbo（约 $0.04/小时）", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo"),
        Provider("阿里云百炼 · qwen3-asr-flash（约 ¥0.79/小时）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-asr-flash"),
        Provider("OpenAI · whisper-1", "https://api.openai.com/v1", "whisper-1"),
        Provider("自定义（自己填地址和模型）", "", "")
    )

    val POLISH = listOf(
        Provider("DeepSeek · deepseek-flash", "https://api.deepseek.com/v1", "deepseek-flash"),
        Provider("硅基流动 · Qwen2.5-7B-Instruct", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
        Provider("自定义（自己填地址和模型）", "", "")
    )
}

class AppSettings(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("lecture_rec", Context.MODE_PRIVATE)

    private fun getStr(key: String, def: String): String = sp.getString(key, def) ?: def
    private fun putStr(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }

    var asrBaseUrl: String
        get() = getStr(K_ASR_BASE, Presets.ASR[0].baseUrl)
        set(v) = putStr(K_ASR_BASE, v)

    var asrApiKey: String
        get() = getStr(K_ASR_KEY, "")
        set(v) = putStr(K_ASR_KEY, v)

    var asrModel: String
        get() = getStr(K_ASR_MODEL, Presets.ASR[0].model)
        set(v) = putStr(K_ASR_MODEL, v)

    var language: String
        get() = getStr(K_LANG, "zh")
        set(v) = putStr(K_LANG, v)

    var hotwords: String
        get() = getStr(K_HOTWORDS, "")
        set(v) = putStr(K_HOTWORDS, v)

    var courseTitle: String
        get() = getStr(K_TITLE, "")
        set(v) = putStr(K_TITLE, v)

    /** VAD 引擎：auto / silero / energy，见 [DetectorFactory]。 */
    var vadEngine: String
        get() = getStr(K_VAD, DetectorFactory.AUTO)
        set(v) = putStr(K_VAD, v)

    /** 并发上传路数，见 [UploadTuning]。 */
    var uploadConcurrency: Int
        get() = sp.getInt(K_CONCURRENCY, UploadTuning.DEFAULT_CONCURRENCY)
        set(v) {
            sp.edit().putInt(K_CONCURRENCY, v).apply()
        }

    /** 时间戳显示方式，见 [Timestamps]。 */
    var timestampMode: String
        get() = getStr(K_TIMESTAMP, Timestamps.DEFAULT)
        set(v) = putStr(K_TIMESTAMP, v)

    var polishEnabled: Boolean
        get() = sp.getBoolean(K_POLISH_ON, false)
        set(v) {
            sp.edit().putBoolean(K_POLISH_ON, v).apply()
        }

    var polishBaseUrl: String
        get() = getStr(K_P_BASE, Presets.POLISH[0].baseUrl)
        set(v) = putStr(K_P_BASE, v)

    var polishApiKey: String
        get() = getStr(K_P_KEY, "")
        set(v) = putStr(K_P_KEY, v)

    var polishModel: String
        get() = getStr(K_P_MODEL, Presets.POLISH[0].model)
        set(v) = putStr(K_P_MODEL, v)

    /** 传给 ASR 的 prompt：热词表压成一行，避免超出接口对 prompt 的长度限制。 */
    fun hotwordPrompt(): String =
        hotwords.split(',', '，', '\n', '、')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("、")

    companion object {
        private const val K_ASR_BASE = "asr_base"
        private const val K_ASR_KEY = "asr_key"
        private const val K_ASR_MODEL = "asr_model"
        private const val K_LANG = "lang"
        private const val K_HOTWORDS = "hotwords"
        private const val K_TITLE = "title"
        private const val K_VAD = "vad_engine"
        private const val K_CONCURRENCY = "upload_concurrency"
        private const val K_TIMESTAMP = "timestamp_mode"
        private const val K_POLISH_ON = "polish_on"
        private const val K_P_BASE = "polish_base"
        private const val K_P_KEY = "polish_key"
        private const val K_P_MODEL = "polish_model"
    }
}
