package com.dsh.lecturerec

/**
 * 哪些错误值得重试。
 *
 * 这个判断在课堂场景下很值钱：一次重试要付出 1.2s + 2.4s 的退避等待，
 * 而配置类错误（Key 没填、模型名写错）重试多少次都不会成功，纯粹白等。
 * 实测里"每段固定多花 3.6 秒"就是这么来的。
 */
object RetryPolicy {

    private val HTTP_CODE = Regex("HTTP (\\d{3})")

    fun isRetryable(t: Throwable): Boolean {
        val msg = t.message.orEmpty()

        // 本地就能判定是配置问题，请求根本没发出去
        if (msg.contains("未配置")) return false

        val code = HTTP_CODE.find(msg)?.groupValues?.get(1)?.toIntOrNull()
            ?: return true   // 没有状态码：连接失败、超时、DNS —— 值得重试

        // 429 是限流，退避后可能就好了；5xx 是服务端临时问题
        // 其余 4xx（400/401/403/404）是请求本身有问题，重试无意义
        return code == 429 || code >= 500
    }
}

/** 上传策略的可调参数。 */
object UploadTuning {

    /**
     * 并发上传路数。
     *
     * 实测瓶颈是"每个请求的固定开销"（免费档约 30 秒，与音频长短基本无关），
     * 所以并发数几乎直接等于吞吐倍数。调太高可能触发服务端限流，从 3 开始试。
     */
    val CONCURRENCY_OPTIONS = listOf(1, 2, 3, 5, 8)
    val CONCURRENCY_LABELS = CONCURRENCY_OPTIONS.map { "$it 路并发" }
    const val DEFAULT_CONCURRENCY = 5

    fun concurrencyIndexOf(value: Int): Int {
        val i = CONCURRENCY_OPTIONS.indexOf(value)
        return if (i >= 0) i else CONCURRENCY_OPTIONS.indexOf(DEFAULT_CONCURRENCY)
    }
}
