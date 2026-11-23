# JVM 单元测试与代码覆盖率（2026-11-23）

## 结论

项目从「零自动化测试」建立起覆盖率体系：JaCoCo + JUnit4 + Robolectric，`bash scripts/run-tests.sh` 一键跑出报告。

覆盖率体系分两层：JVM 单测（60 用例，`bash scripts/run-tests.sh`）+ 真机 instrumentation（8 用例，`bash scripts/run-instrumented-tests.sh`），两份 ec 合并进同一份 JaCoCo 报告。

| 指标 | 基线 | 纯逻辑+Robolectric | 含 Wake 纯逻辑 | JVM 全量 | **+ 真机 instrumentation** |
|---|---|---|---|---|---|
| 分支 | 2.2% (23/1066) | 3.4% (36/1066) | 10.3% (110/1066) | 12.3% (131/1068) | **19.4% (207/1068)** |
| **行** | **2.8% (48/1719)** | **4.7% (81/1719)** | **13.2% (227/1719)** | **14.8% (254/1721)** | **30.5% (525/1721)** |
| 方法 | 3.3% (10/304) | 5.9% (18/304) | 26.3% (80/304) | 27.9% (85/305) | **40.0% (122/305)** |
| 类 | 4.1% (2/49) | 8.2% (4/49) | 28.6% (14/49) | 34.0% (17/50) | **48.0% (24/50)** |

单类行覆盖：`VoiceState` 100%、`WakeDetector$Companion` 100%、`WakeService$Companion` 100%、`KwsFiles` 97%、`AudioRingBuffer` 94.4%、`Config` 92.1%、`KeywordGenerator` 89.4%、`GreetingPlayer` 53.2%。

**真机 instrumentation 覆盖了原生类本体**（这是 JVM 侧永远达不到的）：`WakeDetector` 本体 **98.6%**（68/69，原生 spotter 加载+wav 自检+accept/poll 全链路命中）、`WakeService` 本体 **30.5%**（106/348，Service action 分发+通知渠道+音频路由+相位流转）。JVM 侧这两个类本体只有 0%/0.9%——因为 sherpa .so 加载即崩、Service 生命周期需要真机。两者合并后是全项目 30.5%。

**测试顺带发现并修复一个真实 bug**：`KwsFiles.hasCustomKeywords()` 用 trim 后的文本和末尾带 `\n` 的 `DEFAULT_KEYWORDS` 直接比较，导致默认词表永远被误判为「已自定义」（设置页状态会显示错）。已改为 `!= DEFAULT_KEYWORDS.trim()`。

## 环境事实（复用前必读）

- JDK17 / Gradle 8.11.1 / Android SDK 全部在 `.toolchain/`，系统没装 java，必须走 `scripts/run-tests.ps1` 里那套环境变量。
- **JaCoCo 是 Gradle 核心插件，不能在 `plugins {}` 里带版本号**（`id("jacoco") version "..."` 会报错），版本随 Gradle 自带。
- Maven 走本机代理 `127.0.0.1:7890`（`JAVA_TOOL_OPTIONS` 里设 http/https proxyHost），JUnit 等新依赖靠它下载。**但 Robolectric 的 android-all 下载不能走这条远程路径**（见踩坑），必须走本地镜像。
- Kotlin 编译产物在 `app/build/tmp/kotlin-classes/debug`，JaCoCo 的 `classDirectories` 指向这里；单测 exec 数据在 `app/build/jacoco/testDebugUnitTest.exec`。
- **Robolectric 本地镜像目录：`.toolchain/robolectric-deps/`**，已放 `android-all-instrumented-13-...` 和 `14-...` 两个 jar（各 150MB 左右）。build.gradle 里用 `robolectric.dependency.dir` + `robolectric.offline=true` 指向它。换 Robolectric 版本或 SDK 时需确认目录里有对应版本的 jar。

## 关键做法

1. **纯逻辑可测性改造**：`KeywordGenerator.generate()` 只依赖内存 map，不碰 Android API。把 assets 加载拆成 `parseMap(lines: Sequence<String>)` 纯函数，再加 `internal injectMapForTest(map)` 注入点，JVM 单测即可绕过 Context。
2. **测试数据用真实映射**：从 `pinyin_map.txt` 抽样真实音素构造测试 map，保证断言口径与生成物一致（如「小喵小喵」两行与线上 `keywords.txt` 逐字相同）。
3. **JaCoCo 报告 task**：在 app 的 `build.gradle.kts` 注册 `jacocoTestReport`，`dependsOn("testDebugUnitTest")`，输出 XML/HTML/CSV 三种格式。
4. **Robolectric 覆盖 Context 相关类**：`KwsFiles` 依赖 context.assets/filesDir，用 `@RunWith(RobolectricTestRunner)` + `@Config(sdk = [33])` 即可在无设备情况下测文件复制、版本戳、词表读写；需 `testOptions.unitTests.isIncludeAndroidResources = true` 才能读到合并后的 assets。
5. **原生/Service 类的纯函数提取**：WakeDetector/WakeService 本体依赖 sherpa .so 和 Service 生命周期，JVM 无法实例化。把可独立验证的纯函数（`wavPcm`、`isFarewell`、`pcm16ToBytes`、`computeCallVolume`）提到 companion object 标 `internal`——纯位置移动、行为不变，实例方法内调用自动解析到 companion 同名函数，测试直接 `WakeService.computeCallVolume(...)` 即可。
6. **JVM 单测的边界**：Activity 生命周期、sherpa 原生检测、AudioManager/WebSocket 仍需真机 instrumentation 才能覆盖。

## 踩坑

- 中文测试名（反引号里的中文）在 Gradle 控制台打印成乱码，但**测试本身正常执行**，两个 `@Test` 用例都通过，不影响结果。看结果以 XML 报告为准。
- `MAX_LINES` 用例最初用了 13 个字，先触发「超 12 字」校验而抛异常，测不到行数截断逻辑；改成 12 个不同三声字才正确命中 `MAX_LINES=8` 分支。
- 解析 XML 算覆盖率时，公式是 `covered / (covered + missed)`，别把分子分母写反成「未覆盖率」。
- **Robolectric 首次跑报 `SHA-512 mismatch for POM file`**：代理环境下 Robolectric 从 Maven Central 拉 android-all 时校验和不符（curl 拿到的和它拿到的还不一样，代理缓存不一致）。解法：`robolectric.dependency.dir` + `robolectric.offline=true` 走本地镜像，别用 `robolectric.dependency.repo.url`。
- **本地镜像 jar 版本要对**：`@Config(sdk = [33])` 在 Robolectric 4.13 下实际请求的是 `android-all-instrumented-14-robolectric-10818077-i6.jar`（不是 13），缺哪个报 `Path is not a file`。两个都下好最省心。
- `ApplicationProvider` 需要显式加 `androidx.test:core` 依赖，Robolectric 不会传递带进来。
- **Config 是 object + lateinit prefs**：Robolectric 每个测试重建 application，但 Config 单例不变、prefs 残留指向旧 context。测试 setUp 里用反射把 `prefs` 字段重绑到当前 context 再 clear，否则读写不一致。
- **反射测私有纯逻辑**（如 GreetingPlayer.parseWav/downmixToMono/applyGain）可行且不用改生产代码：`getDeclaredMethod(...).apply { isAccessible = true }`。构造 `GreetingPlayer(android.app.Application())` 在纯 JVM 下也不报错（构造只存 context，不调 Android 方法）。
- `assertEquals` 注意 Kotlin 类型重载：`assertEquals(1000.toShort(), getShort())` 会被匹配成 `assertEquals(Integer, Short)` 而失败，统一用 `.toInt()` 转整数再断言。
- 合成 wav 的字节长度要算对：N 个 int16 样本 = N×2 字节，`copyOfRange(44, 44 + 样本数×2)`。
- `shortArrayOf(0xFFFE)` 编译不过（超出 Short 范围），字面量要 `.toShort()`。
- **别指望 JVM 测原生类的本体**：`WakeDetector`/`WakeService` 构造即 `new KeywordSpotter`（加载 sherpa .so）+ Service 生命周期，Robolectric 也救不了。只提纯函数到 companion 测，本体留给真机。把纯函数提到 companion 后，实例方法里的同名调用会自动解析到 companion 版本，编译无误。

## 真机 instrumentation（2026-11-23，覆盖原生类本体）

JVM 侧把纯逻辑采完后，用真机 instrumentation 覆盖 `WakeDetector`/`WakeService` 本体（原生 spotter + Service 生命周期）。这部分 JVM/Robolectric 都测不了，只能真机。

### 构建配置（`app/build.gradle.kts`）
- `defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`
- `buildTypes.debug.isTestCoverageEnabled = true` —— AGP 给主 APK 插桩，运行时写出 coverage.ec；APK 内应能看到 `jacoco-agent.properties`
- androidTest 依赖：`androidx.test:runner:1.6.2` + `rules:1.6.1` + `ext:junit:1.2.1` + `core:1.6.1`
- 覆盖率 ec 最终拉到 `app/build/outputs/code_coverage/`，`jacocoTestReport` 的 executionData 通配 `outputs/code_coverage/**/*.ec` 会自动合并

### 关键坑：不能用 Gradle 的 connectedDebugAndroidTest
这个环境的 adb daemon **每条命令后重启**，连接不跨命令保留。Gradle 的 `connectedDebugAndroidTest` 内部一连 adb 就触发 daemon 重启 → `No connected devices!`（已实测确认，非推测）。
**解法**：走 `scripts/run-instrumented-tests.sh`，所有 adb 操作都经 `adb-wireless.sh`（每次 connect + 执行在同一个 Python 进程，见 adb-wireless-setup.md）。手动：`install -r` 主 APK + 测试 APK → `am instrument -w -e coverage true -e coverageFilePath /data/user/0/com.example.xiaomiao/files/coverage.ec <testPkg>/androidx.test.runner.AndroidJUnitRunner` → 拉 ec → 跑 `jacocoTestReport` 合并。

### 拉 ec：`adb pull` 无权限，用 run-as + exec-out（二进制安全）
ec 写在 app 私有目录 `/data/user/0/com.example.xiaomiao/files/coverage.ec`，`adb pull` 报 Permission denied。debug APK 是 debuggable，用 `adb exec-out run-as com.example.xiaomiao cat files/coverage.ec` 读二进制流（**必须 exec-out，不能用 shell**，shell 会做 \r\n 转换损坏二进制）。封装在 `scripts/pull-coverage.py`。

**ec 格式认知坑**：JaCoCo exec 魔数是 `01 c0 c0 10 07`（block header 0x01 + magic 0xC0C0 + version 0x1007），不是老资料里的 `ab 01 01 01`。看到 `01 c0 c0 10 ... unknownhost` 开头是合法的，别误判为损坏。

**class ID 匹配**：真机 ec 用插桩后 class ID，`jacocoTestReport` 的 `classDirectories` 指向未插桩的 `kotlin-classes/debug`——实测能正确匹配合并，无需额外处理。

### 测试写法坑
- **直接 `new WakeService()` 没有 context**：反射调 `onStartCommand(ACTION_STOP)` 会走到 `updateNotification → getSystemService`，NPE。需先反射 `ContextWrapper.attachBaseContext(ctx)` 注入 application context。
- **别触发会连网/开麦克风的 action**：ACTION_START / ACTION_WAKE 会构造 detector 并 connect WebSocket，依赖外网，测试里只测 STOP/END_SESSION/UPDATE_AUDIO/RELOAD_KWS 这类安全 action。
- **WakeDetector 测试要清理 filesDir**：`setUp` 里删 `wake_test.wav` + `KwsFiles.resetKeywords(ctx)`，保证回退默认「小喵小喵」样本与词表。
- `am instrument` 的测试 runner 包名 = applicationId + `.test` = `com.example.xiaomiao.test`。

### 实测结果（2026-11-23，最终全绿）
- 8 个真机用例全过。`WakeDetectorInstrumentedTest` 4 个：selfTest 命中、accept/poll 全链路命中、静音零误唤醒、wavPcm 解析——**原生检测链路真机验证通过**。
- `WakeServiceInstrumentedTest` 4 个：通知渠道创建、companion 纯函数真机交叉验证、安全 action 分发不崩、相位流转。
- 进程中被测试迭代修正的两个测试写法问题（非生产 bug）：反射 new 的 Service 需 `attachBaseContext` 才能 `getSystemService`；`ACTION_STOP` 会走 `stopForeground`/`stopSelf` 需要真实组件名，不适合反射模拟，已去掉；`tearDown` 删通知渠道会因前台服务在跑被系统拒绝，已去掉。
- 覆盖率 ec：`/data/user/0/com.example.xiaomiao/files/coverage.ec`，拉到 `outputs/code_coverage/instrumented/coverage.ec`，`jacocoTestReport` 自动合并。
- 设备会间歇性休眠导致 adb 服务停掉（见 adb-wireless-setup.md），跑之前确认 `bash scripts/adb-wireless.sh` 能连上。

### 新增文件
- `app/src/androidTest/java/com/example/xiaomiao/WakeDetectorInstrumentedTest.kt`（4 用例，原生检测全链路）
- `app/src/androidTest/java/com/example/xiaomiao/WakeServiceInstrumentedTest.kt`（4 用例，Service 状态机 + 通知渠道 + 相位流转）
- `scripts/run-instrumented-tests.sh`（真机一键：探测→装 APK→跑测试→拉 ec→合并报告）

## 下一步（提升覆盖率的方向）

- 设备重开无线调试后跑 `bash scripts/run-instrumented-tests.sh`，拉取真机 ec 合并进 JaCoCo，拿到 WakeDetector/WakeService 本体的最终覆盖率数字。
- （可选，非 Kotlin）给 Python 脚本加 pytest——但注意它不进 JaCoCo 报告，与本项目 Kotlin 覆盖率无关。

## 新增/改动文件

- `xiaomiao-voice-agent/build.gradle.kts`：保持原样（jacoco 是核心插件，无需在此声明版本）
- `xiaomiao-voice-agent/app/build.gradle.kts`：apply jacoco、JUnit4 + Robolectric + androidx.test:core 依赖、`testOptions.unitTests`（资源合并 + 本地 Robolectric 镜像）、`jacocoTestReport` task
- `KeywordGenerator.kt`：拆出 `parseMap` + `injectMapForTest`
- `KwsFiles.kt`：修复 `hasCustomKeywords` 默认词表误判 bug
- `app/src/test/java/com/example/xiaomiao/KeywordGeneratorTest.kt`（新增，9 个用例）
- `app/src/test/java/com/example/xiaomiao/KwsFilesTest.kt`（新增，6 个 Robolectric 用例）
- `app/src/test/java/com/example/xiaomiao/AudioRingBufferTest.kt`（新增，6 个纯 JVM 用例）
- `app/src/test/java/com/example/xiaomiao/VoiceStateTest.kt`（新增，6 个纯 JVM 用例）
- `app/src/test/java/com/example/xiaomiao/ConfigTest.kt`（新增，8 个 Robolectric 用例）
- `app/src/test/java/com/example/xiaomiao/GreetingPlayerTest.kt`（新增，6 个反射测私有纯逻辑用例）
- `WakeDetector.kt` / `WakeService.kt`：`wavPcm`/`isFarewell`/`pcm16ToBytes`/`computeCallVolume` 提到 companion（纯位置移动）
- `app/src/test/java/com/example/xiaomiao/WakeDetectorTest.kt`（新增，5 个 wavPcm 解析用例）
- `app/src/test/java/com/example/xiaomiao/WakeServiceLogicTest.kt`（新增，14 个纯逻辑用例）
- `.toolchain/robolectric-deps/`（新增，两个 android-all 镜像 jar）
- `scripts/run-tests.sh` / `run-tests.ps1`（新增，一键入口）
- `app/src/androidTest/` 下两个 instrumentation 测试类 + `scripts/run-instrumented-tests.sh`（新增，真机一键）
- 报告输出：`app/build/reports/jacoco/jacocoTestReport/html/index.html`
