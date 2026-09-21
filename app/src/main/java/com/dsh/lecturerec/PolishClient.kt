package com.dsh.lecturerec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * ASR 之后的 LLM 校对：修同音字、补标点、分段。
 *
 * 提示词刻意写成"只纠错排版、不摘要不扩写"，避免模型把课堂内容改没了。
 */
class PolishClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) {

    suspend fun polish(transcript: String, hotwords: String, courseTitle: String): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw IOException("未配置校对 API Key")

            val system = buildString {
                append("你是课堂笔记整理助手。下面是一节课的语音识别原始文本，可能存在同音字错误、缺少标点、没有分段。\n")
                append("请严格按以下要求处理：\n")
                append("1. 修正明显的识别错误，尤其是专业术语；不确定的地方保持原样，绝对不要臆造内容。\n")
                append("2. 补全标点，按语义分成自然段。\n")
                append("3. 不要摘要、不要删减、不要扩写、不要加标题，只做纠错与排版。\n")
                append("4. 直接输出整理后的正文，不要任何解释、前言或后缀。\n")
                if (courseTitle.isNotBlank()) append("课程名称：$courseTitle\n")
                if (hotwords.isNotBlank()) {
                    append("课程热词（遇到同音字时优先选用这些词）：$hotwords\n")
                }
            }

            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", transcript.take(60000)))

            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("stream", false)

            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Http.client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${body.take(200)}")
                JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content")
                    .trim()
            }
        }
}
