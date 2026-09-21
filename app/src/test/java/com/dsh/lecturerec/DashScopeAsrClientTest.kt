package com.dsh.lecturerec

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64

/**
 * 百炼路径的线格式。它复用 /chat/completions，音频以 base64 data URL 内联 ——
 * 这几个字段写错任何一个都会直接 400。
 */
class DashScopeAsrClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(content: String = "识别结果") {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"$content"}}]}"""
            )
        )
    }

    private fun client(
        baseUrl: String = server.url("/compatible-mode/v1").toString(),
        apiKey: String = "test-key",
        model: String = "qwen3-asr-flash",
        language: String = "zh"
    ) = DashScopeAsrClient(baseUrl, apiKey, model, language, "")

    private fun wav() = Wav.wrap(ByteArray(320))

    @Test
    fun sendsAudioAsBase64DataUrlInsideChatCompletion() = runBlocking {
        ok()
        assertEquals("识别结果", client().transcribe(wav()))

        val req = server.takeRequest()
        assertEquals("/compatible-mode/v1/chat/completions", req.path)
        assertEquals("Bearer test-key", req.getHeader("Authorization"))

        val body = req.body.readUtf8()
        assertTrue("缺 model", body.contains("qwen3-asr-flash"))
        assertTrue("缺 input_audio 类型", body.contains("\"type\":\"input_audio\""))
        assertTrue("音频不是 base64 data URL", body.contains("data:audio/wav;base64,"))
        assertTrue("没关掉流式", body.contains("\"stream\":false"))
        assertTrue("缺 asr_options", body.contains("asr_options"))
        assertTrue("语言没传", body.contains("\"language\":\"zh\""))
    }

    @Test
    fun base64PayloadDecodesBackToTheOriginalWav() = runBlocking {
        ok()
        val original = Wav.wrap(ByteArray(320) { (it % 100).toByte() })
        client().transcribe(original)

        val body = server.takeRequest().body.readUtf8()
        val b64 = body.substringAfter("data:audio/wav;base64,").substringBefore('"')
        val decoded = Base64.getDecoder().decode(b64)
        assertTrue("编码再解码应还原出同一段 WAV", original.contentEquals(decoded))
    }

    @Test
    fun stripsSenseVoiceStyleControlTags() = runBlocking {
        ok("<|zh|><|NEUTRAL|>识别结果")
        assertEquals("识别结果", client().transcribe(wav()))
    }

    @Test
    fun blankApiKeyFailsBeforeAnyRequest() = runBlocking {
        var msg = ""
        try {
            client(apiKey = "").transcribe(wav())
        } catch (e: IOException) {
            msg = e.message ?: ""
        }
        assertTrue("msg=$msg", msg.contains("API Key"))
        assertEquals("不该真的发出请求", 0, server.requestCount)
    }

    @Test
    fun blankBaseUrlFailsBeforeAnyRequest() = runBlocking {
        var msg = ""
        try {
            client(baseUrl = "").transcribe(wav())
        } catch (e: IOException) {
            msg = e.message ?: ""
        }
        assertTrue("msg=$msg", msg.contains("接口地址"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun httpErrorSurfacesStatus() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"Model not exist"}}""")
        )
        var msg = ""
        try {
            client().transcribe(wav())
        } catch (e: IOException) {
            msg = e.message ?: ""
        }
        assertTrue("msg=$msg", msg.contains("400"))
        assertTrue("msg=$msg", msg.contains("Model not exist"))
    }

    @Test
    fun responseWithoutChoicesIsAnErrorNotGarbage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"output":"x"}"""))
        var msg = ""
        try {
            client().transcribe(wav())
        } catch (e: IOException) {
            msg = e.message ?: ""
        }
        assertTrue("msg=$msg", msg.contains("choices"))
    }

    @Test
    fun trailingSlashInBaseUrlDoesNotProduceDoubleSlash() = runBlocking {
        ok()
        client(baseUrl = server.url("/compatible-mode/v1/").toString()).transcribe(wav())
        assertEquals("/compatible-mode/v1/chat/completions", server.takeRequest().path)
    }
}
