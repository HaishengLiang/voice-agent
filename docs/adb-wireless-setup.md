# Android 设备无线调试：环境、连接方式与自动发现

> 2026-11-22 沉淀。目的：把这次摸索出的设备信息和连接方法固化下来，以后直接按本文操作，不用重新排查。

## 一、设备与环境事实

| 项目 | 值 |
|---|---|
| 设备 | realme RMX3370，Android 13 |
| 序列号（USB） | `f2eb71bb` |
| WLAN IP | `192.168.31.253` |
| 本机 IP（同网段） | `192.168.31.122`（/24） |
| adb 路径 | `I:/voice-agent/.toolchain/android-sdk/platform-tools/adb.exe` |
| Python | `I:/voice-agent/.toolchain/venv/Scripts/python.exe` |

设备已开启「USB 调试」和「WLAN 无线调试」，并做过一次 USB 授权（状态 `unauthorized` → `device`）。

## 二、关键经验（踩过的坑）

1. **adb 不在 PATH**。`where adb` 找不到；全盘 Glob 搜索会超时。用 PowerShell 在几个常见根目录递归找最快：
   ```powershell
   @("C:\Program Files","C:\Users\Administrator\AppData\Local","I:\") | % { Get-ChildItem $_ -Filter adb.exe -Recurse -EA SilentlyContinue }
   ```
   机器上还有其他 adb（SideQuest、iVCam、快斯特宝箱 scrcpy），但本项目统一用 `.toolchain` 下这个。

2. **这个环境的 adb daemon 每条命令后都会重启**（每次输出开头都是 `* daemon not running; starting now`）。后果：`adb connect` 建立的连接不跨命令保留，下一条 `adb -s ... shell` 会报 `device not found`。**必须在同一条命令里 connect + 执行，并在失败时自动重连重试**。

3. **mDNS 广播是间歇性的**。`adb mdns services` 时有时无，不能只靠它发现设备。要有多策略兜底。

4. **两种无线调试端口**：
   - Android 11+ 原生：`_adb-tls-connect._tcp` 广播的**动态端口**（这次是 42971，重启会变）。
   - `adb tcpip 5555` 开的：`_adb._tcp` 广播的**固定端口 5555**。后者更适合做稳定发现目标。

5. **自动发现的正确姿势**：先试 mDNS（重试 4 次），失败则扫本机 /24 网段的 5555 端口（ThreadPoolExecutor 64 并发，0.4s 超时），找到后 `adb connect` 并读序列号校验是不是同一台设备。本机 IP 用 UDP socket 连 `223.5.5.5` 取 `getsockname`，避开 172.x 虚拟网卡。

## 三、日常操作（不用填 IP/端口）

```bash
# 自动发现 + 连接 + 执行任意 adb 命令
./scripts/adb-wireless.sh shell "getprop ro.serialno"
./scripts/adb-wireless.sh install app.apk
./scripts/adb-wireless.sh logcat -d

# 只发现 + 连接 + 打印身份
./scripts/adb-wireless.sh
```

完整链路已验证（2026-11-22 全流程一次跑通）：
```
[+] 发现 1 个候选: ['192.168.31.253:5555']
[+] 已连接  设备: f2eb71bb|RMX3370|13
[身份] f2eb71bb / RMX3370 / Android 13
[电池] level 61  [分辨率] 1080x2400  [已装包] 437
[截图] screencap + pull 成功，1.4 MB 拉到本地
```

## 四、相关脚本

| 文件 | 作用 |
|---|---|
| `scripts/adb-wireless-discover.py` | 核心：双策略发现（mDNS → 网段扫描）、connect、序列号校验、带自动重连的 adb 命令执行 |
| `scripts/adb-wireless.sh` | 薄封装，调用上面的 py，保持 `.sh` 接口习惯 |

## 五、故障排查

- **找不到设备**：确认设备「无线调试」开着；若设备重启过，5555 端口可能没了，需在设备端重新开一次无线调试，或插 USB 执行 `adb tcpip 5555`。
- **连上又掉线**：多半是本机 daemon 重启导致，用脚本（内置重连），别手动拼 adb 命令。
- **设备端 adb 服务整个停掉**（现象：ping 得通、ARP 里有设备，但 5555「积极拒绝」、mDNS 无广播、USB 设备也空）：这是**设备端** adb daemon 停了，通常是休眠或 USB 断开后超时退出。本机脚本救不了，**必须在设备端恢复**：重新打开「开发者选项 > 无线调试」，或重新插 USB 线并执行 `adb tcpip 5555`。2026-11-22 实测：连跑几次后设备端服务会停，此时任何发现策略都无效，别在本机反复重试。
- **Git Bash 路径转换坑**：在 Git Bash 里给 adb 传 `/sdcard/xxx` 会被自动改写成 `.../PortableGit/sdcard/xxx`，导致 `pull` 报 `failed to stat remote object`。规避：调用脚本时用 Windows 风格路径并加 `MSYS_NO_PATHCONV=1` 前缀：
  ```bash
  MSYS_NO_PATHCONV=1 "I:/voice-agent/.toolchain/venv/Scripts/python.exe" \
    "I:/voice-agent/scripts/adb-wireless-discover.py" pull /sdcard/x.png C:/Users/Administrator/AppData/Local/Temp/x.png
  ```
- **扫不到 5555**：确认本机和设备在同一网段；防火墙是否拦了出站连接。
- **IP 变了**：脚本每次运行时从本机 IP 重新算网段、重新扫描，无需改配置。
- **设备刚唤醒时 adb 服务未就绪（2026-11-23 实测）**：设备休眠后 ARP 有记录、能 ping 通，但 `adb mdns services` 空、5555 拒绝连接、全端口扫描只扫到几个无关端口（如 46888/47888，不是 adb）。此时**不要急着选「插 USB」或「配对码」**——先等设备上 adb 服务稳定，再重跑 `adb mdns services`。这次等了一会儿后 mDNS 就广播出真实端点：
  ```
  adb-f2eb71bb       _adb._tcp           192.168.31.253:5555
  adb-f2eb71bb-RKxGxr _adb-tls-connect._tcp 192.168.31.253:39081
  ```
  连 TLS 端口 `39081` 直接 `device` 状态可用（本机密钥已授权过，无需重新 pair）。
- **`_adb-tls-connect._tcp` 端口会变**：每次开关无线调试动态分配（这次 39081），**不要靠扫描常见高端口段去猜**（扫到的一堆开放端口都不是 adb）。唯一可靠来源是 `adb mdns services` 的广播。广播是间歇性的，多试几次。
- **`offline` ≠ 需要配对**：`adb connect` 后状态是 `offline` 通常意味着设备端 adb 服务还没就绪或连错了端口，不是本机缺密钥。真缺密钥时设备端会弹「允许无线调试连接？」授权框，点允许即可。判断顺序：先看 mDNS 广播端点 → 连上再判断 `device`/`offline`/`unauthorized`。

## 六、复用提示

换设备/换项目时，只需确认新 adb 路径和 Python 路径，更新 `adb-wireless-discover.py` 顶部的 `ROOT`/`ADB` 常量即可；发现逻辑和重连机制通用。
