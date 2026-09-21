package com.dsh.lecturerec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/**
 * 阿里云百炼（DashScope）的 qwen3-asr-flash。
 *
 * 它的「OpenAI 兼容模式」并不提供 /audio/transcriptions，而是复用 /chat/completions：
 * 音频以 `data:audio/wav;base64,...` 的形式放进 user message 的 content 数组里。
 * 响应取值路径与普通对话一致（choices[0].message.content）。
 *
 * 注意：
 *  - 音频是 base64 内联，编码后不能超过 10 MB。我们最长 25 秒的片段约 800 KB，编码后约 1.07 MB，
 *    余量充足。
 *  - 百炼的「热词」是通过 system message 传背景文本/实体词表，但那部分文档给出的字段结构不完整，
 *    贸然发送可能让每个请求都 400。所以这条路径暂时不发热词，只在 OpenAI 兼容路径上支持。
 */
class DashScopeAsrClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val language: String,
    private val prompt: String
) : AsrEngine {

    override suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw IOException("未配置 API Key")
        if (baseUrl.isBlank()) throw IOException("未配置接口地址")

        val audio = JSONObject()
            .put("type", "input_audio")
            .put("input_audio", JSONObject().put("data", toDataUrl(wav)))

        val user = JSONObject()
            .put("role", "user")
            .put("content", JSONArray().put(audio))

        val asrOptions = JSONObject().put("enable_itn", true)
        if (language.isNotBlank()) asrOptions.put("language", language)

        val payload = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(user))
            .put("stream", false)
            .put("asr_options", asrOptions)

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        Http.client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${body.take(200)}")

            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) throw IOException("响应不是合法 JSON：${trimmed.take(200)}")

            val obj = try {
                JSONObject(trimmed)
            } catch (e: Exception) {
                throw IOException("响应不是合法 JSON：${trimmed.take(200)}")
            }

            val choices = obj.optJSONArray("choices")
                ?: throw IOException("响应缺少 choices：${trimmed.take(200)}")
            if (choices.length() == 0) throw IOException("响应 choices 为空：${trimmed.take(200)}")

            val content = choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content")
                ?: throw IOException("响应缺少 message.content：${trimmed.take(200)}")

            content.replace(Regex("<\\|[^|]*\\|>"), "").trim()
        }
    }

    private fun toDataUrl(wav: ByteArray): String =
        "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)
}
