package com.dsh.lecturerec

import java.io.IOException

/**
 * 按接口地址判断该用哪种协议，并造出对应的引擎。
 *
 * 之所以靠地址判断而不是加一个设置项：百炼的语音识别地址必然带 dashscope 或其新版工作空间域名
 * `*.maas.aliyuncs.com`，其余厂商走的都是 OpenAI 兼容的 /audio/transcriptions。
 * 这样用户在预设之间切换时不需要额外理解「协议」这个概念。
 *
 * 如果你自建了一个 DashScope 协议兼容的代理，域名里请带上 `dashscope` 或 `maas.aliyuncs.com`。
 */
object AsrEngineFactory {

    enum class Protocol { OPENAI, DASHSCOPE }

    fun protocolFor(baseUrl: String): Protocol {
        val u = baseUrl.lowercase()
        return if (u.contains("dashscope") || u.contains("maas.aliyuncs.com")) {
            Protocol.DASHSCOPE
        } else {
            Protocol.OPENAI
        }
    }

    fun create(settings: AppSettings): AsrEngine = create(
        baseUrl = settings.asrBaseUrl,
        apiKey = settings.asrApiKey,
        model = settings.asrModel,
        language = settings.language,
        prompt = settings.hotwordPrompt()
    )

    fun create(
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String,
        prompt: String
    ): AsrEngine = when (protocolFor(baseUrl)) {
        Protocol.OPENAI -> AsrClient(baseUrl, apiKey, model, language, prompt)
        Protocol.DASHSCOPE -> DashScopeAsrClient(baseUrl, apiKey, model, language, prompt)
    }

    /** 供界面提示用。 */
    fun describe(baseUrl: String): String = when (protocolFor(baseUrl)) {
        Protocol.OPENAI -> "OpenAI 兼容 /audio/transcriptions"
        Protocol.DASHSCOPE -> "阿里云百炼 /chat/completions"
    }

    /** 让 IOException 在两条路径上有一致的类型，方便调用方统一处理。 */
    fun isRetryable(t: Throwable): Boolean = t is IOException
}
