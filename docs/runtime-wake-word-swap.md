# 运行时唤醒词替换：在 App 内生成音素并即时生效

> 2026-11-23 沉淀。目标是不重装 APK、不连 PC，直接在设置页改唤醒词。本文记录方案选型依据、
> 数据管线、踩坑和可复用做法。灵敏度/方言变体的实测口径见 `kws-wake-tuning.md`，本文不重复。

## 一、结论先行

| 项 | 改前 | 改后 |
|---|---|---|
| 换词方式 | 改 `keywords.txt` + 重打包 + 重装 | 设置页输入 → 即时生效 |
| 音素来源 | PC 端 `sherpa-onnx-cli text2token` | 构建期映射表 + App 查表 |
| 模型加载 | `newFromAsset`（assets 只读） | `newFromFile`（filesDir 可写） |
| 依赖 | sherpa-onnx AAR + pypinyin(PC) | 仅 sherpa-onnx AAR（pypinyin 前移到构建期） |

三句话总结：

1. **sherpa-onnx 的 Android AAR 里没有任何 pinyin/text2token 能力**——133 个类里只有 KeywordSpotter 相关。中文转音素要么带拼音库进 App，要么把转换前移到构建期做成查表，本项目选后者。
2. **`KeywordSpotter(assetManager=null, config)` 走 `newFromFile`**（见官方 2.0.2 源码 `KeywordSpotter.kt`），能读文件系统里的模型和词表；传了 assetManager 就锁死在只读 assets。所以要把 4.9 MB 模型复制到 filesDir。
3. **`createStream(keywords)` 是追加关键词、不是替换**，无法用它换词，这条路已排除。

## 二、方案选型（为什么这么设计）

| 方案 | 做法 | 结论 |
|---|---|---|
| A 构建时换词 | 跑脚本重生成词表 + 重打包 | 可靠但要重装，作为兜底保留（`scripts/generate-keywords.sh`） |
| **B 预生成映射表 + App 查表** | 构建期用 pypinyin 算出汉字→音素表，App 只拼接 | **采用**。零运行时依赖，离线可用 |
| C 集成拼音库进 App | pinyin4j / TinyPinyin 在端上算 | 否决：TinyPinyin 不带声调直接出局；pinyin4j 还要自己写声母韵母拆分+token 校验，且结果与 pypinyin 可能不一致 |
| D `createStream(keywords)` 传词 | 运行时给 stream 追加关键词 | 否决：是追加不是替换，旧词仍生效 |
| E 服务端生成 | 词发后端跑 pypinyin | 否决：要联网，demo 模式无 key |

社区开源方案（Open-XiaoAI `keywords.py`、AXERA `generate_keyword_tokens.py`、
官方 `text2token`）全是 PC 端生成词表再拷到设备，**没有一个做 App 内实时换词**。
但它们的转换核心可以借鉴：`pypinyin` → `to_initials/to_finals_tone` 拆声母+带调韵母 → 校验 tokens.txt。

## 三、数据管线

```
构建期  scripts/build-pinyin-map.py
        pypinyin(heteronym) + tokens.txt
        -> assets/kws/pinyin_map.txt (20872 字, 225 KB)
            格式: 小=x iǎo   喵=m iāo   每行「字=读音1|读音2」

首次启动  KwsFiles.ensureReady()
        assets/kws 的 5 个模型文件 + tokens + pinyin_map
        -> filesDir/kws/  (4.9 MB, 按 MODEL_VERSION 决定是否重拷)

改词  SettingsActivity.applyWakeWord()
        中文词 -> KeywordGenerator.generate() -> 多行关键词文本
        -> 写 filesDir/kws/keywords.txt
        -> startService(ACTION_RELOAD_KWS) -> WakeService.reloadDetector()
        -> 释放旧 spotter、新建（词表文件路径不变，还是那个 keywords.txt）
```

**映射表口径验证**：`小喵小喵 → x iǎo m iāo x iǎo m iāo`，与 `text2token` 逐字一致；
`你好小智 → n ǐ h ǎo x iǎo zh ì`，与社区示例一致。

## 四、关键代码位置

| 文件 | 作用 |
|---|---|
| `scripts/build-pinyin-map.py` | 构建期生成映射表，`--variants` 可输出多音字全部读音 |
| `assets/kws/pinyin_map.txt` | 生成物，随 APK 打包，勿手改 |
| `KeywordGenerator.kt` | 查表 + 拼音素 + 生成三声变体行；`BOOST=1.0 THRESHOLD=0.35 MAX_LINES=8` |
| `KwsFiles.kt` | 模型落 filesDir、读写词表；`MODEL_VERSION` 升级时 +1 触发重拷 |
| `WakeDetector.kt` | 构造改走文件系统路径（`newFromFile`）；selfTest 支持自定义样本 |
| `WakeService.kt` | `ACTION_RELOAD_KWS` + `reloadDetector()` |
| `Config.kt` | `wakeWord` 持久化，`DEFAULT_WAKE_WORD=小喵小喵` |
| `SettingsActivity.kt` | 基本设置页唤醒词输入框 + 应用/恢复默认 |

## 五、踩坑与设计要点

1. **必须整体切到 `newFromFile`，不能混用**。config 里 encoder/decoder/joiner/tokens/keywords 全走同一套路径解析，
   只要用了 assetManager，所有路径都从 assets 找。所以模型要整体复制到 filesDir，词表也在那里才可写。
2. **模型版本戳**：`KwsFiles.MODEL_VERSION` 是手动维护的。改了映射表或模型文件必须 +1，
   否则老用户升级 App 后 filesDir 还是旧文件。判断条件是「版本戳不符 OR 任一文件缺失/为空」。
3. **三声变体行按「不同的字」去重**，一个字出现多次时整行一起变，与旧的两行配置完全对齐
   （`小喵小喵 → 标准音行 + x iào m iāo x iào m iāo`）。行数上限设 8，与 `maxActivePaths` 一致——
   变体太多会挤占路径、抬升误唤醒（见 kws-wake-tuning.md 的 paths 实验）。
4. **多音字只取 heteronym[0]（常用音）**。「重庆」会取 `zh qìng` 而不是 `ch qìng`，
   如需覆盖次读音，`build-pinyin-map.py --variants` 生成全部读音写在 `|` 后，再改 `KeywordGenerator`
   让它对多音字也出变体行。默认不做，避免路径膨胀。
5. **selfTest 样本**：换词后 assets 里的 `xiaomiao_test.wav` 是旧词录音，会假性未命中。
   现在优先读 `filesDir/wake_test.wav`（用户录的新词样本），没有才回退 assets 并在文案里标注。
   要彻底解决需加「录一段唤醒词」入口写这个文件——尚未做。
6. **换词时机**：`reloadDetector()` 只在 `LISTENING_WAKE` 态执行，会话进行中不动，避免打断对话。
   不在监听态时改词只写文件，下次启动自然读新词表。

## 六、待办 / 后续

- [ ] 真机验证：装 APK → 开监听 → 设置页改词 → 说新词确认唤醒
  - 2026-11-23 进展：新包已下发安装（versionName 0.1.0），app 正常启动、设置三分区正常，
    `run-as com.example.xiaomiao cat files/kws/keywords.txt` 可读到默认词表（两行/boost 1.0/阈值 0.35），
    证明模型与映射表已正确落到 filesDir。
  - **卡点：`adb shell input text 你好小智` 在这台设备上输入中文无效**（输入框清空后仍是空/仅显示标签，
    uiautomator dump 确认文本没写进去）。要跑通 UI 改词链路需另找中文注入方案：
    ① ADB 剪贴板 → 长按输入框粘贴；② 装 ADB 输入法（ADBKeyboard）；③ `run-as` 直接改
    `shared_prefs/moss_wake.xml` 的 `wake_word` + `files/kws/keywords.txt` 后触发 `ACTION_RELOAD_KWS`
    （绕过 UI，只验证下半段链路）。`input text` 走不通，别再试。
- [ ] 加「录制唤醒词样本」入口写 `filesDir/wake_test.wav`，让自检对新词也有效
- [ ] 新词的负样本近音词需人工补（现有 `tune-kws.py` 的 NEG_PHRASES 写死了「小妙小妙」等旧词近音）
- [ ] 若换词后误唤醒升高，按 `kws-wake-tuning.md` 的 sweep 方法重新扫 boost/阈值
