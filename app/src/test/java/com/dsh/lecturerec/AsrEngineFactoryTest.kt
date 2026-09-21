package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议判别。判别错了会直接打到不存在的端点上，每个片段都 404。
 */
class AsrEngineFactoryTest {

    @Test
    fun dashScopeUrlsUseDashScopeProtocol() {
        assertEquals(
            AsrEngineFactory.Protocol.DASHSCOPE,
            AsrEngineFactory.protocolFor("https://dashscope.aliyuncs.com/compatible-mode/v1")
        )
        // 新版工作空间专用域名
        assertEquals(
            AsrEngineFactory.Protocol.DASHSCOPE,
            AsrEngineFactory.protocolFor("https://ws1234.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")
        )
        // 大小写不敏感
        assertEquals(
            AsrEngineFactory.Protocol.DASHSCOPE,
            AsrEngineFactory.protocolFor("https://DashScope.aliyuncs.com/compatible-mode/v1")
        )
    }

    @Test
    fun otherUrlsUseOpenAiProtocol() {
        assertEquals(
            AsrEngineFactory.Protocol.OPENAI,
            AsrEngineFactory.protocolFor("https://api.siliconflow.cn/v1")
        )
        assertEquals(
            AsrEngineFactory.Protocol.OPENAI,
            AsrEngineFactory.protocolFor("https://api.groq.com/openai/v1")
        )
        assertEquals(
            AsrEngineFactory.Protocol.OPENAI,
            AsrEngineFactory.protocolFor("")
        )
    }

    @Test
    fun factoryPicksTheMatchingEngine() {
        val openai = AsrEngineFactory.create("https://api.siliconflow.cn/v1", "k", "m", "zh", "")
        assertTrue("硅基流动应走 multipart 引擎", openai is AsrClient)

        val dash = AsrEngineFactory.create(
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "k", "qwen3-asr-flash", "zh", ""
        )
        assertTrue("百炼应走 chat/completions 引擎", dash is DashScopeAsrClient)
    }

    @Test
    fun aliCloudPresetIsRecognisedAsDashScope() {
        // 回归护栏：预设里加百炼时若地址写错，这条会立刻失败
        val ali = Presets.ASR.first { it.label.contains("百炼") }
        assertEquals(
            AsrEngineFactory.Protocol.DASHSCOPE,
            AsrEngineFactory.protocolFor(ali.baseUrl)
        )
        assertEquals("qwen3-asr-flash", ali.model)
    }

    @Test
    fun everyPresetResolvesToAKnownProtocol() {
        for (p in Presets.ASR) {
            if (p.baseUrl.isBlank()) continue
            val engine = AsrEngineFactory.create(p.baseUrl, "k", p.model, "zh", "")
            assertTrue("预设「${p.label}」没解析出引擎", engine is AsrEngine)
        }
    }
}
