#!/usr/bin/env bash
# 不安装 Android Studio，仅用命令行搭建 Android 构建环境
# 下载优先走本地代理，失败时自动回退直连
set -euo pipefail

TC="I:/voice-agent/.toolchain"
SDK="$TC/android-sdk"
PROXY="${DOWNLOAD_PROXY:-http://127.0.0.1:7890}"
mkdir -p "$TC" "$SDK"

log() { echo "[setup] $*"; }

curl_dl() {
  local url="$1" out="$2"
  log "download $(basename "$out")"
  if ! curl -fL --retry 5 --retry-all-errors --retry-delay 2 --ssl-no-revoke --http1.1 \
      --proxy "$PROXY" -o "$out" "$url"; then
    log "proxy failed, retry direct: $url"
    curl -fL --retry 3 --retry-delay 2 -o "$out" "$url"
  fi
}

# ---------- JDK 17 ----------
if [ ! -x "$TC/jdk17/bin/java.exe" ]; then
  curl_dl "https://cdn.azul.com/zulu/bin/zulu17.62.17-ca-jdk17.0.17-win_x64.zip" "$TC/jdk17.zip"
  log "extract JDK 17"
  unzip -q -o "$TC/jdk17.zip" -d "$TC"
  JDK_DIR=$(find "$TC" -maxdepth 1 -type d -name 'zulu17*' | head -1)
  [ -n "$JDK_DIR" ] || { echo "JDK dir not found" >&2; exit 1; }
  [ "$JDK_DIR" = "$TC/jdk17" ] || mv "$JDK_DIR" "$TC/jdk17"
  rm -f "$TC/jdk17.zip"
fi
export JAVA_HOME="$TC/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"
log "java: $("$JAVA_HOME/bin/java.exe" -version 2>&1 | head -1)"

# ---------- Android SDK command-line tools ----------
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
  curl_dl "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" "$TC/cmdline-tools.zip"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q -o "$TC/cmdline-tools.zip" -d "$SDK/cmdline-tools"
  if [ -d "$SDK/cmdline-tools/cmdline-tools" ] && [ ! -d "$SDK/cmdline-tools/latest" ]; then
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  fi
  rm -f "$TC/cmdline-tools.zip"
fi

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager.bat"
PROXY_ARGS="--proxy=http --proxy_host=127.0.0.1 --proxy_port=7890"
log "accept SDK licenses"
yes | "$SDKMANAGER" --sdk_root="$SDK" $PROXY_ARGS --licenses >/dev/null 2>&1 || true

log "install SDK packages"
if ! "$SDKMANAGER" --sdk_root="$SDK" $PROXY_ARGS "platform-tools" "platforms;android-35" "build-tools;35.0.0"; then
  log "proxy sdkmanager failed, retry direct"
  "$SDKMANAGER" --sdk_root="$SDK" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
fi

# ---------- Gradle ----------
if [ ! -x "$TC/gradle-8.11.1/bin/gradle.bat" ]; then
  curl_dl "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip" "$TC/gradle.zip"
  unzip -q -o "$TC/gradle.zip" -d "$TC"
  rm -f "$TC/gradle.zip"
fi
export GRADLE_HOME="$TC/gradle-8.11.1"
export PATH="$GRADLE_HOME/bin:$PATH"
log "gradle: $("$GRADLE_HOME/bin/gradle.bat" -v 2>&1 | grep -E '^Gradle ' | head -1)"

# ---------- reuse env ----------
cat > "$TC/env.sh" <<EOF
export JAVA_HOME="$TC/jdk17"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export GRADLE_HOME="$TC/gradle-8.11.1"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$GRADLE_HOME/bin:\$PATH"
EOF
log "env file: $TC/env.sh"
log "DONE"
