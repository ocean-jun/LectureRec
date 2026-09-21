# LectureRec

安卓端课堂录音转写 App。**前台服务常驻后台、息屏继续录**，边说边转写，一键复制，逐句落盘成 Markdown。

为课堂场景专门做的，不是通用语音输入法。

---

## 它解决什么问题

市面上要么是录音转文字 App（要会员、后台存活差、导出受限），要么是通用语音输入法（不适合长时间录制）。
这个是自己用的版本：**没有订阅、没有账号、没有埋点**，配置全在本地，用谁家的转写服务由你自己决定。

## 功能

| | |
|---|---|
| **常驻后台** | 前台服务 + `FOREGROUND_SERVICE_TYPE_MICROPHONE` + WakeLock，实测息屏后继续录 |
| **实时转写** | VAD 切句 → 攒批 → 并发上传，边说边出字 |
| **一键复制** | 复制全文（纯文本，不带时间戳）／长按复制单句 |
| **自动落盘** | 每句写进 Markdown，进程被杀也不丢；支持崩溃后恢复 |
| **AI 校对** | 可选：把整篇交给 LLM 修同音字、补标点、分段 |
| **课程热词** | 注入 ASR 的 prompt，专有名词错误率明显下降 |
| **失败留底** | 上传失败的音频自动存盘，联网后可在菜单里一键重传 |
| **时间戳可选** | 每分钟标一次／每段都标／不标 |

## 两种转写协议，自动判别

按 Base URL 选择，切换厂商只需在设置页改一行：

| 地址特征 | 协议 | 厂商 |
|---|---|---|
| 含 `dashscope` 或 `maas.aliyuncs.com` | `POST /chat/completions`，音频以 base64 data URL 内联 | 阿里云百炼 |
| 其余 | `POST /audio/transcriptions`，multipart 上传 | 硅基流动 / Groq / OpenAI / 自建 Whisper |

内置预设：硅基流动（TeleSpeechASR / SenseVoiceSmall，均免费）、Groq、阿里云百炼、OpenAI、自定义。

## 技术要点

- **VAD**：Silero VAD（ONNX Runtime）。它是唯一能区分「人声」和「响亮但不是人声的噪声」的方案 ——
  能量法会把翻书、椅子、空调当成语音，而这些片段发给 ASR 常常换来幻觉文本。
  加载失败或推理连续失败会**自动降级**到能量法，绝不因为模型问题让整堂课录不下来。
- **攒批 + 并发**：把「每请求固定开销」摊到更多音频上。参数按实测延迟调，不是拍脑袋。
- **顺序写文件**：并发上传后完成顺序是乱的，但文件必须按录音顺序写，否则导出的时间戳会来回跳。
- **息屏不丢字**：先开麦克风、再加载模型，避免模型加载的 200ms 里丢掉第一个字。

## 构建

```bash
# 环境：JDK 17 / Gradle 8.0 / Android SDK 34
./gradlew testDebugUnitTest assembleRelease
```

产物在 `app/build/outputs/apk/release/`。

> `app/src/main/assets/silero_vad.onnx` 来自 [snakers4/silero-vad](https://github.com/snakers4/silero-vad)（MIT）。
> 若要自己重新获取：`https://cdn.jsdelivr.net/gh/snakers4/silero-vad@master/src/silero_vad/data/silero_vad.onnx`

## 测试

**113 个单元测试**，覆盖 HTTP 线格式（MockWebServer）、VAD 状态机、攒批边界、
时间戳规则、文件按序写入（回归）、失败命名唯一性、按钮状态机（回归）等。

```bash
./gradlew testDebugUnitTest
```

## 使用说明

详见 [`构建与使用说明.md`](构建与使用说明.md) —— 含首次配置、各厂商保后台设置、费用估算、
以及每一轮真机验证的实测数据与踩过的坑。

## 权限

```
RECORD_AUDIO, INTERNET, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, WAKE_LOCK
```

外加 `allowBackup=false`（禁止云备份带走 API Key）与 `usesCleartextTraffic=false`（禁止明文外发）。

## 证书

未定。仅供个人使用。
