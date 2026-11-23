#!/usr/bin/env python3
"""真机验证「运行时唤醒词替换」完整链路。

主页 -> 设置 -> 基本设置 -> 结束对话(回唤醒态) -> 输入新词 -> 应用 -> 读词表验证 -> KWS 自检
坐标全部由 uiautomator 控件树动态计算；每条 adb 命令带自动重连（本机 daemon 每条命令后重启）。
用法：python scripts/verify-wake-swap.py [新唤醒词]   默认「你好小智」
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"I:/voice-agent/.toolchain/android-sdk/platform-tools/adb.exe"
EP = "192.168.31.253:5555"
NEW_WORD = sys.argv[1] if len(sys.argv) > 1 else "你好小智"


def raw(*args, timeout=25):
    r = subprocess.run([ADB, *args], capture_output=True,
                       text=True, timeout=timeout, errors="replace")
    return (r.stdout + r.stderr).strip()


def adb(*args, timeout=25):
    """带自动重连的 adb 命令（daemon 每条命令后重启，连接不跨命令保留）。"""
    out = ""
    for _ in range(3):
        out = raw("-s", EP, *args, timeout=timeout)
        low = out.lower()
        if "device not found" not in low and "not found" not in low and "offline" not in low:
            return out
        raw("connect", EP, timeout=15)
        time.sleep(0.4)
    return out


def dump():
    adb("shell", "uiautomator dump /sdcard/_u.xml")
    return adb("shell", "cat /sdcard/_u.xml").strip()


def find(xml, rid):
    for n in ET.fromstring(xml).iter("node"):
        if n.get("resource-id", "").endswith("/" + rid):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                return n.get("text", ""), (x1 + x2) // 2, (y1 + y2) // 2
    return None


def tap(xy):
    adb("shell", f"input tap {xy[0]} {xy[1]}")
    time.sleep(0.7)


def wait_for(rid, tries=10, delay=0.6):
    end = None
    for _ in range(tries):
        xml = dump()
        end = find(xml, rid)
        if end:
            return xml
        time.sleep(delay)
    return dump()


def log(msg):
    print(msg, flush=True)


def keywords():
    return adb("shell", "run-as com.example.xiaomiao cat files/kws/keywords.txt 2>&1")


def main():
    log("[*] 连接: " + raw("connect", EP))
    log(f"[*] 目标唤醒词: {NEW_WORD}")
    log(f"[*] 改词前词表:\n{keywords()}")

    xml = dump()

    # 主页 -> 设置齿轮
    if find(xml, "settingsButton"):
        log("[*] 当前在主页，点设置齿轮")
        tap(find(xml, "settingsButton")[1:])
        xml = wait_for("tabBasic", tries=10)

    # 兜底：直接启动设置页
    if not find(xml, "tabBasic"):
        log("[*] 未能进入设置页，直接 am start SettingsActivity")
        adb("shell", "am start -n com.example.xiaomiao/.SettingsActivity")
        xml = wait_for("tabBasic", tries=10)

    if not find(xml, "tabBasic"):
        rids = [n.get("resource-id") for n in ET.fromstring(xml).iter("node")]
        raise RuntimeError("找不到基本设置 Tab，当前控件: " + str(rids))

    # 切到基本设置
    tap(find(xml, "tabBasic")[1:])
    xml = dump()

    # 会话中 -> 先结束对话
    state = find(xml, "stateText")
    log(f"[*] 状态: {state[0] if state else '未知'}")
    wbtn = find(xml, "wakeButton")
    if wbtn and wbtn[0] == "结束对话":
        log("[*] 会话中 -> 点『结束对话』回唤醒态")
        tap(wbtn[1:])
        xml = dump()
        state = find(xml, "stateText")
        log(f"[*] 操作后状态: {state[0] if state else '未知'}")

    box = find(xml, "wakeWordInput")
    if not box:
        raise RuntimeError("找不到唤醒词输入框")
    log(f"[*] 原唤醒词: {box[0]}")
    tap(box[1:])

    # 清空输入框（一次调用发多个 DEL）
    adb("shell", "input keyevent " + " ".join(["67"] * 16))
    time.sleep(0.3)

    # 输入中文
    adb("shell", f"input text {NEW_WORD}")
    time.sleep(0.8)
    xml = dump()
    box = find(xml, "wakeWordInput")
    typed = box[0] if box else "<无>"
    log(f"[*] 输入框内容: {typed!r}")
    if typed.strip() != NEW_WORD:
        raise RuntimeError(f"中文输入失败（得到 {typed!r}），需改用剪贴板/IME 注入")

    apply = find(xml, "saveWakeWordButton")
    if not apply:
        raise RuntimeError("找不到应用唤醒词按钮")
    log(f"[*] 点『{apply[0]}』")
    tap(apply[1:])
    time.sleep(1.5)

    log(f"[*] 改词后词表:\n{keywords()}")
    xml = dump()
    status = find(xml, "wakeWordStatus")
    log(f"[*] 界面提示: {status[0] if status else '未知'}")

    self = find(xml, "selftestButton")
    if self:
        log(f"[*] 点『{self[0]}』")
        tap(self[1:])
        time.sleep(3)
        xml = dump()
        for rid in ("stateText", "wakeWordStatus", "selftestResult"):
            r = find(xml, rid)
            if r:
                log(f"[*] {rid}: {r[0]}")


if __name__ == "__main__":
    main()
