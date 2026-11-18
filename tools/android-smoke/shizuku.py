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
    shell("am", "start", "-W", "-n", APP + "/.ui.MainActivity", "--es", "page", "SETUP")


def install(filename):
    run("adb", "install", "-r", str(APKS / filename))


def grant():
    launch()
    check_state("NEED_PERMISSION")
    press_text(APP, ["Grant Shizuku", "授权 Shizuku"])
    press_text(MANAGER, ["Allow all the time", "始终允许"])
    check_state("READY")


print("SDK", shell("getprop", "ro.build.version.sdk"), "ABIs", shell("getprop", "ro.product.cpu.abilist"), flush=True)
install("shizuku.apk")
shell("monkey", "-p", MANAGER, "1")
pkgdir = Path(shell("pm", "path", MANAGER).removeprefix("package:")).parent
binary = shell("find", str(pkgdir / "lib"), "-name", "libshizuku.so").splitlines()[0]
shell(binary)
shell("input", "keyevent", "KEYCODE_HOME")
shell("logcat", "-c")
if MODE == "upgrade":
    # Cover the reported 1.3 -> 1.4 recovery using their original signed releases.
    install("1.3.0.apk")
    launch()
    install("1.4.0.apk")
    grant()
    # Candidate uses a CI signer. To model a genuine in-place update from 1.4 to
    # the candidate, repeat the historical path with a disposable shared signer.
    run("adb", "uninstall", APP)
    signer = sorted((Path(os.environ["ANDROID_HOME"]) / "build-tools").glob("*/apksigner"))[-1]
    key = Path(os.environ["RUNNER_TEMP"]) / "smoke-signing.jks"
    run("keytool", "-genkeypair", "-keystore", str(key), "-storepass", "temporary", "-keypass", "temporary",
        "-alias", "smoke", "-dname", "CN=Disposable", "-keyalg", "RSA", "-validity", "3")
    for name in ["1.3.0", "1.4.0", "app-release"]:
        run(str(signer), "sign", "--ks", str(key), "--ks-pass", "pass:temporary", "--key-pass", "pass:temporary",
            "--out", str(APKS / (name + "-ci.apk")), str(APKS / (name + ".apk")))
    install("1.3.0-ci.apk")
    launch()
    install("1.4.0-ci.apk")
    shell("logcat", "-c")
    grant()
    shell("logcat", "-c")
    install("app-release-ci.apk")
    launch()
    check_state("READY")
else:
    install("baseline.apk" if MODE == "published-beta" else "app-release.apk")
    grant()
shell("am", "force-stop", APP)
shell("logcat", "-c")
launch()
check_state("READY")
print("PASS", MODE, "grant survived relaunch")
