#!/usr/bin/env python3
"""自动发现并连接 Android 无线调试设备，全程无需手填 IP/端口。

发现策略（按优先级）：
  1. adb mdns services —— 读取设备通过 _adb-tls-connect._tcp / _adb._tcp 广播的地址
  2. 网段扫描 —— 取本机 IP 的 /24 网段，并发探测 5555 端口（adb tcpip 开的）

找到端点后自动 adb connect，校验设备序列号，然后执行传入的 adb 子命令。

用法:
  python scripts/adb-wireless-discover.py                       # 只发现+连接+打印身份
  python scripts/adb-wireless-discover.py shell "getprop ro.serialno"
  python scripts/adb-wireless-discover.py install app.apk
"""
import os
import re
import socket
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.path.join(ROOT, ".toolchain", "android-sdk", "platform-tools", "adb.exe")
if not os.path.exists(ADB):
    ADB = os.path.join(ROOT, ".toolchain", "android-sdk", "platform-tools", "adb")


def run_adb(*args, timeout=15):
    try:
        r = subprocess.run([ADB, *args], capture_output=True, text=True,
                           timeout=timeout, errors="replace")
        return r.stdout + r.stderr
    except Exception as e:
        return str(e)


def local_ip():
    """拿到本机连接外网的网卡 IP（非 127/172 虚拟网卡）。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("223.5.5.5", 80))
        return s.getsockname()[0]
    except Exception:
        return None
    finally:
        s.close()


def discover_mdns():
    """策略1：mDNS 广播，尝试多次（广播有间隔）。"""
    found = []
    for _ in range(4):
        out = run_adb("mdns services", timeout=10)
        for line in out.splitlines():
            m = re.search(r"(_adb(?:-tls-connect)?\._tcp)\s+(\d+\.\d+\.\d+\.\d+):(\d+)", line)
            if m:
                ep = f"{m.group(2)}:{m.group(3)}"
                # TLS 原生端口优先
                score = 0 if "tls-connect" in m.group(1) else 1
                found.append((score, ep, m.group(1)))
        if found:
            break
    found.sort()
    return list(dict.fromkeys((ep, kind) for _, ep, kind in found))


def probe_port(ip, port=5555, timeout=0.4):
    """探测 TCP 端口是否开放。"""
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return ip
    except Exception:
        return None


def discover_scan(ip=None):
    """策略2：扫本机 /24 网段的 5555 端口。"""
    ip = ip or local_ip()
    if not ip:
        return []
    subnet = ".".join(ip.split(".")[:3])
    targets = [f"{subnet}.{i}" for i in range(1, 255) if f"{subnet}.{i}" != ip]
    hits = []
    with ThreadPoolExecutor(max_workers=64) as ex:
        futures = {ex.submit(probe_port, t): t for t in targets}
        for f in as_completed(futures):
            r = f.result()
            if r:
                hits.append(f"{r}:5555")
    return hits


def connect(endpoint):
    out = run_adb("connect", endpoint)
    return "connected" in out.lower(), out.strip()


def identity(endpoint):
    out = exec_adb(endpoint, "shell",
                   'printf "%s|%s|%s" "$(getprop ro.serialno)" "$(getprop ro.product.model)" "$(getprop ro.build.version.release)"',
                   timeout=10)
    return out.strip().replace("\r", "").replace("\n", "")


def exec_adb(endpoint, *args, timeout=60):
    """带自动重连的命令执行：daemon 重启导致连接丢失时重新 connect 并重试。"""
    last = ""
    for attempt in range(4):
        r = subprocess.run([ADB, "-s", endpoint, *args], capture_output=True,
                           text=True, timeout=timeout, errors="replace")
        out = r.stdout + r.stderr
        if r.returncode == 0 and "not found" not in out.lower():
            return out
        last = out
        # 连接失效 -> 重连
        if "not found" in out.lower() or "offline" in out.lower() or "daemon" in out.lower():
            run_adb("connect", endpoint)
        else:
            break
    return last


def main():
    print("[*] 策略1: mDNS 广播发现 ...", file=sys.stderr)
    endpoints = discover_mdns()

    if not endpoints:
        ip = local_ip()
        print(f"[*] mDNS 无结果，策略2: 扫描 {ip}/24 的 5555 端口 ...", file=sys.stderr)
        endpoints = [(ep, "scan") for ep in discover_scan(ip)]

    if not endpoints:
        print("[!] 未发现任何无线调试设备。请确认设备开启「无线调试」或执行过 adb tcpip 5555。", file=sys.stderr)
        sys.exit(1)

    print(f"[+] 发现 {len(endpoints)} 个候选: {[e for e, _ in endpoints]}", file=sys.stderr)

    connected = None
    for ep, kind in endpoints:
        ok, out = connect(ep)
        if ok:
            ident = identity(ep)
            print(f"[+] 已连接 {ep}  设备: {ident}", file=sys.stderr)
            connected = ep
            break
        else:
            print(f"[-] {ep} 连接失败: {out}", file=sys.stderr)

    if not connected:
        print("[!] 所有候选均连接失败", file=sys.stderr)
        sys.exit(1)

    # 执行后续 adb 子命令；无参数则只打印连接信息
    if len(sys.argv) > 1:
        out = exec_adb(connected, *sys.argv[1:])
        sys.stdout.write(out)
        # 把子命令的退出码透传出去
        if "not found" in out.lower() or "error:" in out.lower():
            sys.exit(1)


if __name__ == "__main__":
    main()
