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

/** AI 校对走标准 /chat/completions，同样把线格式钉死。 */
class PolishClientTest {

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

    private fun client(
        baseUrl: String = server.url("/v1").toString(),
        apiKey: String = "test-key",
        model: String = "deepseek-flash"
    ) = PolishClient(baseUrl, apiKey, model)

    @Test
    fun sendsChatCompletionAndParsesContent() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"整理后的正文"}}]}"""
            )
        )

        val out = client().polish("原始文本", "酉矩阵", "高等数学")
        assertEquals("整理后的正文", out)

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer test-key", req.getHeader("Authorization"))

        val body = req.body.readUtf8()
        assertTrue("缺 model", body.contains("deepseek-flash"))
        assertTrue("没带原始文本", body.contains("原始文本"))
        assertTrue("热词没进提示词", body.contains("酉矩阵"))
        assertTrue("课程名没进提示词", body.contains("高等数学"))
        assertTrue("没关掉流式", body.contains("\"stream\":false"))
    }

    @Test
    fun blankKeyFailsBeforeAnyRequest() = runBlocking {
        var msg = ""
        try {
            client(apiKey = "").polish("x", "", "")
        } catch (e: IOException) {
            msg = e.message ?: ""
        }
        assertTrue("msg=$msg", msg.contains("API Key"))
        assertEquals("不该真的发出请求", 0, server.requestCount)
    }

    @Test
    fun httpErrorSurfacesStatus() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limited"}}""")
        )
        var msg = ""
        try {
            client().polish("x", "", "")
        } catch (e: IOException) {
            msg = e.message ?: ""
        }
        assertTrue("msg=$msg", msg.contains("429"))
    }
}
