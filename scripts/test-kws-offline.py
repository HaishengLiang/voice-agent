#!/usr/bin/env python3
"""离线验证 sherpa-onnx KWS 关键词检测效果。

用法：
  python scripts/test-kws-offline.py
  python scripts/test-kws-offline.py --keywords <自定义keywords.txt> --wav <测试.wav>

按官方示例：整段喂入 + 0.66s 尾部静音 + input_finished + while is_ready 解码。
"""
import argparse
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

BASE = Path("I:/voice-agent/xiaomiao-voice-agent/app/src/main/assets/kws")
TEST_WAVS = Path("I:/voice-agent/.toolchain/kws-test/test_wavs")

ap = argparse.ArgumentParser()
ap.add_argument("--keywords", default=str(TEST_WAVS / "test_keywords.txt"))
ap.add_argument("--wav", action="append", default=None)
args = ap.parse_args()

spotter = sherpa_onnx.KeywordSpotter(
    tokens=str(BASE / "tokens.txt"),
    encoder=str(BASE / "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
    decoder=str(BASE / "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
    joiner=str(BASE / "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
    keywords_file=args.keywords,
    num_threads=2,
    provider="cpu",
)

wavs = [Path(p) for p in args.wav] if args.wav else sorted(TEST_WAVS.glob("[0-9].wav"))
print(f"keywords={args.keywords}  wavs={len(wavs)}")
hit = 0
for wav in wavs:
    with wave.open(str(wav), "rb") as f:
        sr, ch, sw, n = f.getframerate(), f.getnchannels(), f.getsampwidth(), f.getnframes()
        pcm = np.frombuffer(f.readframes(n), dtype=np.int16).astype(np.float32) / 32768
    tail = np.zeros(int(0.66 * sr), dtype=np.float32)
    s = spotter.create_stream()
    s.accept_waveform(sr, pcm)
    s.accept_waveform(sr, tail)
    s.input_finished()
    found = []
    while spotter.is_ready(s):
        spotter.decode_stream(s)
        r = spotter.get_result(s)
        if r:
            found.append(r)
            spotter.reset_stream(s)
    result = " | ".join(found)
    if result:
        hit += 1
    print(f"{wav.name}: {result or '(no hit)'}")
print(f"TOTAL_HIT={hit}/{len(wavs)}")
