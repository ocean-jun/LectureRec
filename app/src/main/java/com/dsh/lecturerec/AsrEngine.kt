package com.dsh.lecturerec

/**
 * 语音转写引擎。不同厂商的接口形态不一样，但对我们来说都只是「一段 WAV 进去、一段文字出来」。
 *
 * 目前两种协议：
 *  - [AsrClient]：OpenAI 兼容的 `POST /v1/audio/transcriptions`（multipart 上传文件）
 *    硅基流动、Groq、OpenAI 以及大多数自建 Whisper 都走这条。
 *  - [DashScopeAsrClient]：阿里云百炼的 OpenAI 兼容模式 `POST /compatible-mode/v1/chat/completions`，
 *    音频以 base64 data URL 塞进 message —— 它**不是** /audio/transcriptions。
 */
interface AsrEngine {
    /** 转写一段 WAV。失败抛 IOException。 */
    suspend fun transcribe(wav: ByteArray): String
}
