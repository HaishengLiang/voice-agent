# 小喵语音智能（voice-agent）

一个 Android 端「常驻唤醒 + 全双工语音对话」的完整实现：设备息屏待机时用**离线 KWS** 监听唤醒词，命中后接入 **MOSS-Live Realtime** WebSocket 服务，进入可随时打断的实时语音对话，说到道别词或空闲超时自动回到监听。

全程不需要云端唤醒服务，唤醒在本地完成，只有对话期间才联网。

## 功能特性

| 能力 | 实现 |
|---|---|
| 离线语音唤醒 | sherpa-onnx KeywordSpotter（zipformer2 transducer，3.3M 参数，ppinyin 音素），模型随 APK 内置 |
| 常驻监听 | 前台服务（`microphone` 类型）+ `PARTIAL_WAKE_LOCK`，息屏可继续工作 |
| 开机自启 | `BootReceiver` 监听 `BOOT_COMPLETED` 拉起服务 |
| 全双工对话 | WebSocket 双向音频流：上行 16 kHz mono PCM16，下行 48 kHz mono PCM16 |
| 回声消除 | 切到 `MODE_IN_COMMUNICATION` 让系统 AEC 生效，避免外放被麦克风回采 |
| 实时打断 | 用户开口时上报，接收 `response.interrupted` 立即停播 |
| 流式转写 | 上行/下行 transcript 均按 `updated`（流式）+ `done`（定稿）累积成多轮对话 |
| 本地问候音 | 唤醒瞬间播放 `assets/greeting/female.wav`，播放期间抑制上行，避免自问自答 |
| 道别即结束 | 命中「再见」「退下」「不聊了」等词后主动收尾，播放挂断音、回到唤醒监听 |
| 播放设备可选 | 扬声器外放 / 耳机听筒（有线、USB、蓝牙 SCO），运行时切换即时生效 |
| 服务路径可选 | 「Demo 路径」直连官网公开服务（免密钥）/「SDK 路径」连 MOSS-Live（需保存 API Key） |
| 双页面 UI | 主页只展示状态（巨型 logo + 红黄绿状态灯 + 三步指南），配置与启停集中在右上角齿轮进入的设置页 |
| 打开即监听 | 启动应用自动开始唤醒监听（可在设置页关闭），无需手动点按钮 |

## 工作流程

```
LISTENING_WAKE  →  SESSION_CONNECTING  →  SESSION_ACTIVE  →  LISTENING_WAKE
麦克风→KWS 逐帧检测   播放问候音+建立连接     全双工收发+可打断    播挂断音+释放通道
```

| 阶段 | 做什么 | 关键点 |
|---|---|---|
| 息屏待机 `LISTENING_WAKE` | 麦克风逐帧喂给 KWS | 命中「小喵小喵」即唤醒 |
| 唤醒 `SESSION_CONNECTING` | 播放本地问候音、建立 WebSocket | 播放期间抑制上行，避免把问候音当成用户输入 |
| 对话 `SESSION_ACTIVE` | PCM 上行 + 下行音频播放 | 可随时打断，多轮对话累积展示 |
| 结束 | 播放挂断音、释放音频通道 | 回到唤醒监听，服务不退出 |

状态机定义在 `VoiceState.Phase`：`IDLE → LISTENING_WAKE → SESSION_CONNECTING → SESSION_ACTIVE → LISTENING_WAKE`。

结束条件有三种：说到道别词、空闲超时（默认 30 s，界面可改）、手动点「结束对话」。

## 目录结构

```
voice-agent/
├── xiaomiao-voice-agent/          # Android 工程（Kotlin，AGP + Gradle 8.11.1）
│   └── app/src/main/
│       ├── java/com/example/xiaomiao/
│       │   ├── MainActivity.kt        # 主页：巨型 logo + 状态灯 + 三步指南 + 打开即监听
│       │   ├── SettingsActivity.kt    # 设置页：全部配置项 + 启停 + KWS 自检
│       │   ├── WakeService.kt         # 核心：前台服务、状态机、音频路由、协议收发
│       │   ├── WakeDetector.kt        # sherpa-onnx KWS 封装 + 自检
│       │   ├── MossRealtimeClient.kt  # WebSocket 客户端（OkHttp）
│       │   ├── AudioCapture.kt        # 麦克风采集（16 kHz）
│       │   ├── AudioPlayer.kt         # 下行音频播放（48 kHz）
│       │   ├── GreetingPlayer.kt      # 本地问候音 / 挂断音
│       │   ├── VoiceState.kt          # 全局状态 + 对话转录累积
│       │   ├── Config.kt              # SharedPreferences 配置
│       │   └── BootReceiver.kt        # 开机自启
│       ├── assets/kws/                # 唤醒模型 + 音素表 + 关键词（大部分不入库）
│       └── assets/greeting/           # 问候音、挂断音、自检样本（入库）
├── moss-live-demo/                # 浏览器直连 demo，用于摸协议、验证音频参数
├── scripts/                       # 依赖还原、构建、下发、唤醒词生成
├── docs/adb-wireless-setup.md     # 无线调试环境与踩坑记录
└── .toolchain/                    # JDK / Android SDK / Gradle / Python venv（不入库）
```

## 环境准备

JDK、Android SDK、Gradle、Python venv、KWS 模型**全部不入库**，换机器后一条命令还原：

```bash
bash scripts/fetch-deps.sh            # 全部（默认）
bash scripts/fetch-deps.sh toolchain  # 仅 JDK17 + Android SDK + Gradle
bash scripts/fetch-deps.sh python     # 仅 Python venv + sherpa-onnx
bash scripts/fetch-deps.sh model      # 仅 KWS 模型（sha256 逐文件校验，已就绪自动跳过）
```

脚本默认走 `http://127.0.0.1:7890` 代理，失败自动回退直连；直连请用 `DOWNLOAD_PROXY="" bash scripts/fetch-deps.sh`。

> 脚本内路径是 `I:/voice-agent` 硬编码。换目录时需同步修改 `scripts/*.sh`、`scripts/build-apk.ps1`、`scripts/adb-wireless-discover.py` 顶部的路径常量。

还原后的 `.toolchain/` 结构：

| 路径 | 内容 |
|---|---|
| `.toolchain/jdk17` | JDK 17 |
| `.toolchain/android-sdk` | Android SDK（platform-tools、build-tools 34/35、platform 35） |
| `.toolchain/gradle-8.11.1` | Gradle 发行版 |
| `.toolchain/venv` | Python venv，含 `sherpa-onnx` + `sherpa-onnx-cli` |

## 配置 API Key

Key 不写在源码、也不入库。在 `xiaomiao-voice-agent/local.properties` 里加一行，构建时注入 `BuildConfig`：

```properties
moss.api.key=sk-xxxxxxxx
```

这个文件已被 `.gitignore` 忽略；首次安装用构建注入值，之后在界面里保存过就以本机保存值为准。

不想申请 Key 也可以：界面选「Demo 路径」即可直接跑通（demo 端点连上后服务端自动推 `session_attached`，**不能**再发 `attach_session`，否则报 `invalid_public_request`）。

选「SDK 路径」时，API Key 必须在界面上点「保存 Key」显式保存后才会放行启动——只填不保存会被拦下并提示，避免用户以为在用自己的 Key、实际跑的是构建内置密钥。

## 构建

```bash
bash scripts/build-apk.sh
```

产物：`xiaomiao-voice-agent/app/build/outputs/apk/debug/app-debug.apk`（约 65 MB，含模型与双 ABI）。

必须走这个脚本。它内部调用 `build-apk.ps1`，负责注入 `JAVA_HOME` / `ANDROID_HOME` / 代理等环境变量并调 `gradle.bat`。直接在 Git Bash 里跑 `./gradlew` 会重新下载 Gradle 并卡住。

## 安装到设备

设备已开无线调试，用自动发现脚本下发（详见 [docs/adb-wireless-setup.md](docs/adb-wireless-setup.md)）：

```bash
./scripts/adb-wireless.sh                                  # 自动发现 + 连接 + 打印设备身份
./scripts/adb-wireless.sh install -r <path>/app-debug.apk   # 覆盖安装
./scripts/adb-wireless.sh shell "am start -n com.example.xiaomiao/.MainActivity"
./scripts/adb-wireless.sh logcat -d
```

脚本会先试 mDNS 广播，失败则扫本机 `/24` 网段的 5555 端口，连上后校验序列号。本机 adb daemon 每条命令后会重启，所以连接不跨命令保留——**别手动拼 adb 命令**，一律走脚本（内置自动重连）。

## 使用

界面分两页：**主页**只做状态展示（中间猫咪 logo、下方状态灯与三步使用指南、右上角齿轮），**设置页**承载全部配置与启停操作。主页不放操作按钮，避免当摆件摆在桌上时误触。

状态灯三种颜色：红=未启动，黄=正在接入，绿=等待唤醒词（常亮）/ 对话中（呼吸闪动）。

**打开应用会自动开始监听**（设置页的「打开应用自动开始监听」默认开启），所以日常只需打开 App 直接说话。以下情况不会自动拉起，主页停在「未启动」：开关被关掉、服务已在运行、麦克风权限未授予、SDK 路径尚未保存 Key。

1. 装好后打开 App，授予麦克风与通知权限（首次会弹窗，同意后自动开始监听）。
2. 进设置页点「加入电池优化白名单」，否则息屏几分钟后会被系统冻结，唤醒失效。
3. 回主页可见状态灯转绿、显示「等待唤醒词」。
4. 说「**小喵小喵**」唤醒，听到问候音后直接说话，可随时打断。
5. 说「再见」「退下」「不聊了」结束；或等空闲超时自动结束；或在设置页点「结束对话」。
6. 设置页的「停止监听」才会真正退出前台服务（结束对话只是回到监听）。

设置页可配置：服务路径（Demo / SDK）、API Key 及其保存、播放设备、空闲超时毫秒数、打开应用自动监听，以及「KWS 自检」。

## 修改唤醒词

唤醒词不开放界面配置，需在构建期改。当前关键词由 `assets/kws/keywords.txt` 决定，**两行**：

```
x iǎo m iāo x iǎo m iāo :1.0 #0.35 @小喵小喵   ← 标准读音（小=三声，喵=一声）
x iào m iāo x iào m iāo :1.0 #0.35 @小喵小喵   ← 方言/口音把「小」读四声时命中
```

一行的格式是 `<音素序列> :<boost> #<阈值> @<显示文本>`，四个部分是 sherpa-onnx 的关键词协议。

用脚本重新生成（依赖 `.toolchain/venv` 里的 `sherpa-onnx-cli`，会把中文转成 ppinyin 音素序列，并自动追加四声变体）：

```bash
bash scripts/generate-keywords.sh "小熊小熊"
```

然后重新构建、下发。注意事项：

- 模型是通用 zipformer2 transducer，关键词靠音素序列约束解码，所以**任选中文词都能用**，不必重训模型；但音节必须存在于 `tokens.txt`（227 个 ppinyin 音素）。
- 双音节词（如「小喵」）误唤醒率明显偏高，建议 4 个音节以上、叠词更稳。实测把两音节「小喵」也加进关键词，命中率涨 0、误唤醒从 0 涨到 8/21，**不要加**。
- 「KWS 自检」播放的是固定样本 `assets/greeting/xiaomiao_test.wav`（「小喵小喵」录音）。改词后自检必然报「未命中」，属预期行为，需同步替换样本。

## 唤醒灵敏度调优

唤醒不准（语速快慢、口音、轻声）时，四个旋钮按性价比排序：

| 旋钮 | 位置 | 作用 | 实测结论 |
|---|---|---|---|
| 发音变体 | `keywords.txt` 增加行 | 覆盖声调/口音漂移 | 加一条「小读四声」后命中原 40/47 → **47/47**，误唤醒仍 0 |
| `maxActivePaths` | `WakeDetector.kt` | 多条关键词同时搜索的宽度 | 4 条路径时第二条被挤掉（39/47），**8 才够**；RTF 仅 0.021→0.029 |
| `:boost`（每行） | `keywords.txt` | 关键词的 log 概率加成 | 1.0 最佳；提到 1.5 起开始误唤醒「小妙小妙」，2.0 反而命中降到 36/47 |
| `#阈值` | `keywords.txt` | 触发门限，越低越敏感 | 0.25~0.35 都在平台区，0.45 掉一例；0.35 左右留余量 |

参数不是拍脑袋定的，用调参台跑出来：

```bash
python scripts/tune-kws.py corpus    # edge-tts 合成 7 音色 × 5 语速 + 音高/音量漂移 + 负样本
python scripts/tune-kws.py sweep     # 扫关键词集合/阈值/boost/AGC，打印命中率与误唤醒
```

语料在 `.toolchain/kws-tune/corpus/`：47 条正样本（含 -25%~+25% 语速、±6% 音高、-12/-18dB 轻声、东北与中原官话）、21 条负样本（「小喵」「喵喵」「小妙小妙」「小苗小苗」「小米小爱」等近音词 + 官方测试集真实句子）。

想用**自己的录音**验证，把 16 kHz 单声道 wav 丢进 `corpus/pos/` 或 `corpus/neg/` 再 `sweep` 即可。

真机侧还有两个离线语料测不出来的因素：麦克风采集链（当前用 `VOICE_COMMUNICATION`，会走系统 AGC/NS，轻声有利、强降噪可能削掉辅音）与说话距离。如果调参后仍觉得远场不灵，先换 `VOICE_RECOGNITION` 采集源试，再考虑换更大的 KWS 模型。

## 协议要点

客户端只做三件事：推二进制音频、发少数几个 JSON action、处理下行事件。

| 方向 | 名称 | 说明 |
|---|---|---|
| ↑ | 二进制帧 | 16 kHz mono PCM16 LE |
| ↑ | `attach_session` | 仅真实 API 需要；demo 端点不要发 |
| ↑ | `playback.chunk.completed` | 每个音频分片播放完毕回报 |
| ↑ | `playback.completed` / `playback.stopped` | 整轮播完 / 被打断后停止 |
| ↓ | `session_attached` / `session.updated` | 会话就绪与参数 |
| ↓ | `input.transcript.updated` / `.done` | 用户侧流式转写 / 定稿 |
| ↓ | `output.transcript.updated` / `.done` | MOSS 侧流式转写 / 定稿 |
| ↓ | `response.audio.started` / `.done` / `.stop` | 下行音频生命周期 |
| ↓ | `response.interrupted` / `response.ended` | 被打断 / 本轮结束 |
| ↓ | 二进制帧 | 48 kHz mono PCM16 LE |

端点与模式在 `Config.kt`：

```kotlin
DEFAULT_ENDPOINT = "wss://api.mosi.cn/v1/realtime?model=moss-live-1.0"   // 真实 API，需 Bearer Key
DEMO_ENDPOINT    = "wss://www.moss.cn/api/v1/public/website/moss-live/ws?voice=female"  // 免密钥
```

`moss-live-demo/` 是个纯前端直连 demo（`index.html` + `app.js`），可以在浏览器里抓包看完整交互，改协议前先用它验证。

## 已知限制

- **息屏保活依赖厂商策略**。必须加电池优化白名单并允许自启动，否则 realme/小米等机型会冻结进程导致唤醒失效。
- **通话音量按媒体音量锚定，不顶满格**。代码把 `STREAM_VOICE_CALL` 按媒体音量相对比例锚定（上限 80%），避免历史版本顶满导致 AI 开口突兀（修复提交 `4d9d170`）。
- **demo 端点不保证长期可用**。它对接官网公开接口，随时可能变更或限流，正式使用请切真实 API。
- **仅 debug 构建**。`release` 未配签名，`isMinifyEnabled = false`。
- **唤醒词改动需重新构建**，无界面配置入口。

## 相关文档

- [docs/adb-wireless-setup.md](docs/adb-wireless-setup.md) —— 无线调试设备信息、双策略自动发现、adb daemon 重启等踩坑记录
- `scripts/tune-kws.py` —— 唤醒灵敏度调参台：造语料 + 扫关键词变体/阈值/boost，输出命中率与误唤醒
- `scripts/test-kws-offline.py` —— 用单个关键词文件跑命中率，快速回归
- `scripts/probe-moss-live.py` / `probe-moss-demo.py` —— 命令行验证 MOSS-Live 连通性
- `scripts/make-app-icon.py` —— 从猫咪插画生成各密度启动图标与预览图
