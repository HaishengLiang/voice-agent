#!/usr/bin/env python3
"""唤醒词灵敏度调参台。

先造语料（多说话人 × 多语速 × 音高/音量漂移），再扫关键词变体、阈值、boost，
直接对比「命中率 / 误唤醒次数」，避免凭感觉改参数。

用法：
  python scripts/tune-kws.py corpus            # 生成语料（edge-tts + ffmpeg，已有则跳过）
  python scripts/tune-kws.py sweep             # 默认扫全部组合
  python scripts/tune-kws.py sweep --sets single,tone --thresholds 0.35,0.25
"""
import argparse
import asyncio
import shutil
import subprocess
import sys
import wave
from itertools import product
from pathlib import Path

import numpy as np

ROOT = Path("I:/voice-agent")
ASSETS = ROOT / "xiaomiao-voice-agent/app/src/main/assets/kws"
WORK = ROOT / ".toolchain/kws-tune"
CORPUS = WORK / "corpus"
POS, NEG = CORPUS / "pos", CORPUS / "neg"
KW_DIR = WORK / "keywords"
PY = ROOT / ".toolchain/venv/Scripts/python.exe"

ENCODER = ASSETS / "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
DECODER = ASSETS / "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
JOINER = ASSETS / "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
TOKENS = ASSETS / "tokens.txt"

# ── 关键词变体：同一句话的不同发音路径（声调漂移） ───────────────────────────
# 小喵小喵 = x iǎo m iāo ×2；iǎo/iāo/iáo/iào 都在 tokens.txt 里，可以自由替换。
BASE = "x iǎo m iāo x iǎo m iāo"
SHORT = "x iǎo m iāo"
KEYWORD_SETS = {
    # 现状：单个关键词，最省
    "single": [BASE],
    # 声调漂移：把「小」的第三声、「喵」的第一声各自替换，覆盖含糊/快读时的变调
    "tone": [
        BASE,
        "x iāo m iāo x iāo m iāo",
        "x iǎo m iáo x iǎo m iáo",
        "x iǎo m iǎo x iǎo m iǎo",
    ],
    # 半截唤醒：额外放一个两音节「小喵」，专门救说得又快又连贯、后半截被吞掉的情况
    "short": [BASE, SHORT],
    # 组合：变体 + 半截
    "full": [
        BASE,
        "x iāo m iāo x iāo m iāo",
        "x iǎo m iáo x iǎo m iáo",
        "x iǎo m iǎo x iǎo m iǎo",
        SHORT,
    ],
}

VOICES = [
    "zh-CN-XiaoxiaoNeural",     # 女，标准
    "zh-CN-XiaoyiNeural",       # 女，偏活泼
    "zh-CN-YunxiNeural",        # 男，年轻
    "zh-CN-YunjianNeural",      # 男，浑厚
    "zh-CN-YunyangNeural",      # 男，新闻播报
    "zh-CN-liaoning-XiaobeiNeural",  # 东北官话
    "zh-CN-shaanxi-XiaoniNeural",    # 中原官话
]
RATES = ["-25%", "-12%", "+0%", "+12%", "+25%"]
NEG_PHRASES = ["小喵", "喵喵", "小妙小妙", "小苗小苗", "喵小喵", "小米小爱", "今天天气不错我们出去走走吧"]


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", *args], check=True)


def to_wav16k(src: Path, dst: Path) -> None:
    ffmpeg("-i", str(src), "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(dst))


async def _tts(text: str, voice: str, rate: str, out: Path) -> None:
    import edge_tts
    mp3 = out.with_suffix(".mp3")
    await edge_tts.Communicate(text, voice, rate=rate).save(str(mp3))
    to_wav16k(mp3, out)
    mp3.unlink(missing_ok=True)


def build_corpus() -> None:
    POS.mkdir(parents=True, exist_ok=True)
    NEG.mkdir(parents=True, exist_ok=True)
    jobs = []  # (voice, text, rate, out)
    for voice, rate in product(VOICES, RATES):
        name = f"pos_{voice.split('-')[2].lower()}_{rate.strip('+').replace('%', '')}.wav"
        out = POS / name
        if not out.exists():
            jobs.append((voice, "小喵小喵", rate, out))
    for i, phrase in enumerate(NEG_PHRASES):
        for voice in ("zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural"):
            out = NEG / f"neg_{i}_{voice.split('-')[2].lower()}.wav"
            if not out.exists():
                jobs.append((voice, phrase, "+0%", out))
    if jobs:
        print(f"[corpus] 合成 {len(jobs)} 条…")
        asyncio.run(_synth_all(jobs))
    else:
        print("[corpus] TTS 样本已存在")

    # 音高漂移（不改变语速）：模拟不同声道/情绪下的音色偏移
    for base in sorted(POS.glob("pos_xiaoxiaoneural_0.wav")) + sorted(POS.glob("pos_yunxineural_0.wav")):
        for semis, tag in ((1.06, "up"), (0.94, "down")):
            out = POS / f"{base.stem}_pitch{tag}.wav"
            if not out.exists():
                ffmpeg("-i", str(base), "-filter:a", f"asetrate=16000*{semis},aresample=16000,atempo={1/semis:.6f}",
                       "-ac", "1", "-c:a", "pcm_s16le", str(out))

    # 音量衰减：模拟远场 / 轻声说话（-12dB、-18dB）
    for base in sorted(POS.glob("pos_*_0.wav"))[:4]:
        for gain, tag in ((0.25, "quiet12"), (0.125, "quiet18")):
            out = POS / f"{base.stem}_{tag}.wav"
            if not out.exists():
                ffmpeg("-i", str(base), "-filter:a", f"volume={gain}", "-c:a", "pcm_s16le", str(out))

    # 真实录音的负样本（官方 KWS 测试集，中文句子，不含唤醒词）
    extra = ROOT / ".toolchain/kws-test/test_wavs"
    for wav in sorted(extra.glob("*.wav")):
        out = NEG / f"real_{wav.stem}.wav"
        if not out.exists():
            to_wav16k(wav, out)

    print(f"[corpus] pos={len(list(POS.glob('*.wav')))}  neg={len(list(NEG.glob('*.wav')))}")


async def _synth_all(jobs):
    import edge_tts
    proxy = "http://127.0.0.1:7890" if _proxy_alive() else None
    for voice, text, rate, out in jobs:
        mp3 = out.with_suffix(".mp3")
        for attempt in range(3):
            try:
                await edge_tts.Communicate(text, voice, rate=rate, proxy=proxy).save(str(mp3))
                break
            except Exception as e:  # 网络抖动，重试
                if attempt == 2:
                    print(f"  跳过 {out.name}: {type(e).__name__}")
                    mp3.unlink(missing_ok=True)
                    continue
                await asyncio.sleep(1.5)
        if not mp3.exists():
            continue
        to_wav16k(mp3, out)
        mp3.unlink(missing_ok=True)
        print("  ", out.name)


def _proxy_alive() -> bool:
    import socket
    s = socket.socket()
    s.settimeout(0.3)
    try:
        s.connect(("127.0.0.1", 7890))
        return True
    except OSError:
        return False
    finally:
        s.close()


def read_wav(path: Path) -> tuple[int, np.ndarray]:
    with wave.open(str(path), "rb") as f:
        sr = f.getframerate()
        pcm = np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    return sr, pcm


def write_keywords(path: Path, tokens_lines: list[str], threshold: float) -> None:
    path.write_text("\n".join(f"{t} :1.0 #{threshold} @唤醒" for t in tokens_lines) + "\n", encoding="utf8")


def evaluate(keywords_file: Path, score: float, threshold: float, max_paths: int,
             wavs: list[Path], normalize: bool) -> tuple[int, list[str]]:
    import sherpa_onnx
    spotter = sherpa_onnx.KeywordSpotter(
        tokens=str(TOKENS), encoder=str(ENCODER), decoder=str(DECODER), joiner=str(JOINER),
        keywords_file=str(keywords_file), keywords_score=score, keywords_threshold=threshold,
        max_active_paths=max_paths, num_threads=2, provider="cpu",
    )
    hits, names = 0, []
    for wav in wavs:
        sr, pcm = read_wav(wav)
        if normalize:
            pcm = normalize_stream(pcm, sr)
        tail = np.zeros(int(0.66 * sr), dtype=np.float32)
        s = spotter.create_stream()
        s.accept_waveform(sr, pcm)
        s.accept_waveform(sr, tail)
        s.input_finished()
        found = False
        while spotter.is_ready(s):
            spotter.decode_stream(s)
            if spotter.get_result(s):
                found = True
                break
        if found:
            hits += 1
            names.append(wav.name)
    del spotter
    return hits, names


def normalize_stream(pcm: np.ndarray, sr: int, frame: int = 640) -> np.ndarray:
    """流式 AGC：按 40ms 帧估峰值，把语音段拉到约 0.5 满幅，静音段不动。"""
    out = pcm.copy()
    gain = 1.0
    for i in range(0, len(pcm), frame):
        seg = pcm[i:i + frame]
        peak = float(np.max(np.abs(seg))) if len(seg) else 0.0
        if peak > 0.02:  # 有语音才动增益，避免把底噪抬高
            target = min(4.0, max(1.0, 0.5 / peak))
            gain = 0.7 * gain + 0.3 * target
        out[i:i + frame] = np.clip(seg * gain, -1.0, 1.0)
    return out


def sweep(args) -> None:
    KW_DIR.mkdir(parents=True, exist_ok=True)
    pos_wavs = sorted(POS.glob("*.wav"))
    neg_wavs = sorted(NEG.glob("*.wav"))
    if not pos_wavs:
        sys.exit("先跑 corpus")
    sets = args.sets.split(",")
    thresholds = [float(t) for t in args.thresholds.split(",")]
    scores = [float(s) for s in args.scores.split(",")]
    modes = [m == "agc" for m in args.modes.split(",")]

    print(f"正样本 {len(pos_wavs)} 条，负样本 {len(neg_wavs)} 条\n")
    rows = []
    for set_name, threshold, score, agc in product(sets, thresholds, scores, modes):
        lines = KEYWORD_SETS[set_name]
        kw = KW_DIR / f"{set_name}_{threshold}_{score}.txt"
        write_keywords(kw, lines, threshold)
        tp, _ = evaluate(kw, score, threshold, 8, pos_wavs, agc)
        fa, fa_names = evaluate(kw, score, threshold, 8, neg_wavs, agc)
        rows.append((set_name, threshold, score, "agc" if agc else "raw", tp, fa, fa_names))
        print(f"{set_name:6} thr={threshold:.2f} boost={score:.1f} {'agc' if agc else 'raw'} "
              f"命中 {tp:2}/{len(pos_wavs)}  误唤醒 {fa}/{len(neg_wavs)}")

    print("\n=== 按命中率排序（同分看误唤醒） ===")
    for r in sorted(rows, key=lambda x: (-x[4], x[5]))[:12]:
        fa = ",".join(r[6][:3]) or "-"
        print(f"{r[0]:6} thr={r[1]:.2f} boost={r[2]:.1f} {r[3]:4} → {r[4]}/{len(pos_wavs)}  误{r[5]}  {fa}")


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("corpus")
    sp = sub.add_parser("sweep")
    sp.add_argument("--sets", default="single,tone,short,full")
    sp.add_argument("--thresholds", default="0.35,0.30,0.25,0.20,0.15")
    sp.add_argument("--scores", default="1.0")
    sp.add_argument("--modes", default="raw")
    args = ap.parse_args()
    if args.cmd == "corpus":
        build_corpus()
    else:
        sweep(args)


if __name__ == "__main__":
    main()
