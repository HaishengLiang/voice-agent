#!/usr/bin/env bash
# 用法: bash scripts/generate-keywords.sh "小喵小喵"
# 依赖: I:/voice-agent/.toolchain/venv 中已安装 sherpa-onnx
#
# 生成两条关键词行：标准音 + 「小」读四声的变体（覆盖方言/口音漂移）。
# 参数口径来自 scripts/tune-kws.py 的实测：boost 1.0、阈值 0.35、活跃路径 8。
set -euo pipefail

WORD="${1:?请传入唤醒词，例如: bash scripts/generate-keywords.sh 小喵小喵}"
KWS_DIR="I:/voice-agent/xiaomiao-voice-agent/app/src/main/assets/kws"
CLI="I:/voice-agent/.toolchain/venv/Scripts/sherpa-onnx-cli.exe"
TMP="I:/voice-agent/.toolchain/keywords.in"

printf '%s :1.0 #0.35 @%s\n' "$WORD" "$WORD" > "$TMP"
"$CLI" text2token \
  --tokens "$KWS_DIR/tokens.txt" \
  --tokens-type ppinyin \
  "$TMP" \
  "$KWS_DIR/keywords.txt"

# 追加发音漂移变体：把「小」的三声改成四声（xiǎo → xiào）。
# 只在唤醒词里真的含「小」时才会产生差异，否则保持单行。
BASE="$(cat "$KWS_DIR/keywords.txt")"
VARIANT="$(printf '%s\n' "$BASE" | sed 's/x iǎo/x iào/g')"
if [ "$VARIANT" != "$BASE" ]; then
    printf '%s\n' "$VARIANT" >> "$KWS_DIR/keywords.txt"
    echo "已追加四声变体"
fi

echo "已生成: $KWS_DIR/keywords.txt"
cat "$KWS_DIR/keywords.txt"
