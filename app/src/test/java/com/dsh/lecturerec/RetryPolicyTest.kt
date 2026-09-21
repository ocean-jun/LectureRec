package com.dsh.lecturerec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 重试策略。这条判断每错一次就是白白多等 3.6 秒（1.2s + 2.4s 退避），
 * 而且配置类错误重试多少次都不会成功。
 */
class RetryPolicyTest {

    @Test
    fun localConfigErrorsAreNotRetried() {
        assertFalse(RetryPolicy.isRetryable(IOException("未配置 API Key")))
        assertFalse(RetryPolicy.isRetryable(IOException("未配置接口地址")))
    }

    @Test
    fun clientErrorsAreNotRetried() {
        // Key 写错、模型名写错、地址写错 —— 都是重试无意义的
        assertFalse(RetryPolicy.isRetryable(IOException("HTTP 400 {\"error\":\"bad\"}")))
        assertFalse(RetryPolicy.isRetryable(IOException("HTTP 401 {\"message\":\"Token is invalid\"}")))
        assertFalse(RetryPolicy.isRetryable(IOException("HTTP 403 forbidden")))
        assertFalse(RetryPolicy.isRetryable(IOException("HTTP 404 model not exist")))
    }

    @Test
    fun rateLimitAndServerErrorsAreRetried() {
        assertTrue(RetryPolicy.isRetryable(IOException("HTTP 429 rate limited")))
        assertTrue(RetryPolicy.isRetryable(IOException("HTTP 500 internal error")))
        assertTrue(RetryPolicy.isRetryable(IOException("HTTP 502 bad gateway")))
        assertTrue(RetryPolicy.isRetryable(IOException("HTTP 503 unavailable")))
    }

    @Test
    fun transportErrorsAreRetried() {
        // 没有 HTTP 状态码：连接超时、DNS 失败、连接重置 —— 都值得再试
        assertTrue(RetryPolicy.isRetryable(IOException("timeout")))
        assertTrue(RetryPolicy.isRetryable(IOException("Failed to connect to api.siliconflow.cn")))
        assertTrue(RetryPolicy.isRetryable(RuntimeException("socket closed")))
    }

    @Test
    fun malformedResponsesAreRetried() {
        // 服务端偶发返回脏数据，重试一次通常就好了
        assertTrue(RetryPolicy.isRetryable(IOException("响应不是合法 JSON：<html>502</html>")))
        assertTrue(RetryPolicy.isRetryable(IOException("响应缺少 text 字段：{}")))
    }

    @Test
    fun realClientMessagesAreClassified() {
        // 直接对着两个客户端真实抛出的消息格式测，防止将来改文案时判据失效
        assertFalse(RetryPolicy.isRetryable(IOException("未配置 API Key")))
        assertFalse(
            RetryPolicy.isRetryable(
                IOException("HTTP 401 {\"error\":{\"message\":\"Token is invalid\"}}")
            )
        )
        assertTrue(
            RetryPolicy.isRetryable(
                IOException("HTTP 503 {\"error\":{\"message\":\"service busy\"}}")
            )
        )
    }
}
