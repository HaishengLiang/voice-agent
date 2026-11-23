#!/usr/bin/env python3
"""从真机拉取 JaCoCo coverage.ec（app 私有目录，需 run-as，二进制安全）。

coverage.ec 写在 /data/user/0/<pkg>/files/coverage.ec，adb pull 无权限；
debug APK 是 debuggable，用 `adb exec-out run-as <pkg> cat files/coverage.ec` 读二进制流。
复用 adb-wireless-discover 的发现/连接逻辑（daemon 重启坑）。

用法:
  python scripts/pull-coverage.py <本地输出路径>
"""
import importlib.util
import os
import subprocess
import sys

# 模块文件名带连字符（adb-wireless-discover.py），无法用普通 import，按路径加载
_HERE = os.path.dirname(os.path.abspath(__file__))
_SPEC = importlib.util.spec_from_file_location("discover", os.path.join(_HERE, "adb-wireless-discover.py"))
d = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(d)

PKG = "com.example.xiaomiao"
REMOTE = "files/coverage.ec"


def main():
    if len(sys.argv) < 2:
        print("用法: pull-coverage.py <本地输出路径>", file=sys.stderr)
        sys.exit(1)
    out_path = sys.argv[1]

    endpoints = d.discover_mdns()
    if not endpoints:
        endpoints = [(ep, "scan") for ep in d.discover_scan(d.local_ip())]
    if not endpoints:
        print("[!] 未发现设备", file=sys.stderr)
        sys.exit(1)

    connected = None
    for ep, _ in endpoints:
        ok, _ = d.connect(ep)
        if ok:
            connected = ep
            break
    if not connected:
        print("[!] 连接失败", file=sys.stderr)
        sys.exit(1)

    # exec-out 保留二进制原样（run-as 读 app 私有目录）
    data = None
    for attempt in range(4):
        r = subprocess.run(
            [d.ADB, "-s", connected, "exec-out", "run-as", PKG, "cat", REMOTE],
            capture_output=True, timeout=60,
        )
        # 连接失效则重连重试
        low = (r.stderr or b"").decode("utf-8", "ignore").lower()
        if r.returncode == 0 and r.stdout and "not found" not in low and "denied" not in low:
            data = r.stdout
            break
        if any(k in low for k in ("not found", "offline", "daemon")):
            d.connect(connected)
        else:
            print(f"[!] 读取失败: {low[:200]}", file=sys.stderr)
            break

    if not data:
        print("[!] 未能读取 coverage.ec", file=sys.stderr)
        sys.exit(1)

    # 注意：AGP 8.x 通过 AndroidJUnitRunner 写出的 ec 可能带自定义头
    # （实测前 8 字节 01 c0 c0 10 ... unknownhost），不是经典 JaCoCo 魔数 ab 01 01 01。
    # 不在拉取端做格式校验，交给 JaCoCo/Gradle 报告步骤判断。
    print(f"[i] ec 前 8 字节: {data[:8].hex()}")

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(data)
    print(f"[+] 拉取成功: {out_path} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
