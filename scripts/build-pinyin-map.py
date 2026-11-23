#!/usr/bin/env python3
"""生成「汉字 -> ppinyin token 序列」映射表，供 App 端运行时把中文唤醒词转成音素。

背景：sherpa-onnx 的 Android AAR 不含 text2token/pinyin 能力，中文转音素原本靠
PC 端 sherpa-onnx-cli + pypinyin。本脚本把这一步前移到构建期，产出一张查表，
App 里只做「逐字查表 + 拼接」，实现不重装 APK 也能换唤醒词。

原理与 sherpa-onnx 官方 text2token 一致（见 kws-wake-tuning.md）：
  汉字 --pypinyin(带声调)--> 拼音 --to_initials/to_finals_tone--> 声母 + 带调韵母 token

多音字处理：pypinyin 的 heteronym=True 会给出全部读音。默认只取「常用音」
（heteronym 列表第一个）保证词组自然；但为兜住唤醒词里恰用次读音的情况，
可选生成变体行（--variants），让 sherpa 的多路径机制去匹配。

输出格式（assets/kws/pinyin_map.txt，UTF-8，每行一个汉字）：
  小=x iǎo|x iào
  喵=m iāo
即「汉字=读音1 token 序列|读音2 token 序列|...」，token 之间空格分隔。
App 端按行 split('=') 建表，值再 split('|')。

用法：
  I:/voice-agent/.toolchain/venv/Scripts/python.exe scripts/build-pinyin-map.py
"""
import argparse
import sys
from pathlib import Path

try:
    from pypinyin import pinyin, Style
    from pypinyin.contrib.tone_convert import to_initials, to_finals_tone
    from pypinyin import pinyin_dict
except ImportError as e:
    sys.exit(f"缺少 pypinyin，请用 .toolchain/venv 的 python 运行：{e}")

ROOT = Path(__file__).resolve().parents[1]
TOKENS_FILE = ROOT / "xiaomiao-voice-agent/app/src/main/assets/kws/tokens.txt"
OUT_FILE = ROOT / "xiaomiao-voice-agent/app/src/main/assets/kws/pinyin_map.txt"


def load_tokens(path: Path) -> set[str]:
    tokens = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.rsplit(" ", 1)
        tokens.add(parts[0].strip())
    return tokens


def py_to_tokens(py: str, tokens: set[str]) -> list[str] | None:
    """把一个带声调拼音拆成 sherpa ppinyin token 序列；含 OOV 则返回 None。"""
    initial = to_initials(py, strict=False)
    final = to_finals_tone(py, strict=False)
    seq = []
    if initial:
        seq.append(initial)
    if final:
        seq.append(final)
    if not seq:
        return None
    if any(t not in tokens for t in seq):
        return None
    return seq


def get_chars() -> list[str]:
    """取 pypinyin 词典覆盖的全部汉字（约 2 万常用+次常用字）。"""
    chars = set()
    for key in pinyin_dict.pinyin_dict.keys():
        cp = key if isinstance(key, int) else ord(key)
        if 0x4E00 <= cp <= 0x9FFF:  # CJK 统一汉字基本区
            chars.add(chr(cp))
    return sorted(chars)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--variants", action="store_true",
                    help="同时输出多音字的所有读音（默认只取常用音，值更小更稳）")
    args = ap.parse_args()

    tokens = load_tokens(TOKENS_FILE)
    chars = get_chars()
    print(f"[map] tokens={len(tokens)} chars={len(chars)} variants={args.variants}")

    lines = []
    skipped = 0
    for ch in chars:
        readings = pinyin(ch, heteronym=True)[0]  # list[str]，第一个是常用音
        if not args.variants:
            readings = readings[:1]
        seqs: list[str] = []
        seen = set()
        for py in readings:
            toks = py_to_tokens(py, tokens)
            if not toks:
                continue
            joined = " ".join(toks)
            if joined not in seen:
                seen.add(joined)
                seqs.append(joined)
        if not seqs:
            skipped += 1
            continue
        lines.append(f"{ch}={'|'.join(seqs)}")

    OUT_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")
    size = OUT_FILE.stat().st_size
    print(f"[map] 写入 {len(lines)} 字，跳过 {skipped} 字（读音 token 不在词表）")
    print(f"[map] 输出: {OUT_FILE}  ({size / 1024:.1f} KB)")

    # 抽样自检：确认与 text2token 口径一致
    for ch in "小喵你好智":
        hit = next((l for l in lines if l.startswith(ch + "=")), None)
        print(f"  {ch}: {hit}")


if __name__ == "__main__":
    main()
