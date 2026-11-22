#!/usr/bin/env python3
"""验证官网 demo（无需 API Key）的全双工语音链路。

流程与官网 JS 客户端一致：
  attach_session → session_attached → mic_state
  → 按 20ms(640B) 推 16kHz PCM16LE → 收二进制 48kHz PCM
  → response.audio.context 配对 → playback.chunk.completed / playback.completed

音频可用 --wav 指定（16k/mono/s16），默认推送静音帧用于连通性验证。
"""
import argparse
import asyncio
import json
import wave
from pathlib import Path

import websockets

URL = "wss://www.moss.cn/api/v1/public/website/moss-live/ws"

ap = argparse.ArgumentParser()
ap.add_argument("--voice", default="female", choices=["female", "male"])
ap.add_argument("--wav", default=None)
ap.add_argument("--seconds", type=float, default=8.0)
ap.add_argument("--frame", type=int, default=640, help="上行帧字节数，官网默认 640(20ms)，Android 40ms=1280")
args = ap.parse_args()

pcm = b""
if args.wav:
    with wave.open(str(Path(args.wav)), "rb") as f:
        sr, ch, sw = f.getframerate(), f.getnchannels(), f.getsampwidth()
        assert sr == 16000 and ch == 1 and sw == 2, (sr, ch, sw)
        pcm = f.readframes(f.getnframes())


async def main() -> int:
    contexts = []       # 每个二进制音频帧对应一个 context
    rx_bytes = 0
    tx_frames = 0
    events = []
    async with websockets.connect(f"{URL}?voice={args.voice}", open_timeout=20) as ws:
        print("HANDSHAKE_OK", flush=True)

        async def send(obj):
            await ws.send(obj if isinstance(obj, str) else json.dumps(obj))

        async def recv_loop():
            nonlocal rx_bytes
            while True:
                raw = await ws.recv()
                if isinstance(raw, bytes):
                    rx_bytes += len(raw)
                    if rx_bytes % (48000 * 2) < 1280:
                        print(f"[AUDIO] rx bytes={rx_bytes}", flush=True)
                    continue
                obj = json.loads(raw)
                action = obj.get("action")
                data = obj.get("data") or {}
                events.append(action)
                if action not in ("input.transcript.updated", "output.transcript.updated"):
                    print("[EVENT]", action, json.dumps(data, ensure_ascii=False)[:200], flush=True)
                if action == "response.audio.context":
                    contexts.append({k: data[k] for k in ("run_id", "turn_id", "chunk_id") if k in data})
                elif action == "session_attached":
                    await send({"action": "mic_state", "enabled": True})
                    print("[SEND] mic_state enabled", flush=True)
                elif action == "response.audio.stop":
                    await send({"action": "playback.stopped", "run_id": data.get("run_id"),
                                "turn_id": data.get("turn_id"), "played_audio_ms": 0})
                elif action == "session.closing":
                    return

        recv_task = asyncio.create_task(recv_loop())
        # 注意：demo 端连上即自动推送 session_attached，不能发 attach_session（会报 invalid_public_request）。
        # 真实 API（api.mosi.cn）才需要 attach_session。
        print("[INFO] 等待服务端 session_attached…", flush=True)

        # 按指定帧大小推送（官网 640B=20ms；Android 端为 1280B=40ms）
        frame = args.frame
        if pcm:
            chunks = [pcm[i:i + frame] for i in range(0, len(pcm), frame)]
        else:
            chunks = [b"\x00" * frame] * int(args.seconds / 0.02)
        for c in chunks:
            if recv_task.done():
                break
            await ws.send(c)
            tx_frames += 1
            await asyncio.sleep(0.02)  # 实时速度
        print(f"[UP] sent {tx_frames} frames ({tx_frames*frame} bytes)", flush=True)

        # 等接收完剩余音频
        await asyncio.sleep(4)
        # 模拟 ACK：为每个收到的 context 回执（实际应由播放器回调触发）
        for ctx in contexts:
            await send({"action": "playback.chunk.completed", **ctx})
        if contexts:
            await send({"action": "playback.completed",
                        "run_id": contexts[-1]["run_id"], "turn_id": contexts[-1]["turn_id"]})
        print(f"[ACK] chunk.completed x{len(contexts)} + playback.completed", flush=True)
        await asyncio.sleep(1)
        recv_task.cancel()
        try:
            await recv_task
        except asyncio.CancelledError:
            pass

    print("\n=== SUMMARY ===")
    print("events:", sorted(set(events)))
    print(f"tx_frames={tx_frames} rx_bytes={rx_bytes} contexts={len(contexts)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
