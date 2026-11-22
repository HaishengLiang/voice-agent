#!/usr/bin/env bash
# 构建 debug APK：bash scripts/build-apk.sh
set -euo pipefail
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "I:\\voice-agent\\scripts\\build-apk.ps1"
