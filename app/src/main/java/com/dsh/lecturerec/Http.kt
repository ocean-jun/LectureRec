package com.dsh.lecturerec

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 全局共用一个连接池，避免反复建连；课堂场景是长时低频请求。 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // OkHttp 默认每个主机只允许 5 个并发请求。并发上传时这是个隐形天花板：
        // 设置里选 8 路，多出来的会在 OkHttp 内部排队，实际并不会真的跑 8 路。
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            }
        )
        .build()
}
