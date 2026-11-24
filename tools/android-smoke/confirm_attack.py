"""Stage 6 (2.0.0-beta6) security & strong-confirmation attack/acceptance smoke suite.

Runs in device.yml on BOTH API 30 (pixel_2) and API 34 (pixel_6).
Covers every requirement from plan sections 6.8, 6.9, and 6.10:
  1) API `launch <ownPkg>`, `handoff in <ownPkg>`, `handoff park/unpark <ownPkg>` -> PROTECTED_APP (approvals unchanged)
  2) Debug hook display-0 injection (INJECT_DRAG/KEY/TEXT/COMBO/POINTER) -> all false (approvals unchanged)
  3) Debug hook service-side MOVE_TASK (own pkg -> vd), FOCUS_TASK (d0 & omitted), REMOVE_TASK (d0), a11y d0 read -> all refused
  4) Fresh unapproved token -> NEEDS_CONFIRM (NEW_CLIENT); while pending, Operit handshake (/v2/ping + status + guide)
     and MCP handshake (initialize + tools/list + vscreen_status + vscreen_help) all succeed
  5) `am start --display <vd> -n <pkg>/.ui.StrongConfirmActivity` -> immediately finishes + strong_confirm_wrong_display audit
  6) Main-screen focused EditText (MainInputActivity on d0): `act "type ..."` on virtual display never leaks text to d0
  7) StrongConfirmActivity on d0:
     - `act "type ...; scroll down"` on vd -> unaffected, approvals unchanged
     - External AttackA11yService ACTION_CLICK & ACTION_LONG_CLICK on confirm button -> false, strong_confirm_a11y_ignored audit
     - No lockscreen + `adb shell input swipe x y x y 3500` on confirm button -> not approved, strong_confirm_l6_ignored audit
  8) API 34 positive PIN BiometricPrompt path (6.8 / 6.10 #4):
     - `locksettings set-pin 1234`, fresh token #2 -> NEEDS_CONFIRM, record with `screenrecord`, open StrongConfirmActivity,
       tap confirm, enter PIN via `input text 1234` + KEYCODE_ENTER -> approvals.json has by="biometric",
       audit has strong_confirm_approved, resend `apps` succeeds, mp4 artifact saved > 0 bytes
  9) Positive no-lockscreen real-touch hold path (API 30 & 34, 6.10 #1):
     - `adb root` + `sendevent` real touchscreen 3.5s hold on confirm button -> approvals.json has by="hold",
       audit has strong_confirm_approved, resend `apps` succeeds

Usage (inside android-emulator-runner):
  python3 tools/android-smoke/confirm_attack.py test-apks/app-debug.apk [--out confirm-out]
"""
import argparse
import hashlib
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
TORTURE = "com.kilon.avstorture"
STRONG_ACT = f"{APP}/.ui.StrongConfirmActivity"
MAIN_INPUT_ACT = f"{TORTURE}/.MainInputActivity"
ATTACK_A11Y = f"{TORTURE}/.AttackA11yService"
LOCAL_PORT = 18766

FAILED = []
MAIN_TOKEN = None
GATE_PORT = 0
IS_ROOT_ADB = False


def run(*argv, timeout=90, check_rc=True):
    p = subprocess.run(argv, text=True, timeout=timeout, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = (p.stdout or "").strip()
    if check_rc and p.returncode != 0:
        raise subprocess.CalledProcessError(p.returncode, argv, output=out)
    return p.returncode, out


def shell(*args, timeout=90, check_rc=True):
    return run("adb", "shell", *args, timeout=timeout, check_rc=check_rc)


def shell_out(*args, timeout=90):
    _, out = shell(*args, timeout=timeout, check_rc=True)
    return out


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (" :: " + detail if detail else ""), flush=True)
    if not ok:
        FAILED.append(name)
    return ok


def pid(pkg=APP):
    rc, out = shell("pidof", pkg, check_rc=False)
    return out if rc == 0 else ""


def read_app_file(rel_path):
    if IS_ROOT_ADB:
        rc, out = shell("cat", f"/data/data/{APP}/{rel_path}", "2>/dev/null", "||", "true", check_rc=False)
        if rc == 0:
            return out
    _, out = shell("run-as", APP, "cat", rel_path, "2>/dev/null", "||", "true", check_rc=False)
    return out


def read_approvals():
    raw = read_app_file("files/vscreen/approvals.json").strip()
    if not raw:
        return []
    try:
        data = json.loads(raw)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def read_audit_lines():
    out = []
    for rel in ("files/audit/audit.1.jsonl", "files/audit/audit.jsonl"):
        raw = read_app_file(rel)
        for line in raw.splitlines():
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                out.append(json.loads(line))
            except Exception:
                pass
    return out


def shizuku_state():
    _, out = shell("logcat", "-d", "-s", "ShizukuManager:I", check_rc=False)
    states = re.findall(r"state=(\w+)", out)
    return states[-1] if states else None


def wait_state(expected, secs=60):
    for _ in range(secs):
        if shizuku_state() == expected:
            return True
        time.sleep(1)
    return False


def tap_bounds(bounds):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
    shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2), check_rc=False)


def press_text(package, labels, tries=15):
    want = {x.casefold() for x in labels}
    for _ in range(tries):
        _, d = shell("uiautomator", "dump", "/data/local/tmp/w.xml", check_rc=False)
        if "dumped" in d.lower():
            anr_wait = None
            _, xml_txt = shell("cat", "/data/local/tmp/w.xml", check_rc=False)
            try:
                root = ET.fromstring(xml_txt)
            except Exception:
                time.sleep(1)
                continue
            for node in root.iter("node"):
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


def setup_shizuku(apk: Path):
    shizuku_apk = apk.parent / "shizuku.apk"
    torture_apk = apk.parent / "torture-debug.apk"
    sdk = int(shell_out("getprop", "ro.build.version.sdk"))
    print("SDK", sdk, "ABIs", shell_out("getprop", "ro.product.cpu.abilist"), flush=True)
    run("adb", "install", "-r", str(shizuku_apk), timeout=180)
    shell("monkey", "-p", MANAGER, "1", check_rc=False)
    pkgdir = Path(shell_out("pm", "path", MANAGER).removeprefix("package:")).parent
    shizuku_bin = shell_out("find", str(pkgdir / "lib"), "-name", "libshizuku.so").splitlines()[0]
    shell(shizuku_bin, check_rc=False)
    shell("input", "keyevent", "KEYCODE_HOME", check_rc=False)
    shell("logcat", "-c", check_rc=False)
    run("adb", "install", "-r", "-g", str(apk), timeout=240)
    run("adb", "install", "-r", "-g", str(torture_apk), timeout=180)
    shell("settings", "put", "secure", "enabled_accessibility_services", f"{APP}/.host.VScreenAccessibilityService")
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    shell("am", "start", "-W", "-n", f"{APP}/.ui.MainActivity", "--es", "page", "SETUP")
    if not wait_state("NEED_PERMISSION", 45) and shizuku_state() != "READY":
        raise SystemExit("FAIL app never saw Shizuku: " + str(shizuku_state()))
    for _ in range(6):
        if shizuku_state() == "READY":
            break
        if not pid():
            shell("am", "start", "-W", "-n", f"{APP}/.ui.MainActivity", "--es", "page", "SETUP", check_rc=False)
            time.sleep(1)
        if not press_text(APP, ["Grant Shizuku", "授权 Shizuku", "Grant", "授权"], tries=10):
            shell("am", "start", "-W", "-n", f"{APP}/.ui.MainActivity", "--es", "page", "SETUP", check_rc=False)
            continue
        press_text(MANAGER, ["Allow all the time", "始终允许", "Allow", "允许"], tries=15)
        wait_state("READY", 10)
    check("shizuku granted", wait_state("READY", 45), str(shizuku_state()))
    shell("input", "keyevent", "KEYCODE_HOME", check_rc=False)
    return sdk


def seed():
    shell("am", "broadcast", "-a", "com.kilon.agentvscreen.CI_SEED", "-p", APP)
    for i in range(90):
        out = read_app_file("files/ci.txt")
        parts = out.split()
        if len(parts) == 2 and parts[1] != "0":
            run("adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{parts[1]}")
            return parts[0], int(parts[1])
        if i > 0 and i % 5 == 0 and not pid():
            shell("am", "broadcast", "-a", "com.kilon.agentvscreen.CI_SEED", "-p", APP, check_rc=False)
        time.sleep(1)
    raise SystemExit("FAIL service never reported its port")


def http_json(method, path, payload=None, token=None, timeout=60):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"X-VScreen-Lang": "en"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://127.0.0.1:{LOCAL_PORT}{path}", data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}
    except Exception as e:
        return -1, {"error": repr(e)}


def api_run(cmd_or_args, token=None, session="confirm-atk", timeout=90):
    payload = {"session": session}
    if isinstance(cmd_or_args, list):
        payload["args"] = cmd_or_args
    else:
        payload["cmd"] = cmd_or_args
    return http_json("POST", "/v2/run", payload, token=token or MAIN_TOKEN, timeout=timeout)


def wait_gate_and_selftest(secs=120):
    global GATE_PORT
    for _ in range(secs):
        code, body = api_run("status", timeout=15)
        res = body.get("result") if isinstance(body, dict) and isinstance(body.get("result"), dict) else body
        host = res.get("host") if isinstance(res, dict) and isinstance(res.get("host"), dict) else None
        gate = host.get("gate") if isinstance(host, dict) and isinstance(host.get("gate"), dict) else None
        st = host.get("ocr_selftest") if isinstance(host, dict) and isinstance(host.get("ocr_selftest"), dict) else None
        if code == 200 and body.get("ok") is True and gate and gate.get("port", 0) > 0:
            GATE_PORT = int(gate["port"])
            run("adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{GATE_PORT}")
            if st and st.get("state") in ("done", "crashed"):
                print(f"gate ready: {GATE_PORT} ocr_selftest={st}", flush=True)
                return True
        elif code != 200 and not pid():
            print("app dead during first-boot OCR selftest (expected on API 30); restarting", flush=True)
            shell("am", "start-foreground-service", "-n", f"{APP}/.host.HostService", check_rc=False)
            time.sleep(1)
            parts = read_app_file("files/ci.txt").split()
            if len(parts) == 2 and parts[1] != "0":
                GATE_PORT = int(parts[1])
                run("adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{GATE_PORT}", check_rc=False)
        time.sleep(1)
    return False


def ci_hook(extra_key, extra_val, out_file, wait_secs=15):
    if IS_ROOT_ADB:
        shell("rm", "-f", f"/data/data/{APP}/{out_file}", check_rc=False)
    else:
        shell("run-as", APP, "rm", "-f", out_file, check_rc=False)
    shell("am", "broadcast", "-a", "com.kilon.agentvscreen.CI_SEED", "-p", APP, "--es", extra_key, str(extra_val))
    deadline = time.monotonic() + wait_secs
    while time.monotonic() < deadline:
        txt = read_app_file(out_file).strip()
        if txt:
            return txt
        time.sleep(0.25)
    return ""


def make_fresh_token(name, scope="operate"):
    txt = ci_hook("mk_token", f"{name}:{scope}", "files/ci-token.txt")
    lines = [ln.strip() for ln in txt.splitlines() if ln.strip()]
    if len(lines) != 2:
        raise SystemExit(f"FAIL mk_token returned {txt!r}")
    return lines[0], lines[1]


def find_virtual_display_id():
    code, body = api_run(["tasks"], timeout=30)
    if code == 200 and isinstance(body, dict):
        res = body.get("result") if isinstance(body.get("result"), dict) else body
        if isinstance(res, dict) and isinstance(res.get("display_id"), int) and res["display_id"] > 0:
            return res["display_id"]
    _, out = shell("dumpsys", "activity", "activities", check_rc=False)
    cur = None
    for line in out.splitlines():
        m = re.search(r"Display #(\d+)", line)
        if m:
            cur = int(m.group(1))
        elif cur and cur != 0 and f"{TORTURE}/" in line:
            return cur
    return None


def find_own_task_id_on_d0():
    _, out = shell("dumpsys", "activity", "activities", check_rc=False)
    block = out.split("Display #0", 1)[-1].split("Display #", 1)[0] if "Display #0" in out else out
    cur_task = None
    for line in block.splitlines():
        mt = re.search(r"Task(?:Record)?\{[0-9a-fA-F]+\s+#(\d+)", line)
        if mt:
            cur_task = int(mt.group(1))
        if cur_task is not None and f"{APP}/" in line:
            return cur_task
    return None


def activity_on_display(disp_id, frag):
    _, out = shell("dumpsys", "activity", "activities", check_rc=False)
    marker = f"Display #{disp_id}"
    if marker not in out:
        return False
    block = out.split(marker, 1)[-1].split("Display #", 1)[0]
    for line in block.splitlines():
        if frag not in line:
            continue
        if any(k in line for k in ("mResumedActivity", "topResumedActivity", "ResumedActivity:", "state=RESUMED")):
            return True
    return False


def find_confirm_button_center(want_id=None, want_bio=None, tries=20):
    for _ in range(tries):
        raw = ci_hook("attack", "confirm_btn_bounds", "files/ci-attack.txt", wait_secs=5)
        if raw.startswith("{"):
            try:
                obj = json.loads(raw)
            except Exception:
                obj = {}
            if obj.get("present") is True:
                if want_id is not None and obj.get("id") != want_id:
                    time.sleep(0.25)
                    continue
                if want_bio is not None and obj.get("biometric") is not want_bio:
                    time.sleep(0.25)
                    continue
                return int(obj["cx"]), int(obj["cy"]), str(obj.get("bounds", ""))
        time.sleep(0.25)
    return None


def read_atk_events(since_t=0):
    _, raw = shell("logcat", "-d", "-s", "AVS_ATK:I", "-v", "raw", check_rc=False)
    evs = []
    for line in raw.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            obj = json.loads(line)
        except Exception:
            continue
        if obj.get("t", 0) > since_t:
            evs.append(obj)
    return evs


def latest_atk_t():
    return max((e.get("t", 0) for e in read_atk_events(0)), default=0)


def wait_atk_event(ev_name, since_t, timeout=10.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for e in read_atk_events(since_t):
            if e.get("ev") == ev_name:
                return e
        time.sleep(0.15)
    return None


def display0_size():
    _, out = shell("wm", "size", check_rc=False)
    m = re.findall(r"(\d+)x(\d+)", out)
    if m:
        return int(m[-1][0]), int(m[-1][1])
    return 1080, 1920


def parse_evdev_touch():
    _, ge = shell("getevent", "-pl", check_rc=False)
    devices = []
    cur = None
    for line in ge.splitlines():
        m = re.match(r"^add device \d+:\s+(\S+)", line)
        if m:
            if cur:
                devices.append(cur)
            cur = {"path": m.group(1), "name": "", "abs": {}, "has_btn_touch": False}
            continue
        if cur is None:
            continue
        mn = re.match(r'^\s+name:\s+"(.*)"', line)
        if mn:
            cur["name"] = mn.group(1)
            continue
        if "BTN_TOUCH" in line or "014a" in line:
            cur["has_btn_touch"] = True
        ma = re.search(r"(ABS_[A-Z0-9_]+)\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)", line)
        if ma:
            cur["abs"][ma.group(1)] = (int(ma.group(2)), int(ma.group(3)))
    if cur:
        devices.append(cur)
    cands = [d for d in devices if "ABS_MT_POSITION_X" in d["abs"] and "ABS_MT_POSITION_Y" in d["abs"]]
    cands.sort(key=lambda d: (
        0 if "virtio_input_multi_touch_1" == d["name"] else 1,
        int(re.search(r"event(\d+)$", d["path"]).group(1)) if re.search(r"event(\d+)$", d["path"]) else 999,
    ))
    return cands[0] if cands else None


def sendevent_hold_px(dev, px, py, w, h, hold_sec=3.6, tid=501):
    path = dev["path"]
    a = dev["abs"]
    x_min, x_max = a["ABS_MT_POSITION_X"]
    y_min, y_max = a["ABS_MT_POSITION_Y"]
    ax = x_min + int((px / max(1, w)) * (x_max - x_min))
    ay = y_min + int((py / max(1, h)) * (y_max - y_min))
    down = [
        f"sendevent {path} 3 47 0",
        f"sendevent {path} 3 57 {tid}",
        f"sendevent {path} 3 53 {ax}",
        f"sendevent {path} 3 54 {ay}",
    ]
    if "ABS_MT_PRESSURE" in a:
        pmin, pmax = a["ABS_MT_PRESSURE"]
        down.append(f"sendevent {path} 3 58 {max(pmin + 1, (pmin + pmax) // 2)}")
    if "ABS_MT_TOUCH_MAJOR" in a:
        down.append(f"sendevent {path} 3 48 10")
    if dev["has_btn_touch"]:
        down.append(f"sendevent {path} 1 330 1")
    down.append(f"sendevent {path} 0 0 0")

    up = [
        f"sendevent {path} 3 47 0",
        f"(sendevent -- {path} 3 57 -1 2>/dev/null || sendevent {path} 3 57 4294967295 2>/dev/null || sendevent {path} 3 57 -1)",
    ]
    if dev["has_btn_touch"]:
        up.append(f"sendevent {path} 1 330 0")
    up.append(f"sendevent {path} 0 0 0")

    rc1, o1 = shell(" && ".join(down), check_rc=False)
    time.sleep(hold_sec)
    rc2, o2 = shell(" && ".join(up), check_rc=False)
    return (rc1, o1), (rc2, o2), (ax, ay)


def sha256_hex(s):
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def parse_token_secret(tok):
    m = re.match(r"^avs_([a-z0-9]{4,12})_([A-Za-z0-9]{16,64})$", tok.strip())
    return (m.group(1), m.group(2)) if m else ("main", tok.strip())


def run_suite(sdk: int, out_dir: Path):
    global IS_ROOT_ADB

    # Speed up NEEDS_CONFIRM wait timeout to 2s so smoke tests finish quickly.
    w_out = ci_hook("wait_ms", "2000", "files/ci-wait.txt")
    print("wait_ms override:", w_out, flush=True)

    # Open torture on a virtual display so we have an active <vd>.
    c_l, b_l = api_run(["launch", TORTURE], timeout=90)
    vd = find_virtual_display_id()
    check("setup virtual display for attack suite", c_l == 200 and b_l.get("ok") is True and vd is not None and vd > 0, f"http={c_l} code={b_l.get('code')} vd={vd}")
    if not vd:
        return

    # Ensure own app has a task on display 0 for handoff / MOVE_TASK tests.
    shell("am", "start", "-W", "-n", f"{APP}/.ui.MainActivity", check_rc=False)
    time.sleep(0.5)
    own_task_d0 = find_own_task_id_on_d0()
    print(f"own_task_d0={own_task_d0} vd={vd}", flush=True)

    approvals_snap = read_approvals()

    # ── 1) L3 App-side single gate: launch / handoff in / handoff park / unpark -> PROTECTED_APP ─
    for cmd_label, argv in [
        ("launch own package", ["launch", APP]),
        ("handoff in own package", ["handoff", "in", APP]),
        ("handoff park own package", ["handoff", "park", APP]),
        ("handoff unpark own package", ["handoff", "unpark", APP]),
    ]:
        c, b = api_run(argv, timeout=30)
        check(
            f"[L3] {cmd_label} -> PROTECTED_APP",
            c == 200 and b.get("ok") is False and b.get("code") == "PROTECTED_APP" and b.get("next") == "none",
            f"http={c} code={b.get('code')} next={b.get('next')}",
        )
    check("[L3] approvals unchanged after launch/handoff attacks", read_approvals() == approvals_snap)

    # ── 2) L2 Display-0 injection + service-side MOVE_TASK/FOCUS_TASK/REMOVE_TASK + a11y d0 ─
    inj_raw = ci_hook("attack", "inject0", "files/ci-attack.txt")
    inj = json.loads(inj_raw) if inj_raw.startswith("{") else {}
    check(
        "[L2] display-0 injection (DRAG/KEY/TEXT/COMBO/POINTER) all refused",
        all(inj.get(k) is False for k in ("inject_drag", "inject_key", "inject_text", "inject_combo", "pointer")),
        str(inj),
    )

    if own_task_d0 is not None:
        st_raw = ci_hook("attack", f"service_tasks:{own_task_d0}:{vd}", "files/ci-attack.txt")
        st = json.loads(st_raw) if st_raw.startswith("{") else {}
        check(
            "[L3/service] MOVE_TASK own pkg -> vd, FOCUS_TASK d0/null, REMOVE_TASK d0 all refused",
            st.get("move_own_to_vd") is False
            and "PROTECTED_APP" in str(st.get("move_own_msg", ""))
            and st.get("focus_d0") is False
            and st.get("focus_null") is False
            and st.get("remove_d0") is False,
            str(st),
        )
    else:
        check("[L3/service] found own task on d0 for MOVE_TASK test", False, "own_task_d0=None")

    a0_raw = ci_hook("attack", "a11y_d0", "files/ci-attack.txt")
    a0 = json.loads(a0_raw) if a0_raw.startswith("{") else {}
    check("[a11y] rootNodesForDisplay(0) returns 0 roots", a0.get("connected") is True and a0.get("d0_roots") == 0, str(a0))
    check("[L2/service] approvals unchanged", read_approvals() == approvals_snap)

    # ── 3) Fresh token #1 -> NEEDS_CONFIRM + Operit & MCP handshakes while pending ─
    tok1_id, tok1_secret = make_fresh_token("ci-hold", "operate")
    c_new, b_new = api_run(["apps"], token=tok1_secret, session="s6-t1", timeout=30)
    conf1 = b_new.get("confirm") if isinstance(b_new, dict) and isinstance(b_new.get("confirm"), dict) else {}
    cid1 = conf1.get("id", "")
    check(
        "[Risk] fresh token triggers NEEDS_CONFIRM (NEW_CLIENT)",
        c_new == 200
        and b_new.get("ok") is False
        and b_new.get("code") == "NEEDS_CONFIRM"
        and b_new.get("next") == "ask_user"
        and conf1.get("risk") == "NEW_CLIENT"
        and cid1.startswith("c_")
        and int(conf1.get("expires_in_s", 0)) > 0,
        f"http={c_new} code={b_new.get('code')} confirm={conf1}",
    )

    # Operit toolkit handshake while cid1 is pending: /v2/ping?me=&n= + status + guide.
    tid_p, sec_p = parse_token_secret(tok1_secret)
    key_hex = sha256_hex(sec_p)
    nonce = "operitnonce12345"
    want_you = sha256_hex(f"{key_hex}\n{nonce}\nping\n{key_hex}")
    cp, bp = http_json("GET", f"/v2/ping?me={tid_p}&n={nonce}", timeout=15)
    cs_op, bs_op = api_run("status", token=tok1_secret, session="operit", timeout=20)
    cg_op, bg_op = api_run("guide", token=tok1_secret, session="operit", timeout=20)
    check(
        "[Handshake] Operit ping + status + guide succeed while confirm is pending",
        cp == 200 and bp.get("you") == want_you and cs_op == 200 and bs_op.get("ok") is True and cg_op == 200 and bg_op.get("ok") is True,
        f"ping={cp}/{bp.get('you') == want_you} status={cs_op}/{bs_op.get('ok')} guide={cg_op}/{bg_op.get('ok')}",
    )

    # MCP protocol handshake while cid1 is pending: initialize + tools/list + vscreen_status + vscreen("help").
    cm1, bm1 = http_json("POST", "/mcp", {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}}, token=tok1_secret, timeout=20)
    cm2, bm2 = http_json("POST", "/mcp", {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, token=tok1_secret, timeout=20)
    cm3, bm3 = http_json("POST", "/mcp", {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "vscreen_status", "arguments": {}}}, token=tok1_secret, timeout=20)
    cm4, bm4 = http_json("POST", "/mcp", {"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "vscreen", "arguments": {"command": "help"}}}, token=tok1_secret, timeout=20)
    check(
        "[Handshake] MCP initialize + tools/list + vscreen_status + help succeed while confirm is pending",
        cm1 == 200 and bm1.get("result", {}).get("serverInfo", {}).get("name") == "agent-vscreen"
        and cm2 == 200 and len(bm2.get("result", {}).get("tools", [])) > 5
        and cm3 == 200 and bm3.get("result", {}).get("isError") is False
        and cm4 == 200 and bm4.get("result", {}).get("isError") is False,
        f"init={cm1} list={cm2}/{len(bm2.get('result', {}).get('tools', []))} status_err={bm3.get('result', {}).get('isError')} help_err={bm4.get('result', {}).get('isError')}",
    )

    # ── 4) L1: external am start blocked by exported=false; internal launch on <vd> finishes immediately + audit ─
    rc_ext, out_ext = shell("am", "start", "-W", "--display", str(vd), "-n", STRONG_ACT, "--es", "confirm_id", cid1, check_rc=False)
    check(
        "[L1/manifest] external shell am start StrongConfirmActivity blocked (exported=false)",
        rc_ext != 0 or "Permission Denial" in out_ext or "SecurityException" in out_ext,
        f"rc={rc_ext} out={out_ext[:120]!r}",
    )
    ci_hook("attack", f"open_confirm_vd:{cid1}:{vd}", "files/ci-attack.txt")
    time.sleep(1.2)
    still_on_vd = activity_on_display(vd, "StrongConfirmActivity")
    audits_l1 = [a for a in read_audit_lines() if a.get("what") == "strong_confirm_wrong_display"]
    check(
        "[L1] StrongConfirmActivity on virtual display finishes immediately and writes strong_confirm_wrong_display",
        (not still_on_vd) and len(audits_l1) >= 1 and read_approvals() == approvals_snap,
        f"still_on_vd={still_on_vd} audits={len(audits_l1)}",
    )

    # ── 5) Main-screen focused EditText vs `act "type ..."` on virtual display (6.10 #3) ─
    t_mi = latest_atk_t()
    shell("am", "start", "-W", "--display", "0", "-f", "0x10000000", "-n", MAIN_INPUT_ACT, check_rc=False)
    ready_mi = wait_atk_event("main_input_ready", t_mi, timeout=8.0)
    time.sleep(0.4)
    api_run(["act", "type leaked_secret_text"], timeout=45)
    t_q = latest_atk_t()
    shell("am", "broadcast", "-a", "com.kilon.avstorture.QUERY_MAIN_INPUT", "-p", TORTURE, check_rc=False)
    st_mi = wait_atk_event("main_input_state", t_q, timeout=5.0)
    leaked_txt = st_mi["data"].get("text") if st_mi else "MISSING"
    check(
        "[a11y/typing] act type never writes into focused main-screen EditText",
        ready_mi is not None
        and ready_mi["data"].get("displayId") == 0
        and st_mi is not None
        and st_mi["data"].get("displayId") == 0
        and leaked_txt == "",
        f"ready={ready_mi} state={st_mi}",
    )

    # ── 6) StrongConfirmActivity on d0: act type/scroll, a11y CLICK/LONG_CLICK, adb swipe 3500 ─
    shell("input", "keyevent", "KEYCODE_WAKEUP", check_rc=False)
    shell("wm", "dismiss-keyguard", check_rc=False)
    ci_hook("attack", f"open_confirm:{cid1}", "files/ci-attack.txt")
    btn_pos = find_confirm_button_center(want_id=cid1, want_bio=False)
    check("located StrongConfirmActivity confirm button on display 0", btn_pos is not None, str(btn_pos))

    # 6a) act "type ...; scroll down" while StrongConfirmActivity is in front on d0.
    api_run(["act", "type attack_confirm; scroll down"], timeout=45)
    check(
        "[a11y] act type/scroll against StrongConfirmActivity on d0 has no effect",
        read_approvals() == approvals_snap and activity_on_display(0, "StrongConfirmActivity"),
        f"on_d0={activity_on_display(0, 'StrongConfirmActivity')}",
    )

    # 6b) External AccessibilityService ACTION_CLICK & ACTION_LONG_CLICK on confirm button (6.9).
    _, prev_a11y = shell("settings", "get", "secure", "enabled_accessibility_services", check_rc=False)
    prev_a11y = "" if prev_a11y in ("", "null") else prev_a11y
    combined = f"{prev_a11y}:{ATTACK_A11Y}" if prev_a11y and ATTACK_A11Y not in prev_a11y else (prev_a11y or ATTACK_A11Y)
    try:
        t_ac = latest_atk_t()
        shell("settings", "put", "secure", "enabled_accessibility_services", combined, check_rc=False)
        shell("settings", "put", "secure", "accessibility_enabled", "1", check_rc=False)
        conn = wait_atk_event("attack_a11y_connected", t_ac, timeout=12.0)
        # Re-ensure StrongConfirmActivity is top on d0.
        ci_hook("attack", f"open_confirm:{cid1}", "files/ci-attack.txt")
        time.sleep(0.8)
        t_ck = latest_atk_t()
        shell("am", "broadcast", "-a", "com.kilon.avstorture.ATTACK_A11Y_CLICK", "-p", TORTURE, check_rc=False)
        res_ck = wait_atk_event("a11y_click_result", t_ck, timeout=10.0)
        audits_a11y = [a for a in read_audit_lines() if a.get("what") == "strong_confirm_a11y_ignored"]
        check(
            "[a11y/6.9] external a11y CLICK/LONG_CLICK on confirm button rejected with audit",
            conn is not None
            and res_ck is not None
            and res_ck["data"].get("found") is True
            and res_ck["data"].get("clickOk") is False
            and res_ck["data"].get("longClickOk") is False
            and len(audits_a11y) >= 1
            and read_approvals() == approvals_snap,
            f"conn={conn is not None} res={res_ck} audits={len(audits_a11y)}",
        )
    finally:
        if prev_a11y:
            shell("settings", "put", "secure", "enabled_accessibility_services", prev_a11y, check_rc=False)

    # 6c) No-lockscreen adb shell input swipe x y x y 3500 -> rejected + strong_confirm_l6_ignored audit (6.8).
    if btn_pos is not None:
        bx, by, _ = btn_pos
        ci_hook("attack", f"open_confirm:{cid1}", "files/ci-attack.txt")
        time.sleep(0.6)
        shell("input", "-d", "0", "swipe", str(bx), str(by), str(bx), str(by), "3500", check_rc=False)
        time.sleep(0.6)
        audits_l6 = [a for a in read_audit_lines() if a.get("what") == "strong_confirm_l6_ignored"]
        check(
            "[L6] adb shell input swipe 3500ms on confirm button ignored with strong_confirm_l6_ignored audit",
            len(audits_l6) >= 1 and read_approvals() == approvals_snap,
            f"audits_l6={len(audits_l6)} approvals_len={len(read_approvals())}",
        )

    # ── 7) API 34 positive PIN BiometricPrompt auto-test + screenrecord (6.8 / 6.10 #4) ─
    if sdk >= 34:
        tok2_id, tok2_secret = make_fresh_token("ci-pin", "operate")
        c_p1, b_p1 = api_run(["apps"], token=tok2_secret, session="s6-pin", timeout=30)
        conf2 = b_p1.get("confirm") if isinstance(b_p1, dict) and isinstance(b_p1.get("confirm"), dict) else {}
        cid2 = conf2.get("id", "")
        check(
            "[PIN] fresh token #2 gets NEEDS_CONFIRM (NEW_CLIENT)",
            c_p1 == 200 and b_p1.get("code") == "NEEDS_CONFIRM" and cid2.startswith("c_"),
            f"http={c_p1} code={b_p1.get('code')} confirm={conf2}",
        )

        pin = "1234"
        rc_set, out_set = shell("locksettings", "set-pin", pin, check_rc=False)
        print(f"locksettings set-pin: rc={rc_set} out={out_set!r}", flush=True)
        shell("input", "keyevent", "KEYCODE_WAKEUP", check_rc=False)
        shell("wm", "dismiss-keyguard", check_rc=False)
        remote_mp4 = "/sdcard/s6-pin-confirm.mp4"
        shell("rm", "-f", remote_mp4, check_rc=False)
        rec_proc = subprocess.Popen(
            ["adb", "shell", "screenrecord", "--time-limit", "60", remote_mp4],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        time.sleep(1.0)
        pin_approved_rec = None
        try:
            ci_hook("attack", f"open_confirm:{cid2}", "files/ci-attack.txt")
            pbtn = find_confirm_button_center(want_id=cid2, want_bio=True) or btn_pos
            print(f"PIN confirm button: {pbtn}", flush=True)
            if pbtn is not None:
                shell("input", "-d", "0", "tap", str(pbtn[0]), str(pbtn[1]), check_rc=False)
                time.sleep(2.0)
                shell("input", "text", pin, check_rc=False)
                time.sleep(0.35)
                shell("input", "keyevent", "KEYCODE_ENTER", check_rc=False)
                deadline = time.monotonic() + 8.0
                while time.monotonic() < deadline:
                    recs = read_approvals()
                    pin_approved_rec = next(
                        (r for r in recs if r.get("token") == tok2_id and r.get("risk") == "NEW_CLIENT" and r.get("by") == "biometric"),
                        None,
                    )
                    if pin_approved_rec is not None:
                        break
                    time.sleep(0.3)
        finally:
            _, rec_pids = shell("pidof", "screenrecord", check_rc=False)
            if rec_pids:
                for p in rec_pids.split():
                    shell("kill", "-2", p, check_rc=False)
            else:
                shell("pkill", "-INT", "screenrecord", check_rc=False)
            try:
                rec_proc.communicate(timeout=12)
            except subprocess.TimeoutExpired:
                rec_proc.kill()
            time.sleep(1.0)
            local_mp4 = out_dir / "s6-pin-confirm.mp4"
            rc_pull, _ = run("adb", "pull", remote_mp4, str(local_mp4), check_rc=False)
            mp4_bytes = local_mp4.stat().st_size if local_mp4.is_file() else 0
            print(f"PIN screenrecord pulled: rc={rc_pull} bytes={mp4_bytes}", flush=True)
            rc_clr, out_clr = shell("locksettings", "clear", "--old", pin, check_rc=False)
            print(f"locksettings clear: rc={rc_clr} out={out_clr!r}", flush=True)

        audits_pin = [
            a for a in read_audit_lines()
            if a.get("what") == "strong_confirm_approved" and cid2 in str(a.get("detail", "")) and "by=biometric" in str(a.get("detail", ""))
        ]
        c_p2, b_p2 = api_run(["apps"], token=tok2_secret, session="s6-pin", timeout=30)
        check(
            "[PIN/6.10 #4] BiometricPrompt PIN confirms token #2 (approvals by=biometric + audit + resend ok + mp4)",
            rc_set == 0
            and pin_approved_rec is not None
            and len(audits_pin) >= 1
            and c_p2 == 200 and b_p2.get("ok") is True
            and mp4_bytes > 0,
            f"set_rc={rc_set} rec={pin_approved_rec} audits={len(audits_pin)} resend={c_p2}/{b_p2.get('ok')}/{b_p2.get('code')} mp4={mp4_bytes}",
        )

    # ── 8) Positive no-lockscreen 3.5s sendevent real-touch hold (API 30 & 34, 6.10 #1) ─
    shell("input", "keyevent", "KEYCODE_WAKEUP", check_rc=False)
    shell("wm", "dismiss-keyguard", check_rc=False)
    ci_hook("attack", f"open_confirm:{cid1}", "files/ci-attack.txt")
    hbtn = find_confirm_button_center(want_id=cid1, want_bio=False) or btn_pos
    w0, h0 = display0_size()

    run("adb", "root", check_rc=False)
    run("adb", "wait-for-device", check_rc=False)
    time.sleep(2.0)
    IS_ROOT_ADB = True
    parts_after = read_app_file("files/ci.txt").split()
    fwd_port = int(parts_after[1]) if len(parts_after) == 2 and parts_after[1].isdigit() and int(parts_after[1]) > 0 else (GATE_PORT or 18765)
    run("adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{fwd_port}", check_rc=False)

    dev = parse_evdev_touch()
    hold_rec = None
    if dev is not None and hbtn is not None:
        ci_hook("attack", f"open_confirm:{cid1}", "files/ci-attack.txt")
        hbtn = find_confirm_button_center(want_id=cid1, want_bio=False) or hbtn
        time.sleep(0.5)
        r_down, r_up, mapped = sendevent_hold_px(dev, hbtn[0], hbtn[1], w0, h0, hold_sec=3.6, tid=601)
        print(f"sendevent 3.6s hold on {dev['path']} ({dev['name']}) px=({hbtn[0]},{hbtn[1]})/{w0}x{h0} ev={mapped}: down={r_down} up={r_up}", flush=True)
        deadline = time.monotonic() + 5.0
        while time.monotonic() < deadline:
            recs = read_approvals()
            hold_rec = next(
                (r for r in recs if r.get("token") == tok1_id and r.get("risk") == "NEW_CLIENT" and r.get("by") == "hold"),
                None,
            )
            if hold_rec is not None:
                break
            time.sleep(0.25)

    audits_hold = [
        a for a in read_audit_lines()
        if a.get("what") == "strong_confirm_approved" and cid1 in str(a.get("detail", "")) and "by=hold" in str(a.get("detail", ""))
    ]
    c_h2, b_h2 = api_run(["apps"], token=tok1_secret, session="s6-t1", timeout=30)
    check(
        "[Hold/6.10 #1] sendevent real-touch 3.5s hold confirms token #1 (approvals by=hold + audit + resend ok)",
        dev is not None
        and hbtn is not None
        and hold_rec is not None
        and len(audits_hold) >= 1
        and c_h2 == 200 and b_h2.get("ok") is True,
        f"dev={dev['path'] if dev else None} hbtn={hbtn} rec={hold_rec} audits={len(audits_hold)} resend={c_h2}/{b_h2.get('ok')}/{b_h2.get('code')}",
    )

    # Clean up virtual display.
    api_run(["release"], timeout=30)
    (out_dir / f"approvals-api{sdk}.json").write_text(json.dumps(read_approvals(), indent=2, ensure_ascii=False), encoding="utf-8")
    (out_dir / f"audit-api{sdk}.jsonl").write_text("\n".join(json.dumps(x, ensure_ascii=False) for x in read_audit_lines()) + "\n", encoding="utf-8")


def main():
    global MAIN_TOKEN, GATE_PORT
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", nargs="?", default="test-apks/app-debug.apk")
    ap.add_argument("--out", default="confirm-out")
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    apk = Path(args.apk)

    sdk = setup_shizuku(apk)
    MAIN_TOKEN, GATE_PORT = seed()
    print(f"seeded port={GATE_PORT} sdk={sdk}", flush=True)
    if not wait_gate_and_selftest(120):
        raise SystemExit("FAIL gate / ocr_selftest never ready")

    run_suite(sdk, out_dir)
    print("SUMMARY", "all passed" if not FAILED else "failed: " + ", ".join(FAILED), flush=True)
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
