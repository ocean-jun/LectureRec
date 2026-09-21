package com.dsh.lecturerec

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 上传格式写错的话整个 App 就是废的。用 MockWebServer 把线格式钉死 ——
 * 这是在没有真机、没有 API Key 的情况下唯一能验证端到端请求的手段。
 */
class AsrClientTest {

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

    private fun ok(text: String = """{"text":"识别结果"}""") {
        server.enqueue(MockResponse().setResponseCode(200).setBody(text))
    }

    private fun client(
        baseUrl: String = server.url("/v1").toString(),
        apiKey: String = "test-key",
        model: String = "FunAudioLLM/SenseVoiceSmall",
        language: String = "zh",
        prompt: String = "hotword"
    ) = AsrClient(baseUrl, apiKey, model, language, prompt)

    private fun wav100ms() = Wav.wrap(ByteArray(3200))

    /** 逐字节读取请求体，避免二进制把 ASCII 字段名匹配搞坏 */
    private fun recordedBody(): String =
        server.takeRequest().body.readByteArray().toString(Charsets.ISO_8859_1)

    @Test
    fun sendsOpenAiCompatibleMultipartRequest() = runBlocking {
        ok()
        val text = client().transcribe(wav100ms())
        assertEquals("识别结果", text)

        val req = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", req.path)
        assertEquals("Bearer test-key", req.getHeader("Authorization"))
        assertTrue(
            "content-type=${req.getHeader("Content-Type")}",
            req.getHeader("Content-Type")!!.startsWith("multipart/form-data")
        )

        val body = req.body.readByteArray().toString(Charsets.ISO_8859_1)
        assertTrue("缺 file 字段", body.contains("name=\"file\""))
        assertTrue("缺 filename", body.contains("filename=\"audio.wav\""))
        assertTrue("缺 model 字段", body.contains("name=\"model\""))
        assertTrue("缺 language 字段", body.contains("name=\"language\""))
        assertTrue("缺 prompt（热词）字段", body.contains("name=\"prompt\""))
        assertTrue("缺 response_format 字段", body.contains("name=\"response_format\""))
        assertTrue("model 值没传对", body.contains("FunAudioLLM/SenseVoiceSmall"))
        assertTrue("WAV 头没带上", body.contains("RIFF"))
    }

    @Test
    fun trailingSlashInBaseUrlDoesNotProduceDoubleSlash() = runBlocking {
        ok()
        client(baseUrl = server.url("/v1/").toString()).transcribe(wav100ms())
        assertEquals("/v1/audio/transcriptions", server.takeRequest().path)
    }

    @Test
    fun stripsSenseVoiceControlTags() = runBlocking {
        ok("""{"text":"<|zh|><|NEUTRAL|><|Speech|>这是识别结果"}""")
        assertEquals("这是识别结果", client().transcribe(wav100ms()))
    }

    @Test
    fun omitsOptionalFieldsWhenBlank() = runBlocking {
        ok()
        client(language = "", prompt = "").transcribe(wav100ms())
        val body = recordedBody()
        assertFalse("language 为空时不该发送", body.contains("name=\"language\""))
        assertFalse("prompt 为空时不该发送", body.contains("name=\"prompt\""))
    }

    @Test
    fun blankApiKeyFailsBeforeAnyRequest() = runBlocking {
        var msg = ""
        try {
            client(apiKey = "").transcribe(wav100ms())
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
            client(baseUrl = "").transcribe(wav100ms())
        } catch (e: IOException) {
            msg = e.message ?: ""
        }
        assertTrue("msg=$msg", msg.contains("接口地址"))
        assertEquals("不该真的发出请求", 0, server.requestCount)
    }

    @Test
    fun httpErrorSurfacesStatusAndServerMessage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Token is invalid"}}""")
        )
        var msg = ""
        try {
            client().transcribe(wav100ms())
        } catch (e: IOException) {
            msg = e.message ?: ""
        }
        assertTrue("msg=$msg", msg.contains("401"))
        assertTrue("msg=$msg", msg.contains("Token is invalid"))
    }

    @Test
    fun hotwordPromptReachesRequestBody() = runBlocking {
        ok()
        client(prompt = "abc,def").transcribe(wav100ms())
        assertTrue("热词没进请求体", recordedBody().contains("abc,def"))
    }

    @Test
    fun malformedJsonIsAnErrorNotGarbageTranscript() = runBlocking {
        // 关键：畸形响应绝不能被当成正文写进课堂笔记
        ok("""{"text":""")
        var msg = ""
        try {
            client().transcribe(wav100ms())
        } catch (e: IOException) {
            msg = e.message ?: ""
        }
        assertTrue("msg=$msg", msg.contains("JSON"))
    }

    @Test
    fun jsonWithoutTextFieldIsAnError() = runBlocking {
        ok("""{"result":"something else"}""")
        var msg = ""
        try {
            client().transcribe(wav100ms())
        } catch (e: IOException) {
            msg = e.message ?: ""
        }
        assertTrue("msg=$msg", msg.contains("text"))
    }

    @Test
    fun plainTextResponseIsAccepted() = runBlocking {
        // 少数兼容实现在 response_format=text 下直接返回纯文本
        ok("纯文本结果")
        assertEquals("纯文本结果", client().transcribe(wav100ms()))
    }
}
