package com.dsh.lecturerec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * OpenAI 兼容的语音转写客户端：POST {base}/audio/transcriptions
 *
 * 因为只用标准字段（file / model / language / prompt / response_format），
 * 所以硅基流动、Groq、OpenAI、以及任何兼容实现都可以直接换 base_url + model 使用。
 */
class AsrClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val language: String,
    private val prompt: String
) : AsrEngine {

    override suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw IOException("未配置 API Key")
        if (baseUrl.isBlank()) throw IOException("未配置接口地址")

        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")

        if (language.isNotBlank()) form.addFormDataPart("language", language)
        // prompt 用来注入课程热词，对专有名词准确率提升明显
        if (prompt.isNotBlank()) form.addFormDataPart("prompt", prompt.take(800))

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(form.build())
            .build()

        Http.client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${body.take(200)}")

            val trimmed = body.trim()
            val raw = if (trimmed.startsWith("{")) {
                val obj = try {
                    JSONObject(trimmed)
                } catch (e: Exception) {
                    // 宁可抛错进重传队列，也不要把 {"text":...} 整串写进课堂笔记
                    throw IOException("响应不是合法 JSON：${trimmed.take(200)}")
                }
                if (!obj.has("text")) {
                    throw IOException("响应缺少 text 字段：${trimmed.take(200)}")
                }
                obj.optString("text")
            } else {
                // 少数兼容实现会在 response_format=text 下返回纯文本
                trimmed
            }

            // SenseVoice 系模型可能带上 <|zh|><|NEUTRAL|> 这类标记，清掉
            raw.replace(Regex("<\\|[^|]*\\|>"), "").trim()
        }
    }
}
