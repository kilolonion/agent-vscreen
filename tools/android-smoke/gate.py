"""Device smoke test for 2.0.0-beta2 Gate (TCP forwarder in VScreenDisplayService).

Usage (inside android-emulator-runner):
  python3 tools/android-smoke/gate.py test-apks/app-debug.apk [--root | --api30]

API 34 Shizuku (default, uid 2000):
  1) `am freeze --sticky <proc>` + wait 3s (past BEAT_STALE_MS) -> `status` succeeds within 3.5s,
     with `wake_ok >= 1` and `am unfreeze` in `last_wake_cmds`.
  2) `am force-stop <pkg>` -> wait dead -> `status` returns 200 OK (with `last_wake_ms <= 3000`) or
     503 NOT_READY within 3.5s (never times out).
  4) `am start-foreground-service -n <pkg>/.host.HostService -a com.kilon.agentvscreen.STOP` ->
     `status` still returns 200 OK and HostService stays running (exported entry ignores actions/extras).

API 34 Root (`--root`, avsu via `su 2000` -> RootMain runs as uid 2000, adb starts as root):
  1) Same as Shizuku Case 1.
  2) Same as Shizuku Case 2.
  3) `pm disable <pkg>/.host.HostService` + `am force-stop <pkg>` -> `status` must return 503 NOT_READY
     (`next=ask_user`) within 3.5s; `pm enable` always restored in `finally`.

API 30 Shizuku (`--api30` or SDK < 34):
  - Waits for `host.ocr_selftest.state` to become `done` or `crashed` (one first-boot crash under x86_64
    arm64 translation is expected and persisted; no further crashes afterwards).
  - `am force-stop <pkg>` -> wait dead -> `status` returns 200 OK (`last_wake_ms <= 3000`) or 503 NOT_READY
    within 3.5s.
  - `host.gate.last_wake_cmds` confirms `am start-foreground-service` ran and `am unfreeze` did NOT run.
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
ROOT = "--root" in sys.argv
APK = Path(ARGS[0] if ARGS else "test-apks/app-debug.apk")
SHIZUKU = APK.parent / "shizuku.apk"
AVSU = APK.parent / "avsu"
SU = "/data/local/tmp/avsu-bin/su"
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


def priv_state():
    found = re.findall(r"via=(\w+) state=(\w+)", shell("logcat", "-d", "-s", "Privileged:I"))
    return found[-1] if found else None


def wait_priv(expected, secs=60):
    for _ in range(secs):
        if priv_state() == expected:
            return True
        time.sleep(1)
    return False


def root_pid():
    try:
        return shell("pidof", "agent-vscreen:root")
    except subprocess.CalledProcessError:
        return ""


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


def setup_root():
    """Reuse agent_e2e.py's root setup: adb root once at start, avsu daemon runs RootMain via `su 2000` (uid 2000)."""
    sdk = int(shell("getprop", "ro.build.version.sdk"))
    print("SDK", sdk, "ABIs", shell("getprop", "ro.product.cpu.abilist"), flush=True)
    run("adb", "root")
    run("adb", "wait-for-device")
    time.sleep(2)
    shell("setenforce", "0")
    shell("mkdir", "-p", "/data/local/tmp/avsu-bin")
    run("adb", "push", str(AVSU), SU)
    shell("chmod", "755", "/data/local/tmp/avsu-bin", SU)
    shell("sh", "-c", f"'{SU} --daemon </dev/null >/dev/null 2>&1'")
    shell("setprop", "debug.avs.su", SU)
    print("stand-in su:", shell(SU, "-c", "id"), flush=True)
    shell("logcat", "-c")
    run("adb", "install", "-r", "-g", str(APK), timeout=240)
    shell("settings", "put", "secure", "enabled_accessibility_services", APP + "/.host.VScreenAccessibilityService")
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    shell("am", "start", "-W", "-n", APP + "/.ui.MainActivity", "--es", "page", "SETUP")
    check("app starts on Shizuku without Shizuku installed", wait_priv(("shizuku", "NOT_INSTALLED"), 45), str(priv_state()))
    for _ in range(3):
        press_text(APP, ["Rooted? Use root instead", "手机已 root？改用 Root"])
        if wait_priv(("root", "READY"), 20) or (priv_state() or ("",))[0] == "root":
            break
    check("root granted and service connected", wait_priv(("root", "READY"), 60), str(priv_state()))
    rp = root_pid()
    who = shell("ps", "-o", "USER", "-p", rp).splitlines()[-1].strip() if rp else ""
    check("root service runs as shell (uid 2000)", who == "shell", f"pid={rp} user={who}")
    shell("input", "keyevent", "KEYCODE_HOME")
    return sdk


def setup_shizuku():
    sdk = int(shell("getprop", "ro.build.version.sdk"))
    print("SDK", sdk, "ABIs", shell("getprop", "ro.product.cpu.abilist"), flush=True)
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
    for _ in range(6):
        if shizuku_state() == "READY":
            break
        if not pid():
            # Expected on API 30 if first-boot OCR self-test crashed the process during setup: relaunch.
            shell("am", "start", "-W", "-n", APP + "/.ui.MainActivity", "--es", "page", "SETUP")
            time.sleep(1)
        if not press_text(APP, ["Grant Shizuku", "授权 Shizuku", "Grant", "授权"], tries=10):
            shell("am", "start", "-W", "-n", APP + "/.ui.MainActivity", "--es", "page", "SETUP")
            continue
        press_text(MANAGER, ["Allow all the time", "始终允许", "Allow", "允许"], tries=15)
        wait_state("READY", 10)
    check("shizuku granted", wait_state("READY", 45), str(shizuku_state()))
    shell("input", "keyevent", "KEYCODE_HOME")
    return sdk


def seed():
    shell("am", "broadcast", "-a", "com.kilon.agentvscreen.CI_SEED", "-p", APP)
    for i in range(90):
        out = shell("run-as", APP, "cat", "files/ci.txt", "2>/dev/null", "||", "true")
        parts = out.split()
        if len(parts) == 2 and parts[1] != "0":
            run("adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{parts[1]}")
            return parts[0], int(parts[1])
        if i > 0 and i % 5 == 0 and not pid():
            shell("am", "broadcast", "-a", "com.kilon.agentvscreen.CI_SEED", "-p", APP)
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


def host_of(body):
    if not isinstance(body, dict):
        return None
    res = body.get("result") if isinstance(body.get("result"), dict) else body
    host = res.get("host") if isinstance(res.get("host"), dict) else None
    return host


def gate_of(body):
    host = host_of(body)
    gate = host.get("gate") if isinstance(host, dict) else None
    return gate if isinstance(gate, dict) else None


def ocr_selftest_of(body):
    host = host_of(body)
    st = host.get("ocr_selftest") if isinstance(host, dict) else None
    return st if isinstance(st, dict) else None


def wait_gate_and_selftest(secs=120):
    """Wait until Gate is active and `host.ocr_selftest.state` reaches `done` or `crashed`."""
    last = None
    last_st_str = None
    for i in range(secs):
        code, body, _ = api_raw("status", timeout=15)
        gate = gate_of(body)
        st = ocr_selftest_of(body)
        last = (code, body.get("code") if isinstance(body, dict) else None, gate, st)
        if st and str(st) != last_st_str:
            print("ocr_selftest:", st, flush=True)
            last_st_str = str(st)
        if code == 200 and body.get("ok") is True and gate and gate.get("port", 0) > 0:
            run("adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{gate['port']}")
            if st and st.get("state") in ("done", "crashed"):
                return gate, st
        elif code != 200 and not pid():
            # Process may have exited on first-boot OCR self-test crash (API 30 translator): restart so recovery persists CRASHED.
            print("app dead while waiting for ocr_selftest (expected on first-boot crash); restarting", flush=True)
            try:
                shell("am", "start-foreground-service", "-n", f"{APP}/.host.HostService")
            except Exception as e:
                print("restart warning:", e, flush=True)
            time.sleep(1)
            try:
                out = shell("run-as", APP, "cat", "files/ci.txt", "2>/dev/null", "||", "true")
                parts = out.split()
                if len(parts) == 2 and parts[1] != "0":
                    run("adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{parts[1]}")
            except Exception:
                pass
        time.sleep(1)
    print("wait_gate_and_selftest failed; last:", last, flush=True)
    return None, None


def run_freeze_and_force_stop(label):
    # 1) am freeze --sticky <proc>, wait > BEAT_STALE_MS (3s), status succeeds within 3.5s.
    freeze_out = shell("am", "freeze", "--sticky", APP)
    print("am freeze:", freeze_out, flush=True)
    time.sleep(3.0)
    code, body, dt = api_raw("status", timeout=15)
    gate1 = gate_of(body) or {}
    cmds1 = gate1.get("last_wake_cmds") or []
    check(
        f"[{label}] frozen app wakes and answers status within 3.5s",
        code == 200 and body.get("ok") is True and dt <= 3.5,
        f"http={code} dt={dt:.2f}s code={body.get('code')} gate={gate1}",
    )
    check(
        f"[{label}] freeze wake recorded wake_ok and am unfreeze",
        gate1.get("wake_ok", 0) >= 1 and any("am unfreeze" in c for c in cmds1),
        f"gate={gate1}",
    )

    # 2) am force-stop -> wait dead -> status returns 200 (last_wake_ms <= 3000) or 503 NOT_READY within 3.5s.
    shell("am", "force-stop", APP)
    dead2 = wait_app_dead(3.0)
    print("force-stop dead:", dead2, flush=True)
    code2, body2, dt2 = api_raw("status", timeout=15)
    gate2 = gate_of(body2) or {}
    ok2 = (code2 == 200 and body2.get("ok") is True) or (
        code2 == 503 and body2.get("code") == "NOT_READY" and body2.get("next") == "ask_user"
    )
    check(
        f"[{label}] force-stop status returns 200 or 503 NOT_READY within 3.5s",
        ok2 and dt2 <= 3.5,
        f"http={code2} dt={dt2:.2f}s code={body2.get('code')} last_wake_ms={gate2.get('last_wake_ms')} body_err={body2.get('error')}",
    )
    if code2 == 200:
        check(
            f"[{label}] force-stop wake completed within 3000ms",
            0 < int(gate2.get("last_wake_ms", 0)) <= 3000,
            f"last_wake_ms={gate2.get('last_wake_ms')} gate={gate2}",
        )
    return code2


def run_api30():
    gate, st = wait_gate_and_selftest(120)
    check("gate active and ocr_selftest finished on API 30", gate is not None and st is not None, f"gate={gate} selftest={st}")
    if gate is None:
        return

    shell("am", "force-stop", APP)
    dead = wait_app_dead(3.0)
    print("API 30 force-stop dead:", dead, flush=True)
    code, body, dt = api_raw("status", timeout=15)
    gate_after = gate_of(body) if code == 200 else None
    ok_resp = (code == 200 and body.get("ok") is True) or (
        code == 503 and body.get("code") == "NOT_READY" and body.get("next") == "ask_user"
    )
    check(
        "API 30 force-stop status returns 200 or 503 NOT_READY within 3.5s",
        ok_resp and dt <= 3.5,
        f"http={code} dt={dt:.2f}s code={body.get('code')} last_wake_ms={(gate_after or {}).get('last_wake_ms')} body_err={body.get('error')}",
    )
    if code == 200:
        check(
            "API 30 force-stop wake completed within 3000ms",
            0 < int((gate_after or {}).get("last_wake_ms", 0)) <= 3000,
            f"gate={gate_after}",
        )

    # Confirm via GATE STATUS last_wake_cmds that am unfreeze was never executed on SDK < 34.
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
    # Confirm no repeat crash after force-stop wake.
    pid_after = pid()
    time.sleep(2.0)
    c_alive, b_alive, _ = api_raw("status", timeout=10)
    check(
        "API 30 app stays alive after wake (no repeat OCR self-test crash)",
        bool(pid_after) and pid() == pid_after and c_alive == 200 and b_alive.get("ok") is True,
        f"pid={pid_after}->{pid()} http={c_alive}",
    )


def run_api34_shizuku():
    gate, st = wait_gate_and_selftest(120)
    check("gate active and ocr_selftest finished on API 34 (shizuku)", gate is not None and st is not None, f"gate={gate} selftest={st}")
    if gate is None:
        return

    code2 = run_freeze_and_force_stop("shizuku")
    if code2 != 200:
        for _ in range(15):
            time.sleep(1)
            c, b, _ = api_raw("status", timeout=10)
            if c == 200 and b.get("ok") is True:
                break

    # 4) Exported HostService boundary (uid 2000): external ACTION_STOP must be ignored; service stays up and status=200.
    out4 = shell("am", "start-foreground-service", "-n", f"{APP}/.host.HostService", "-a", "com.kilon.agentvscreen.STOP")
    print("external ACTION_STOP output:", out4, flush=True)
    time.sleep(1.5)
    code4, body4, dt4 = api_raw("status", timeout=10)
    host4 = host_of(body4) or {}
    res4 = body4.get("result") if isinstance(body4.get("result"), dict) else {}
    blocking4 = [x.get("code") if isinstance(x, dict) else x for x in (res4.get("readiness") or {}).get("blocking", [])]
    check(
        "[shizuku] external ACTION_STOP to HostService is ignored and status stays 200",
        code4 == 200 and body4.get("ok") is True and host4.get("foreground_service") is True and "service_off" not in blocking4,
        f"http={code4} dt={dt4:.2f}s fg={host4.get('foreground_service')} blocking={blocking4}",
    )


def run_api34_root():
    gate, st = wait_gate_and_selftest(120)
    check("gate active and ocr_selftest finished on API 34 (root)", gate is not None and st is not None, f"gate={gate} selftest={st}")
    if gate is None:
        return

    run_freeze_and_force_stop("root")

    # 3) Disable HostService (adb is already root since setup_root, no adbd restart) + force-stop -> 503 NOT_READY <= 3.5s.
    try:
        dis = shell("pm", "disable", f"{APP}/.host.HostService")
        print("disable HostService:", dis, flush=True)
        shell("am", "force-stop", APP)
        dead3 = wait_app_dead(3.0)
        print("case3 force-stop dead:", dead3, flush=True)
        code3, body3, dt3 = api_raw("status", timeout=15)
        check(
            "[root] disabled HostService returns 503 NOT_READY within 3.5s",
            code3 == 503
            and body3.get("ok") is False
            and body3.get("code") == "NOT_READY"
            and body3.get("exit") == 1
            and body3.get("next") == "ask_user"
            and "Agent-VScreen" in str(body3.get("text", ""))
            and dt3 <= 3.5,
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
    sdk = setup_root() if ROOT else setup_shizuku()
    TOKEN, port = seed()
    print("seeded port:", port, flush=True)
    if FORCE_API30 or sdk < 34:
        run_api30()
    elif ROOT:
        run_api34_root()
    else:
        run_api34_shizuku()
    print("SUMMARY", "all passed" if not FAILED else "failed: " + ", ".join(FAILED), flush=True)
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
