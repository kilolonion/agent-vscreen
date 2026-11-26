"""Check the real Shizuku manager dialog and Binder state through Android's UI."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

APP = "com.kilon.agentvscreen"
MANAGER = "moe.shizuku.privileged.api"
APKS = Path("test-apks")
MODE = sys.argv[1]


def run(*argv, **kw):
    return subprocess.check_output(argv, text=True, timeout=75, **kw).strip()


def shell(*args):
    return run("adb", "shell", *args)


def check_state(expected):
    for _ in range(45):
        lines = shell("logcat", "-d", "-s", "ShizukuManager:I")
        states = re.findall(r"state=(\w+)", lines)
        if states and states[-1] == expected:
            print("PASS app reports", expected, flush=True)
            return
        time.sleep(1)
    raise RuntimeError("Expected " + expected + ", saw " + str(states[-5:]))


def press_text(package, labels):
    seen = set()
    for _ in range(18):
        dump = shell("uiautomator", "dump", "/data/local/tmp/vscreen-window.xml")
        if "dumped" not in dump.lower():
            time.sleep(1)
            continue
        root = ET.fromstring(shell("cat", "/data/local/tmp/vscreen-window.xml"))
        for node in root.iter("node"):
            attr = node.attrib
            if attr.get("package") != package:
                continue
            label = (attr.get("text") or attr.get("content-desc", "")).strip()
            if label: seen.add(label)
            if label.casefold() not in {x.casefold() for x in labels}:
                continue
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", attr["bounds"]))
            shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            print("PASS tapped", package, label, flush=True)
            return
        time.sleep(1)
    raise RuntimeError("Expected UI control in " + package + "; labels=" + repr(sorted(seen)[:18]))


def launch():
    # Wait for Shizuku state/UI below, not ActivityManager's launch completion.
    shell("am", "start", "-n", APP + "/.ui.MainActivity", "--es", "page", "SETUP")


def install(filename):
    run("adb", "install", "-r", str(APKS / filename))


def state_now():
    states = re.findall(r"state=(\w+)", shell("logcat", "-d", "-s", "ShizukuManager:I"))
    return states[-1] if states else None


def wait_ready(secs):
    for _ in range(secs):
        if state_now() == "READY":
            return True
        time.sleep(1)
    return False


def window_packages():
    dump = shell("uiautomator", "dump", "/data/local/tmp/vscreen-window.xml")
    if "dumped" not in dump.lower():
        return set()
    root = ET.fromstring(shell("cat", "/data/local/tmp/vscreen-window.xml"))
    return {n.attrib.get("package") for n in root.iter("node")}


def grant():
    launch()
    check_state("NEED_PERMISSION")
    press_text(APP, ["Grant Shizuku", "授权 Shizuku"])
    press_text(MANAGER, ["Allow all the time", "始终允许"])
    # A slow API 30 emulator can swallow the tap on Shizuku's dialog (CI: the tap "passed", the app stayed
    # NEED_PERMISSION for 45 s). Tell that apart from the app missing a granted permission, which must still fail.
    for attempt in range(3):
        if wait_ready(15):
            print("PASS app reports READY", flush=True)
            return
        if MANAGER in window_packages():
            print("Shizuku dialog still showing after the tap (swallowed tap); tapping again", flush=True)
            press_text(MANAGER, ["Allow all the time", "始终允许"])
            continue
        # Say what the app itself saw: a "permission result ... grant=false" line means the tap denied, none at all
        # means the callback never arrived.
        seen = shell("logcat", "-d", "-s", "ShizukuBackend:*", "ShizukuManager:*").splitlines()[-12:]
        print("app's Shizuku log:\n" + "\n".join(seen), flush=True)
        raise RuntimeError("Shizuku dialog is gone but the app still reports %s: the grant was not noticed"
                           % state_now())
    raise RuntimeError("Expected READY after 3 taps on Shizuku's dialog, saw " + str(state_now()))


print("SDK", shell("getprop", "ro.build.version.sdk"), "ABIs", shell("getprop", "ro.product.cpu.abilist"), flush=True)
install("shizuku.apk")
shell("monkey", "-p", MANAGER, "1")
pkgdir = Path(shell("pm", "path", MANAGER).removeprefix("package:")).parent
binary = shell("find", str(pkgdir / "lib"), "-name", "libshizuku.so").splitlines()[0]
shell(binary)
shell("input", "keyevent", "KEYCODE_HOME")
shell("logcat", "-c")
if MODE == "upgrade":
    # 2.0.0-beta8 (J.2): verify in-place upgrade on x86_64 emulator using two debug APKs with the same debug
    # keystore and increasing versionCode (both carrying native x86_64 libs, avoiding arm64 translator crashes).
    install("app-debug-prev.apk")
    grant()
    shell("logcat", "-c")
    install("app-debug.apk")
    launch()
    check_state("READY")
else:
    install("baseline.apk" if MODE == "published-beta" else "app-debug.apk")
    grant()
shell("am", "force-stop", APP)
shell("logcat", "-c")
launch()
check_state("READY")
print("PASS", MODE, "grant survived relaunch")
