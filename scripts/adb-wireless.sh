#!/usr/bin/env bash
# adb-wireless.sh — 自动发现并连接无线调试设备（薄封装）
# 真正的发现逻辑在 adb-wireless-discover.py：mDNS 广播 + 网段端口扫描，无需手填 IP/端口。
#
# 用法:
#   ./scripts/adb-wireless.sh                       # 自动发现+连接+打印身份
#   ./scripts/adb-wireless.sh shell <cmd...>        # 连接后执行 adb 命令
#   ./scripts/adb-wireless.sh install app.apk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="$ROOT/.toolchain/venv/Scripts/python.exe"
[[ -f "$PY" ]] || PY="$ROOT/.toolchain/venv/bin/python"

exec "$PY" "$ROOT/scripts/adb-wireless-discover.py" "$@"
