#!/usr/bin/env bash
# 运行 JVM 单元测试 + 生成 JaCoCo 覆盖率报告
# 用法：bash scripts/run-tests.sh
set -euo pipefail
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File "I:\\voice-agent\\scripts\\run-tests.ps1"
