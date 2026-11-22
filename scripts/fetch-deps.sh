#!/usr/bin/env bash
# =============================================================================
# fetch-deps.sh — 项目依赖统一下载/还原脚本
#
# 目标：项目的 JDK / Android SDK / Gradle / Python venv / KWS 模型全部来自
#       互联网，不入库。换机器后只需运行本脚本即可一键还原全部依赖。
#
# 用法：
#   bash scripts/fetch-deps.sh                 # 下载全部依赖（默认）
#   bash scripts/fetch-deps.sh toolchain       # 仅 JDK + Android SDK + Gradle
#   bash scripts/fetch-deps.sh python          # 仅 Python venv + sherpa-onnx
#   bash scripts/fetch-deps.sh model           # 仅 KWS 唤醒词模型
#   bash scripts/fetch-deps.sh all             # 同默认
#   DOWNLOAD_PROXY="" bash scripts/fetch-deps.sh   # 不走代理（直连）
#
# 特性：代理优先失败回退直连 / 断点续传 / sha256 校验 / 已就绪自动跳过
# 环境变量：
#   DOWNLOAD_PROXY   下载代理，默认 http://127.0.0.1:7890，设为空则直连
#   PYPI_INDEX       pip 源，默认 https://pypi.org/simple
# =============================================================================
set -euo pipefail

TC="I:/voice-agent/.toolchain"
ASSETS="I:/voice-agent/xiaomiao-voice-agent/app/src/main/assets/kws"
PROXY="${DOWNLOAD_PROXY:-http://127.0.0.1:7890}"
PYPI_INDEX="${PYPI_INDEX:-https://pypi.org/simple}"

# modelscope 模型仓库
MODEL_NS="pkufool"
MODEL_NAME="sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
MODEL_API="https://www.modelscope.cn/api/v1/models/${MODEL_NS}/${MODEL_NAME}/repo"

log()  { echo "[fetch] $*"; }
warn() { echo "[fetch][warn] $*" >&2; }
die()  { echo "[fetch][err] $*" >&2; exit 1; }

# curl 下载：代理优先，失败回退直连；支持断点续传；-o 目标路径
curl_dl() {
  local url="$1" out="$2" name
  name="$(basename "$out")"
  mkdir -p "$(dirname "$out")"
  local base=(-fL --retry 5 --retry-all-errors --retry-delay 2 --ssl-no-revoke --http1.1)
  log "download $name"
  if [ -n "$PROXY" ]; then
    if curl "${base[@]}" --proxy "$PROXY" -C - -o "$out" "$url"; then
      return 0
    fi
    warn "proxy failed, retry direct: $url"
  fi
  curl "${base[@]}" -C - -o "$out" "$url"
}

sha256_of() {
  sha256sum "$1" 2>/dev/null | awk '{print $1}' || \
  python -c "import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" "$1"
}

# -----------------------------------------------------------------------------
# 模块 1：工具链 JDK17 + Android SDK + Gradle（封装已有 setup-toolchain.sh）
# -----------------------------------------------------------------------------
fetch_toolchain() {
  log "=== toolchain: JDK17 / Android SDK / Gradle ==="
  bash "I:/voice-agent/scripts/setup-toolchain.sh"
}

# -----------------------------------------------------------------------------
# 模块 2：Python venv + sherpa-onnx
# -----------------------------------------------------------------------------
fetch_python() {
  log "=== python: venv + sherpa-onnx ==="
  local venv="$TC/venv" py="$TC/venv/Scripts/python.exe"
  if [ ! -x "$py" ]; then
    local host_py
    host_py="$(command -v python || command -v python3 || true)"
    [ -n "$host_py" ] || die "未找到本机 Python，请先安装 Python 3.10+"
    log "create venv via $host_py"
    "$host_py" -m venv "$venv"
  fi
  # 用 requirements 文件安装（版本锁定，随仓库入库），已满足则 pip 自动跳过
  local req="I:/voice-agent/scripts/requirements-python.txt"
  if [ ! -f "$req" ]; then
    die "缺少依赖清单 $req"
  fi
  if ! "$py" -m pip install -i "$PYPI_INDEX" -r "$req" 2>/dev/null; then
    warn "指定 pip 源失败，回退默认源"
    "$py" -m pip install -r "$req"
  fi
  [ -x "$TC/venv/Scripts/sherpa-onnx-cli.exe" ] || die "sherpa-onnx-cli 安装失败"
  log "python ready: $("$py" --version 2>&1)"
}

# -----------------------------------------------------------------------------
# 模块 3：KWS 唤醒词模型（逐文件下载 + sha256 校验）
# 清单格式：<sha256>  <size>  <相对路径>
# -----------------------------------------------------------------------------
MODEL_MANIFEST="
dd0ec68b5d9ee736f016fc982078bd3cdbccc69057e9910bdd45580992d13367 767 README.md
63cd5920dc448cf6416aa533e4de43f3066f646ddfca8ed19e05b1b265f37cb6 48 configuration.json
ed83454004d5bd16d831eaf00adcd181ed7734886aab6ef440f3ffa5aa3cfe3b 181069 decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx
fb581d6734511676e246e0dff2fea01b31b0913176cb3ca64576dbab0a177774 675349 decoder-epoch-12-avg-2-chunk-16-left-64.onnx
ed83454004d5bd16d831eaf00adcd181ed7734886aab6ef440f3ffa5aa3cfe3b 181069 decoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx
fb581d6734511676e246e0dff2fea01b31b0913176cb3ca64576dbab0a177774 675349 decoder-epoch-99-avg-1-chunk-16-left-64.onnx
dd784973fc9d2fabb3b800d6dcd20fc3b0ca84f8e2415afe54b032878e447f4d 4777666 encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx
859cd6decc23f35e6ddb3a6ddb7172a57f6de7b54288728c2433ab94b7635c59 12152560 encoder-epoch-12-avg-2-chunk-16-left-64.onnx
dd784973fc9d2fabb3b800d6dcd20fc3b0ca84f8e2415afe54b032878e447f4d 4777666 encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx
859cd6decc23f35e6ddb3a6ddb7172a57f6de7b54288728c2433ab94b7635c59 12152560 encoder-epoch-99-avg-1-chunk-16-left-64.onnx
f79760052b87239e325f0567c752ad3130b30d92effb847d4307743c20c59a24 65242 joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx
fcf43a2edf687e2e1bc8a2b2cf53129d3b0d693d90dcc4202e2d53b43db6c43c 253410 joiner-epoch-12-avg-2-chunk-16-left-64.onnx
f79760052b87239e325f0567c752ad3130b30d92effb847d4307743c20c59a24 65242 joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx
fcf43a2edf687e2e1bc8a2b2cf53129d3b0d693d90dcc4202e2d53b43db6c43c 253410 joiner-epoch-99-avg-1-chunk-16-left-64.onnx
75b5846317e6c90d37da2c8ba5d05e6f5688eca20c7bca1ad4d0372795339ace 101 keywords_raw.txt
668bf8df51a10027b84d5d8816a1ce11ae93545538dc05cfe2aa6811d399c250 179646 test_wavs/0.wav
30edbabea84ca4c076f5b43bb44495f436f6711319c32a200d3fc5c67c1fee1d 164976 test_wavs/1.wav
04a579c23bdd6de049814f1cbfb470d735d7747ba37eb0a92d813c89da942706 144856 test_wavs/2.wav
710e79fb3450042be50bb2f6432a2a549b53577aea041a577e53a926fcabb614 257006 test_wavs/3.wav
d46ca3a2ce52b5059f3d84e2cfdc62907e8579faa7078b1becaea614dd41b35d 147216 test_wavs/4.wav
55321b6d1ab4bd9a16928957a52cf943b2ae864543cca7f39e9844b4889e9bb8 132938 test_wavs/5.wav
f5da5689f1960431a53855d7fbe78c9ab0b73e5495ffba631cd43458225d9039 113518 test_wavs/6.wav
6af1addaeb80ddb6dc8576e11dcb5a4d17bc02629e6561ca6b2aadb5ec05e27d 222 test_wavs/test_keywords.txt
72316508d9119696145abc6f1f8cdc46287535c34e5ce7e595f845cb1499cf2e 1627 tokens.txt
"

fetch_model() {
  log "=== model: KWS 唤醒词模型 ==="
  mkdir -p "$ASSETS"
  local want_sha want_size rel out actual_size actual_sha ok
  while read -r want_sha want_size rel; do
    [ -z "${rel:-}" ] && continue
    out="$ASSETS/$rel"
    ok=1
    if [ -f "$out" ]; then
      actual_size=$(wc -c < "$out" | tr -d ' ')
      actual_sha="$(sha256_of "$out")"
      [ "$actual_size" = "$want_size" ] && [ "$actual_sha" = "$want_sha" ] || ok=0
    else
      ok=0
    fi
    if [ "$ok" -eq 1 ]; then
      log "skip (verified) $rel"
      continue
    fi
    rm -f "$out"
    curl_dl "${MODEL_API}?Revision=master&FilePath=${rel}" "$out" "$rel"
    actual_size=$(wc -c < "$out" | tr -d ' ')
    actual_sha="$(sha256_of "$out")"
    if [ "$actual_size" != "$want_size" ] || [ "$actual_sha" != "$want_sha" ]; then
      die "校验失败：$rel (size $actual_size/$want_size, sha ${actual_sha:0:12}/${want_sha:0:12})"
    fi
    log "verified $rel"
  done <<< "$MODEL_MANIFEST"
}

# -----------------------------------------------------------------------------
# 入口
# -----------------------------------------------------------------------------
main() {
  local target="${1:-all}"
  case "$target" in
    all)          fetch_toolchain; fetch_python; fetch_model ;;
    toolchain)    fetch_toolchain ;;
    python)       fetch_python ;;
    model)        fetch_model ;;
    -h|--help|help)
      sed -n '2,17p' "${BASH_SOURCE[0]}"
      ;;
    *) die "未知模块：$target（可选 all|toolchain|python|model）" ;;
  esac
  log "DONE ($target)"
}

main "$@"
