#!/usr/bin/env bash
# run-instrumented-tests.sh — 真机跑 instrumentation 测试 + 采集覆盖率 + 合并 JaCoCo 报告
#
# 这个环境的 adb daemon 每条命令后会重启，连接不跨命令保留。
# 故所有 adb 操作都走 adb-wireless.sh（每次 connect + 执行在同一 Python 进程），
# 不用 Gradle 的 connectedDebugAndroidTest（它内部连发多条 adb 命令会掉线）。
#
# 前置：
#   1. 设备在线（无线调试已开，见 docs/adb-wireless-setup.md）
#   2. 已构建插桩 APK：bash scripts/build-apk.sh（debug 开了 testCoverageEnabled）
#   3. 已构建测试 APK：gradle assembleDebugAndroidTest
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APP="$ROOT/xiaomiao-voice-agent/app"
MAIN_APK="$APP/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$APP/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
COVERAGE_REMOTE="/data/user/0/com.example.xiaomiao/files/coverage.ec"
COVERAGE_LOCAL_DIR="$APP/build/outputs/code_coverage/instrumented"
COVERAGE_LOCAL="$COVERAGE_LOCAL_DIR/coverage.ec"
RUNNER="com.example.xiaomiao.test/androidx.test.runner.AndroidJUnitRunner"

echo "=== [1/5] 探测设备 ==="
bash scripts/adb-wireless.sh shell "getprop ro.serialno" | tail -3 || {
    echo "[!] 设备不在线。请先开无线调试（设置>开发者选项>无线调试），重试。"; exit 1; }

echo "=== [2/5] 安装 APK ==="
bash scripts/adb-wireless.sh install -r "$MAIN_APK" 2>&1 | tail -2
bash scripts/adb-wireless.sh install -r "$TEST_APK" 2>&1 | tail -2

echo "=== [3/5] 清理旧覆盖率文件（失败无妨，instrument 会覆盖）==="
bash scripts/adb-wireless.sh shell "rm -f $COVERAGE_REMOTE" 2>&1 | tail -2 || true

echo "=== [4/5] 运行 instrumentation 测试 ==="
# am instrument 会跑数秒（加载 4.9M 模型 + 解码），输出完整保留便于确认通过数
bash scripts/adb-wireless.sh shell "am instrument -w -e coverage true -e coverageFilePath $COVERAGE_REMOTE $RUNNER" 2>&1 | tee /tmp/am-instrument.log | tail -20
if ! grep -q "OK (8 tests)" /tmp/am-instrument.log; then
    echo "[!] 测试未全部通过或设备中途断连。设备常因休眠停 adb 服务，请重开无线调试后重试。"
    echo "    完整输出见 /tmp/am-instrument.log"
fi

echo "=== [5/5] 拉取覆盖率并合并报告 ==="
# 设备可能在跑测试后休眠，这里确认一次，掉了就明确提示（不静默失败）
bash scripts/adb-wireless.sh shell "getprop ro.serialno" >/dev/null 2>&1 || {
    echo "[!] 设备已断连（休眠导致 adb 服务停），无法拉 ec。请重开无线调试后重试。"; exit 1; }
mkdir -p "$COVERAGE_LOCAL_DIR"
# 用 Windows 风格绝对路径（ROOT 是 Git Bash 的 /i/... 路径，Python 直接打不开）
WIN_ROOT="${ROOT/\/i\//I:/}"
"$WIN_ROOT/.toolchain/venv/Scripts/python.exe" \
    "$WIN_ROOT/scripts/pull-coverage.py" "${COVERAGE_LOCAL/\/i\//I:/}" 2>&1 | tail -5
ls -lh "$COVERAGE_LOCAL"

# 用 Gradle 生成合并报告（JVM 单测 exec + 真机 ec 一起）
export JAVA_HOME="${JAVA_HOME:-I:/voice-agent/.toolchain/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-I:/voice-agent/.toolchain/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_TOOL_OPTIONS='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890'
"I:/voice-agent/.toolchain/gradle-8.11.1/bin/gradle.bat" -p "$ROOT/xiaomiao-voice-agent" \
    jacocoTestReport --no-daemon --console=plain 2>&1 | tail -10

echo "=== 完成。报告：$APP/build/reports/jacoco/jacocoTestReport/html/index.html ==="
