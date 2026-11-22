#!/usr/bin/env python3
"""探测 MOSS-Live Realtime WebSocket 握手与 session 建立。

用法：
  MOSS_API_KEY=sk-xxx python scripts/probe-moss-live.py [model_id]

密钥只从环境变量读取，不写入脚本或日志。
"""
import asyncio
import json
import os
import sys

import websockets

KEY = os.environ.get("MOSS_API_KEY", "")
MODEL = sys.argv[1] if len(sys.argv) > 1 else "moss-live-1.0"
URL = f"wss://api.mosi.cn/v1/realtime?model={MODEL}"


async def main() -> int:
    if not KEY:
        print("MOSS_API_KEY is empty")
        return 2
    try:
        async with websockets.connect(
            URL,
            additional_headers={"Authorization": f"Bearer {KEY}"},
            open_timeout=25,
        ) as ws:
            print("HANDSHAKE_OK", flush=True)
            await ws.send(json.dumps({"action": "attach_session", "session_id": None}))
            for _ in range(6):
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=20)
                except asyncio.TimeoutError:
                    print("TIMEOUT waiting server event", flush=True)
                    return 1
                text = raw.decode("utf-8", "replace") if isinstance(raw, bytes) else raw
                print("EVENT", text[:1200], flush=True)
                try:
                    obj = json.loads(text)
                except Exception:
                    continue
                if obj.get("action") in ("session_attached", "error"):
                    return 0
            return 0
    except Exception as exc:  # noqa: BLE001
        print(f"CONNECT_FAILED {type(exc).__name__}: {exc}", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
