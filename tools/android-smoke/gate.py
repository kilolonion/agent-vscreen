"""Device smoke test for 2.0.0-beta2 Gate (TCP forwarder in VScreenDisplayService).

Usage (inside android-emulator-runner):
  python3 tools/android-smoke/gate.py test-apks/app-debug.apk [--api30]

API 34+:
  1) `am freeze --sticky <proc>` + wait 3s (past BEAT_STALE_MS) -> `status` succeeds within 3.5s,
     with `wake_ok >= 1` and `am unfreeze` in `last_wake_cmds`.
  2) `am force-stop <pkg>` -> `status` returns 200 OK or 503 NOT_READY within 3.5s (never times out).
  3) Disable HostService + `am force-stop <pkg>` -> `status` must return 503 NOT_READY (with next=ask_user).

API 30 (`--api30` or SDK < 34, lightweight smoke without triggering OCR):
  1) Initial `status` succeeds with Gate active.
  2) `am force-stop <pkg>` -> `status` returns 200 OK or 503 NOT_READY within 3.5s.
  3) `host.gate.last_wake_cmds` confirms `am start-foreground-service` ran and `am unfreeze` did NOT run.
"""
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

APP = "com.kilon.agentvscreen"
MANAGER = "moe.shizuku.privileged.api"
ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
FORCE_API30 = "--api30" in sys.argv
APK = Path(ARGS[0] if ARGS else "test-apks/app-debug.apk")
SHIZUKU = APK.parent / "shizuku.apk"
LOCAL_PORT = 18766
FAILED = []
TOKEN = None


def run(*argv, timeout=90):
    return subprocess.check_output(argv, text=True, timeout=timeout, stderr=subprocess.STDOUT).strip()


def shell(*args, timeout=90):
    return run("adb", "shell", *args, timeout=timeout)


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (" :: " + detail if detail else ""), flush=True)
    if not ok:
        FAILED.append(name)
    return ok


def shizuku_state():
    states = re.findall(r"state=(\w+)", shell("logcat", "-d", "-s", "ShizukuManager:I"))
    return states[-1] if states else None


def wait_state(expected, secs=60):
    for _ in range(secs):
        if shizuku_state() == expected:
            return True
        time.sleep(1)
    return False


def tap_bounds(bounds):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
    shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def press_text(package, labels, tries=20):
    want = {x.casefold() for x in labels}
    for _ in range(tries):
        if "dumped" in shell("uiautomator", "dump", "/data/local/tmp/w.xml").lower():
            anr_wait = None
            for node in ET.fromstring(shell("cat", "/data/local/tmp/w.xml")).iter("node"):
                a = node.attrib
                label = (a.get("text") or a.get("content-desc", "")).strip()
                if a.get("package") == package and label.casefold() in want:
                    tap_bounds(a["bounds"])
                    print("tapped", package, label, flush=True)
                    return True
                if a.get("package") == "android" and label in ("Wait", "等待"):
                    anr_wait = a["bounds"]
            if anr_wait:
                tap_bounds(anr_wait)
        time.sleep(1)
    return False


def setup():
    sdk = int(shell("getprop", "ro.build.version.sdk"))
    print("SDK", sdk, "ABIs", shell("getprop", "ro.product.cpu.abilist"), flush=True)
    if FORCE_API30 or sdk < 34:
        shell("setprop", "debug.avs.skip_ocr_selftest", "1")
    run("adb", "install", "-r", str(SHIZUKU))
    shell("monkey", "-p", MANAGER, "1")
    pkgdir = Path(shell("pm", "path", MANAGER).removeprefix("package:")).parent
    shell(shell("find", str(pkgdir / "lib"), "-name", "libshizuku.so").splitlines()[0])
    shell("input", "keyevent", "KEYCODE_HOME")
    shell("logcat", "-c")
    run("adb", "install", "-r", "-g", str(APK), timeout=240)
    shell("settings", "put", "secure", "enabled_accessibility_services", APP + "/.host.VScreenAccessibilityService")
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    shell("am", "start", "-W", "-n", APP + "/.ui.MainActivity", "--es", "page", "SETUP")
    if not wait_state("NEED_PERMISSION", 45) and shizuku_state() != "READY":
        raise SystemExit("FAIL app never saw Shizuku: " + str(shizuku_state()))
    for _ in range(3):
        if shizuku_state() == "READY":
            break
        press_text(APP, ["Grant Shizuku", "授权 Shizuku", "Grant", "授权"])
        press_text(MANAGER, ["Allow all the time", "始终允许", "Allow", "允许"])
        wait_state("READY", 10)
    check("shizuku granted", wait_state("READY", 45), str(shizuku_state()))
    shell("input", "keyevent", "KEYCODE_HOME")
    return sdk


def pid():
    try:
        return shell("pidof", APP)
    except subprocess.CalledProcessError:
        return ""


def wait_app_dead(secs=3.0):
    deadline = time.monotonic() + secs
    while time.monotonic() < deadline:
        if not pid():
            return True
        time.sleep(0.1)
    return not pid()


def seed():
    shell("am", "broadcast", "-a", "com.kilon.agentvscreen.CI_SEED", "-p", APP)
    for _ in range(90):
        out = shell("run-as", APP, "cat", "files/ci.txt", "2>/dev/null", "||", "true")
        parts = out.split()
        if len(parts) == 2 and parts[1] != "0":
            run("adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{parts[1]}")
            return parts[0], int(parts[1])
        time.sleep(1)
    raise SystemExit("FAIL service never reported its port")


def api_raw(cmd, timeout=15):
    body = json.dumps({"cmd": cmd, "session": "gate-ci"}).encode("utf-8")
    req = urllib.request.Request(
        f"http://127.0.0.1:{LOCAL_PORT}/v2/run",
        data=body,
        method="POST",
        headers={
            "Authorization": "Bearer " + TOKEN,
            "Content-Type": "application/json",
            "X-VScreen-Lang": "en",
        },
    )
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            dt = time.monotonic() - t0
            return r.status, json.loads(r.read().decode("utf-8")), dt
    except urllib.error.HTTPError as e:
        dt = time.monotonic() - t0
        raw = e.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw)
        except Exception:
            parsed = {"raw": raw}
        return e.code, parsed, dt
    except Exception as e:
        dt = time.monotonic() - t0
        return -1, {"error": repr(e)}, dt


def gate_of(body):
    if not isinstance(body, dict):
        return None
    res = body.get("result") if isinstance(body.get("result"), dict) else body
    host = res.get("host") if isinstance(res.get("host"), dict) else None
    gate = host.get("gate") if isinstance(host, dict) else None
    return gate if isinstance(gate, dict) else None


def wait_gate_active(secs=20):
    last = None
    for _ in range(secs):
        code, body, _ = api_raw("status", timeout=10)
        gate = gate_of(body)
        last = (code, body.get("code") if isinstance(body, dict) else None, gate)
        if code == 200 and body.get("ok") is True and gate and gate.get("port", 0) > 0:
            run("adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{gate['port']}")
            return gate
        time.sleep(1)
    print("wait_gate_active failed; last:", last, flush=True)
    return None


def run_api30():
    gate = wait_gate_active()
    check("gate active on API 30", gate is not None, str(gate))
    if gate is None:
        return

    shell("am", "force-stop", APP)
    dead = wait_app_dead(3.0)
    print("API 30 force-stop dead:", dead, flush=True)
    code, body, dt = api_raw("status", timeout=15)
    ok_resp = (code == 200 and body.get("ok") is True) or (
        code == 503 and body.get("code") == "NOT_READY" and body.get("next") == "ask_user"
    )
    check(
        "API 30 force-stop status returns 200 or 503 NOT_READY within 3.5s",
        ok_resp and dt <= 3.5,
        f"http={code} dt={dt:.2f}s code={body.get('code')} body_err={body.get('error')}",
    )

    # Confirm via GATE STATUS last_wake_cmds that am unfreeze was never executed on SDK < 34.
    gate_after = gate_of(body) if code == 200 else None
    if gate_after is None:
        for _ in range(20):
            time.sleep(1)
            c2, b2, _ = api_raw("status", timeout=10)
            if c2 == 200 and b2.get("ok") is True:
                gate_after = gate_of(b2)
                if gate_after and gate_after.get("last_wake_cmds"):
                    break
    cmds = (gate_after or {}).get("last_wake_cmds") or []
    check(
        "API 30 wake ran start-foreground-service without am unfreeze",
        len(cmds) >= 1 and any("start-foreground-service" in c for c in cmds) and all("unfreeze" not in c for c in cmds),
        f"last_wake_cmds={cmds}",
    )


def run_api34():
    gate = wait_gate_active()
    check("gate active on API 34+", gate is not None, str(gate))
    if gate is None:
        return
    gate_port = gate["port"]

    # 1) am freeze --sticky <proc>, wait > BEAT_STALE_MS (3s), status succeeds within 3.5s.
    freeze_out = shell("am", "freeze", "--sticky", APP)
    print("am freeze:", freeze_out, flush=True)
    time.sleep(3.0)
    code, body, dt = api_raw("status", timeout=15)
    gate1 = gate_of(body) or {}
    cmds1 = gate1.get("last_wake_cmds") or []
    check(
        "frozen app wakes and answers status within 3.5s",
        code == 200 and body.get("ok") is True and dt <= 3.5,
        f"http={code} dt={dt:.2f}s code={body.get('code')} gate={gate1}",
    )
    check(
        "freeze wake recorded wake_ok and am unfreeze",
        gate1.get("wake_ok", 0) >= 1 and any("am unfreeze" in c for c in cmds1),
        f"gate={gate1}",
    )

    # 2) am force-stop -> wait for process exit -> status returns 200 or 503 NOT_READY within 3.5s, never times out.
    shell("am", "force-stop", APP)
    dead2 = wait_app_dead(3.0)
    print("force-stop dead:", dead2, flush=True)
    code2, body2, dt2 = api_raw("status", timeout=15)
    ok2 = (code2 == 200 and body2.get("ok") is True) or (
        code2 == 503 and body2.get("code") == "NOT_READY" and body2.get("next") == "ask_user"
    )
    check(
        "force-stop status returns 200 or 503 NOT_READY within 3.5s",
        ok2 and dt2 <= 3.5,
        f"http={code2} dt={dt2:.2f}s code={body2.get('code')} body_err={body2.get('error')}",
    )

    # 3) Disable HostService (via adb root) + force-stop -> wake must fail and return 503 NOT_READY.
    run("adb", "root")
    run("adb", "wait-for-device")
    time.sleep(1)
    run("adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{gate_port}")
    try:
        dis = shell("pm", "disable", f"{APP}/.host.HostService")
        print("disable HostService:", dis, flush=True)
        shell("am", "force-stop", APP)
        wait_app_dead(3.0)
        code3, body3, dt3 = api_raw("status", timeout=15)
        check(
            "disabled HostService returns 503 NOT_READY without timing out",
            code3 == 503
            and body3.get("ok") is False
            and body3.get("code") == "NOT_READY"
            and body3.get("exit") == 1
            and body3.get("next") == "ask_user"
            and "Agent-VScreen" in str(body3.get("text", ""))
            and dt3 <= 5.0,
            f"http={code3} dt={dt3:.2f}s body={body3}",
        )
    finally:
        try:
            en = shell("pm", "enable", f"{APP}/.host.HostService")
            print("re-enabled HostService:", en, flush=True)
        except Exception as e:
            print("WARN re-enable HostService failed:", e, flush=True)


def main():
    global TOKEN
    sdk = setup()
    TOKEN, port = seed()
    print("seeded port:", port, flush=True)
    if FORCE_API30 or sdk < 34:
        run_api30()
    else:
        run_api34()
    print("SUMMARY", "all passed" if not FAILED else "failed: " + ", ".join(FAILED), flush=True)
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
