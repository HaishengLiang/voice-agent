# Agent 配置、TTS 提示音与唤醒语音补灌

本文记录 2026-11-23 那次需求调整的实现与协议依据。下次改设置页、MOSS 会话参数、提示音或环形缓存前先读它，不用重新摸协议。

## 需求落地概览

设置页拆成三个 Tab（基本设置 / Agent / 提示音），实现文件：

| 文件 | 职责 |
|---|---|
| `SettingsActivity.kt` | 三分区 Tab、demo/SDK 联动禁用、Agent 配置保存、TTS 生成/试听串接 |
| `res/layout/activity_settings.xml` | 三个独立 ScrollView 页面；Tab 用三个 TextView + drawable 切换，未引入新依赖 |
| `Config.kt` | 新增 persona / instructions / dialogueVoice / ttsVoice / 两段提示音文本，走 SharedPreferences |
| `MossRealtimeClient.kt` | `sendSessionConfig` / `sendChangeVoice` / `sendBufferedFrames` |
| `WakeService.kt` | 会话就绪后下发配置；开麦时补灌环形缓存 |
| `AudioCapture.kt` / `AudioRingBuffer.kt` | 采集线程每帧写 5 秒环形缓存，帧序号透传 |
| `TtsClient.kt` | `POST /v1/audio/speech`，wav 存到 app 内部 filesDir |
| `GreetingPlayer.kt` | 优先播本地 TTS 文件，回退自带 assets；从 RIFF 头读采样率 |

## MOSS 协议事实（来自官方文档，已核对源码）

文档入口：<https://platform.moss.cn/docs/getting-started/quickstart>，接口参考 `/docs/reference/moss-live`、`/docs/reference/speech`、`/docs/reference/voices-list`。

### 实时会话配置（WebSocket `/v1/realtime`）

- **没有 `system_prompt` 字段**。系统设定拆成两个：
  - 人设：`action=session.update` → `session.persona.prompt`（最多 8000 Unicode 字符），可带 `session.persona.name` / `aliases`。
  - 会话指令：`session.update` → `session.instructions`（业务规则，与人设冲突时优先）。
  - 二者都放在一个 `session` 对象里下发：`{"action":"session.update","session":{"persona":{"prompt":"..."},"instructions":"..."}}`。
- 音色：`{"action":"change_voice","voice_name":"<voice_id>"}`，收到 `session_attached` 后在同一 WebSocket 发，回执 `voice_changed`。
- `change_voice` 的字段名是 **`voice_name`，但值是音色 ID**（`/v1/audio/voices` 返回的 `data[].id`），名字有误导性。
- 开麦仍是 `{"action":"mic_state","enabled":true}`，回执 `session.updated` 对应 session.update。
- 官方 demo 端（`DEMO_ENDPOINT`）连上自动推 `session_attached`，**不能**发 `attach_session`，也不接受自定义配置——这就是 demo 模式禁用输入框的协议原因。

**开麦时序（重要）**：非 demo 模式在 `session_attached` 后先发 `session.update` / `change_voice`，等 `session.updated` / `voice_changed` 回执再开麦。用 `micOpened` 标志保证一轮只开一次（两个回执都会触发）。

### TTS（HTTP）

- `POST https://api.mosi.cn/v1/audio/speech`，`Authorization: Bearer <API Key>`。
- 必填：`model`（用 `moss-tts-2.0-flash-preview`）、`input`、`voice_id`。
- `response_format=wav` → 返回带 RIFF 头的 wav 二进制；非流式默认直接返回音频，不要传 `delivery_method`（取值有不确定风险，去掉后实测可用）。
- TTS **只接受 `voice_id`**，不接受 `voice_url` / `voice_data` / 参考音频。
- 音色列表：`GET /v1/audio/voices?limit=20`，取 `data[].id`。
- 结果存 `context.filesDir`（不外露、不打包进 apk），文件名 `tone_greeting.wav` / `tone_farewell.wav`；改文本即删旧文件，避免播过期音频。

## 5 秒环形缓存补灌（设计要点）

背景：命中唤醒词到真正开麦之间，要等 WebSocket 连接、`session_attached`、配置回执、问候音播完。这期间用户说的第一句话会被丢弃。

实现：

- `AudioRingBuffer` 容量 `5000/40 + 8 ≈ 133` 帧（16 kHz / 640 sample-per-frame = 40 ms/frame），写发生在采集线程，读在主线程，全部 `@Synchronized`。
- `AudioCapture` 每帧都 `push` 并返回全局递增 `seq`，回调里把 `seq` 带到 `WakeService`。
- `onAudioFrame` 在 `LISTENING_WAKE` 命中时记 `wakeFrameSeq = seq`；新一轮 `startSession` 据此决定 `pendingBufferedFlush`（手动唤醒 `seq=-1`，跳过）。
- 开麦后第一个未被抑制的采集帧触发 `flushBufferedFrames`：取 `ringBuffer.range(wakeFrameSeq, currentSeq-1)` 一次性灌入，然后同一帧走实时上行——顺序是「缓存帧 → 当前帧」，不倒挂、不重复。
- 只灌命中之后的帧，**不含唤醒词本身及唤醒前的旧声音**，否则服务端会把「小喵小喵」识别成用户输入。

跨线程可见性：`wakeFrameSeq` / `flushedWakeSeq` / `pendingBufferedFlush` / `micOpened` 都加了 `@Volatile`。

## demo 与 SDK 的差异（禁用规则来源）

| 项 | demo | SDK（API Key） |
|---|---|---|
| 服务路径 | 官网公开端点，免密 | `api.mosi.cn`，Bearer Key |
| 人设 / 指令 / 对话音色 | 禁用（端点不接受自定义） | 可填、下发 |
| 提示音 | 固定自带 wav（MOSS TTS 生成，见下） | 可填音色 + 文本，TTS 生成存设备 |
| 音色库浏览/试听 | 可用（公开接口，免 Key、不消耗积分） | 同样可用 |

## 音色库（浏览 + 官方试听）

提示音分区顶部新增音色库区域，demo / SDK 模式都可用：

- **数据源**：`GET https://platform.moss.cn/portal-api/audio/voices/public?limit=100[&cursor=]`。这是网页端音色库同款接口，**免 API Key**，返回 `voice_id` / `voice_name` / `gender` / `age_range` / `accent` / `tags` / `preview_audio_url` / `audio_sample_url` 等。
  - 注意与账号接口 `GET https://api.mosi.cn/v1/audio/voices` 不同：后者需要 Bearer Key 且**只有 id/name，没有试听链接**。
- **试听**：点「试听」直接下载并播放 `preview_audio_url`（约 146KB 的 wav，官方短试听片段），**不走 TTS 生成、不消耗积分**。`audio_sample_url` 是完整样本（约 322KB），未使用。
  - `preview_audio_url` 是 OSS 签名 URL，`display_assets.expires_at` 标明有效期（约 3 天）；列表刷新后 URL 会更新，**不要长期缓存**。`VoiceListClient` 过滤掉无试听链接的音色。
- **交互**：整行点击选用 → 写入 `Config.ttsVoice` 并使已生成提示音失效；「刷新列表」重拉第一页，「加载更多」按 cursor 翻页；「恢复默认」回到暖暖音色 + 两句默认文本并清除已生成文件。
- 实现文件：`VoiceListClient.kt`（分页拉取 + 解析）、`VoicePreviewPlayer.kt`（下载签名 wav + 按原始声道 AudioTrack 播放）、`item_voice.xml`（列表项）。
- **试听不降单声道**：`VoicePreviewPlayer.decodeWav` 原样保留 data 块，`Pcm` 带 `channels`，按 wav 实际声道数选 `CHANNEL_OUT_STEREO`/`CHANNEL_OUT_MONO`；时长按 `data.size / (channels*2) / sampleRate` 计算（按帧算，否则立体声会算出双倍时长）。这与「自带提示音」章节不同——提示音的 `GreetingPlayer` 仍需降混，因为那是 TTS 生成的立体声按单声道播放。两处不要混改。
- **默认音色「暖暖」是私有音色，不在公开列表里**（扫 1200 个都没找到），所以列表无法选中它——这正是需要「恢复默认」按钮的原因。

## 自带提示音（assets，已用 MOSS TTS 覆盖）

默认提示音不再是官网原始 wav，而是用 MOSS TTS 生成后转单声道、归一化打包进 assets：

| 文件 | 文本 | 音色 voice_id |
|---|---|---|
| `assets/greeting/female.wav`（联通音） | 在呢，老板 | `6c3152b1-1d4f-4e49-ade3-09f447712fec` |
| `assets/greeting/farewell.wav`（挂断音） | 好的，我退下了 | 同上 |

- 生成命令：`POST https://api.mosi.cn/v1/audio/speech`，`model=moss-tts-2.0-flash-preview`，`response_format=wav`，`language=Chinese`，Bearer Key 取自 `local.properties` 的 `moss.api.key`。
- **TTS 返回 48kHz 立体声、电平约 54%**；播放器按单声道播放，故生成后需：左右声道取平均降为单声道 + 归一化到 peak≈90%。否则变调、时长翻倍。
- 配套改动：`GreetingPlayer.COMPANION` 的 `ASSET_GAIN` 从 24.0f（旧官网音 peak≈1% 需要的增益）改为 1.0f；`parseWav` 新增 `channels` 解析与 `downmixToMono`，运行时 TTS 本地文件（立体声）也能正确播放。
- `Config` 三个默认值（ttsVoice / greetingText / farewellText）已设为上表内容，设置页显示与实际音频一致。

### 提示音提速（2026-11-23）

用户反馈提示音慢、加逗号后停顿长不灵动。结论先说：**逗号不是原因，MOSS TTS 默认语速才慢**。

- 实测：「在呢，老板」与「在呢老板」生成时长都是 1.60s，有无逗号总时长几乎一样，别在标点上找原因。
- 接口事实：`POST /v1/audio/speech` **没有 `speed` 参数**（完整参数表核对过），但支持 `expected_duration_sec`（number，>0，期望总时长）。实测传 0.8 → 得到 0.80s，传 1.0 → 1.12s，精确生效；模型据此压缩语速和停顿，比本地 DSP 变速自然。
- 还支持内联 `[pause 0.1s]`～`[pause 10.0s]` 标记（仅 flash 模型），用于局部留白。
- 落地实现：`TtsClient.estimateDurationSec` 按「汉字数 × 0.20s + `[pause Ns]` 秒数」估算目标时长，请求同时补 `language=Chinese`。默认文本去逗号为 `在呢老板` / `好的我退下了`；assets 换成 0.8s / 1.2s 短音频。
- 调参工具：`scripts` 无专门脚本，候选生成/分析脚本在 `.toolchain/tts-tune/gen.py`（调 API 生成候选 + 测时长/静音段）与 `convert.py`（降混 mono + 归一化 peak90%）。想换语速改 `TtsClient.SEC_PER_HANZI` 后重新生成即可。
- 用户试听对比后确认 0.8s 版 OK。

## 已知边界 / 后续可做

- 本次无线调试设备未在线，**未做真机通话验证**，仅 debug APK 编译通过。下次连上设备重点验证：补灌后第一句话是否被识别、TTS 生成音的采样率/音量、两个配置回执是否都触发开麦、新提示音音色与音量是否合适。
- 问候音播放期仍抑制上行；`suppressMicUntilMs` 较长时，5 秒环形缓存理论上可能覆盖最老的命中后帧，属边界情况。
- 未做音色列表下拉选择，当前是手填 voice_id；可从 `/v1/audio/voices` 拉列表做选择器。
