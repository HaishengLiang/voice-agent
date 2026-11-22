#!/usr/bin/env python3
"""把猫头插画生成 Android app 图标（传统圆角 + 圆形 + 自适应图标）。

依赖: numpy + ffmpeg（项目 .toolchain/venv 已具备 numpy，ffmpeg 在系统 PATH）。
用法: python scripts/make-app-icon.py [源图路径]
"""
import json
import os
import subprocess
import sys

import numpy as np

SRC = sys.argv[1] if len(sys.argv) > 1 else (
    r"C:/Users/Administrator/.workbuddy/clipboard-images/"
    r"clipboard-2026-11-22T10-48-46-726Z-e9dd912a.jpg"
)
RES = r"I:/voice-agent/xiaomiao-voice-agent/app/src/main/res"
PREVIEW = r"I:/voice-agent/xiaomiao-voice-agent/app-icon-preview.png"
# 背景米色（取图片四角），自适应图标背景层同色，视觉一体
BG = (253, 237, 205)

# 密度 -> (传统图标边长px, 自适应图标画布边长px)
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}
FG_RATIO = 0.78      # 自适应前景中猫图占画布比例（让猫脸落进中间安全区）
CORNER_RATIO = 0.22  # 传统图标圆角半径比例


def run(cmd, inp=None):
    r = subprocess.run(["ffmpeg", "-v", "error"] + cmd,
                       input=inp, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode(errors="ignore"))
    return r.stdout


def load_rgb(path):
    info = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height", "-of", "json", path],
        capture_output=True).stdout
    d = json.loads(info)["streams"][0]
    raw = run(["-i", path, "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"])
    return np.frombuffer(raw, dtype=np.uint8).reshape(d["height"], d["width"], 3)


def to_bytes(arr):
    return np.ascontiguousarray(arr).tobytes()


def center_crop_scale(arr, target):
    """居中裁成正方形再缩放到 target×target，返回 RGB 数组。"""
    h, w = arr.shape[:2]
    side = min(w, h)
    x0, y0 = (w - side) // 2, (h - side) // 2
    crop = arr[y0:y0 + side, x0:x0 + side]
    out = run(
        ["-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{side}x{side}",
         "-i", "pipe:0", "-vf", f"scale={target}:{target}:flags=lanczos",
         "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"],
        inp=to_bytes(crop))
    return np.frombuffer(out, dtype=np.uint8).reshape(target, target, 3)


def alpha_mask(size, radius, shape):
    """抗锯齿 alpha 蒙版（0..1）。shape: circle / roundrect。"""
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    if shape == "circle":
        cx = cy = (size - 1) / 2.0
        dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
        a = radius - dist + 0.5
    else:
        r = radius
        dx = np.maximum(np.maximum(r - xx, xx - (size - 1 - r)), 0)
        dy = np.maximum(np.maximum(r - yy, yy - (size - 1 - r)), 0)
        dist = np.sqrt(dx * dx + dy * dy)
        a = r - dist + 0.5
    return np.clip(a, 0, 1)


def save_png(rgba, path):
    h, w = rgba.shape[:2]
    run(["-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", f"{w}x{h}",
         "-i", "pipe:0", "-frames:v", "1", path], inp=to_bytes(rgba))


def with_alpha(rgb, mask):
    return np.dstack([rgb, np.clip(mask * 255, 0, 255).astype(np.uint8)])


def solid(size, rgb):
    a = np.zeros((size, size, 4), dtype=np.uint8)
    a[:, :, 0], a[:, :, 1], a[:, :, 2] = rgb
    a[:, :, 3] = 255
    return a


def main():
    img = load_rgb(SRC)
    print(f"源图 {img.shape[1]}x{img.shape[0]}, 背景 {BG}")

    # 圆角预览
    big = center_crop_scale(img, 512)
    save_png(with_alpha(big, alpha_mask(512, 512 * CORNER_RATIO, "roundrect")),
             PREVIEW)
    print("预览:", PREVIEW)

    for dens, (legacy, adaptive) in DENSITIES.items():
        d = os.path.join(RES, f"mipmap-{dens}")
        os.makedirs(d, exist_ok=True)
        icon = center_crop_scale(img, legacy)

        # 传统圆角 / 圆形图标
        save_png(with_alpha(icon, alpha_mask(legacy, legacy * CORNER_RATIO,
                                             "roundrect")),
                 os.path.join(d, "ic_launcher.png"))
        save_png(with_alpha(icon, alpha_mask(legacy, legacy / 2.0, "circle")),
                 os.path.join(d, "ic_launcher_round.png"))

        # 自适应前景：猫图缩到 FG_RATIO 居中，四周透明
        cat = center_crop_scale(img, adaptive)
        cat_sz = int(adaptive * FG_RATIO)
        out = run(
            ["-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{adaptive}x{adaptive}",
             "-i", "pipe:0", "-vf", f"scale={cat_sz}:{cat_sz}:flags=lanczos",
             "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"], inp=to_bytes(cat))
        cat_small = np.frombuffer(out, dtype=np.uint8).reshape(cat_sz, cat_sz, 3)
        fg = np.zeros((adaptive, adaptive, 4), dtype=np.uint8)
        off = (adaptive - cat_sz) // 2
        fg[off:off + cat_sz, off:off + cat_sz, :3] = cat_small
        fg[off:off + cat_sz, off:off + cat_sz, 3] = 255
        save_png(fg, os.path.join(d, "ic_launcher_foreground.png"))

        # 自适应背景：纯米色
        save_png(solid(adaptive, BG),
                 os.path.join(d, "ic_launcher_background.png"))
        print(f"  {dens}: legacy={legacy} adaptive={adaptive} ok")


if __name__ == "__main__":
    main()
