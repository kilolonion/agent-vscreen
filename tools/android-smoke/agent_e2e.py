"""End-to-end check on an emulator: an agent drives the background screen through the real HTTP API.

Usage (inside android-emulator-runner): python3 tools/android-smoke/agent_e2e.py test-apks/app-debug.apk [--root|--root-uid0]
Needs test-apks/shizuku.apk (Shizuku way in) or test-apks/avsu (root way in: the CI stand-in root manager built from
tools/android-smoke/avsu.c; --root-uid0 makes it refuse `su <uid>`, so the service runs as uid 0). Each check prints PASS/FAIL; the script exits non-zero if any check failed.
What it guards: the service comes up with Shizuku, `launch` builds a screen and opens an app there, the user's main
screen is not touched, `look` and `act` work on it, `release` closes it, and the app process never crashes or loses a
background thread on the way (status background_thread_deaths).
1.4.2-beta6 adds the session and control scenarios: `launch --keep` keeps the page, park / unpark, Stop and takeover
refuse the agent until the hand-back, a takeover outlives the agent's turn, and a long press the user stops midway is
cancelled (no tap reaches the app).
"""
import json
import re
import subprocess
import sys
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

APP = "com.kilon.agentvscreen"
MANAGER = "moe.shizuku.privileged.api"
ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
ROOT = "--root" in sys.argv or "--root-uid0" in sys.argv
ROOT_UID0 = "--root-uid0" in sys.argv
APK = Path(ARGS[0] if ARGS else "test-apks/app-debug.apk")
AVSU = APK.parent / "avsu"
SU = "/data/local/tmp/avsu-bin/su"
SHIZUKU = APK.parent / "shizuku.apk"
FAILED = []


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
    seen = set()
    for _ in range(tries):
        if "dumped" in shell("uiautomator", "dump", "/data/local/tmp/w.xml").lower():
            anr_wait = None
            for node in ET.fromstring(shell("cat", "/data/local/tmp/w.xml")).iter("node"):
                a = node.attrib
                label = (a.get("text") or a.get("content-desc", "")).strip()
                if label:
                    seen.add(a.get("package", "?") + ":" + label)
                if a.get("package") == package and label.casefold() in want:
                    tap_bounds(a["bounds"])
                    print("tapped", package, label, flush=True)
                    return True
                if a.get("package") == "android" and label in ("Wait", "等待"):
                    anr_wait = a["bounds"]
            # A slow emulator often shows "<app> isn't responding" over everything; wait it out like a person would.
            if anr_wait:
                tap_bounds(anr_wait)
                print("dismissed a system 'not responding' dialog", flush=True)
                seen.clear()
        time.sleep(1)
    print("no", labels, "in", package, "; on screen:", sorted(seen)[:30], flush=True)
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


def setup_root():
    """The root way in, no Shizuku on the phone: a root manager stand-in, then the user taps "use root" in setup."""
    print("SDK", shell("getprop", "ro.build.version.sdk"), "ABIs", shell("getprop", "ro.product.cpu.abilist"), flush=True)
    run("adb", "root")
    run("adb", "wait-for-device")
    time.sleep(2)
    shell("setenforce", "0")
    shell("mkdir", "-p", "/data/local/tmp/avsu-bin")
    run("adb", "push", str(AVSU), SU)
    shell("chmod", "755", "/data/local/tmp/avsu-bin", SU)
    env = "AVSU_NO_UID=1 " if ROOT_UID0 else ""
    shell("sh", "-c", "'%s%s --daemon </dev/null >/dev/null 2>&1'" % (env, SU))
    shell("setprop", "debug.avs.su", SU)
    print("stand-in su:", shell(SU, "-c", "id"), flush=True)
    shell("logcat", "-c")
    try:
        run("adb", "install", "-r", "-g", str(APK), timeout=240)
    except subprocess.CalledProcessError as e:
        raise SystemExit("FAIL install: " + (e.output or "").strip())
    shell("settings", "put", "secure", "enabled_accessibility_services", APP + "/.host.VScreenAccessibilityService")
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    shell("am", "start", "-W", "-n", APP + "/.ui.MainActivity", "--es", "page", "SETUP")
    check("app starts on Shizuku without Shizuku installed", wait_priv(("shizuku", "NOT_INSTALLED"), 45), str(priv_state()))
    # A slow emulator can swallow a tap (a "not responding" dialog came and went): tap again until the choice shows.
    for _ in range(3):
        press_text(APP, ["Rooted? Use root instead", "手机已 root？改用 Root"])
        if wait_priv(("root", "READY"), 20) or (priv_state() or ("",))[0] == "root":
            break
    check("root granted and service connected", wait_priv(("root", "READY"), 60), str(priv_state()))
    print(shell("logcat", "-d", "-s", "RootBackend:*", "Privileged:*")[-2500:], flush=True)
    rp = root_pid()
    who = shell("ps", "-o", "USER", "-p", rp).splitlines()[-1].strip() if rp else ""
    want = "root" if ROOT_UID0 else "shell"
    check("root service runs as " + want, who == want, "pid=%s user=%s" % (rp, who))
    shell("input", "keyevent", "KEYCODE_HOME")


def setup():
    if ROOT:
        return setup_root()
    print("SDK", shell("getprop", "ro.build.version.sdk"), "ABIs", shell("getprop", "ro.product.cpu.abilist"), flush=True)
    run("adb", "install", "-r", str(SHIZUKU))
    shell("monkey", "-p", MANAGER, "1")
    pkgdir = Path(shell("pm", "path", MANAGER).removeprefix("package:")).parent
    shell(shell("find", str(pkgdir / "lib"), "-name", "libshizuku.so").splitlines()[0])
    shell("input", "keyevent", "KEYCODE_HOME")
    shell("logcat", "-c")
    try:
        run("adb", "install", "-r", "-g", str(APK), timeout=240)
    except subprocess.CalledProcessError as e:
        raise SystemExit("FAIL install: " + (e.output or "").strip())
    # The element list comes from the app's accessibility service, which a user turns on in setup; do the same here.
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


def seed():
    shell("am", "broadcast", "-a", "com.kilon.agentvscreen.CI_SEED", "-p", APP)
    out = ""
    for _ in range(90):
        out = shell("run-as", APP, "cat", "files/ci.txt", "2>/dev/null", "||", "true")
        parts = out.split()
        if len(parts) == 2 and parts[1] != "0":
            run("adb", "forward", "tcp:18766", "tcp:" + parts[1])
            return parts[0]
        time.sleep(1)
    print("ci.txt:", repr(out[:8] + "..." if out else out))
    print(shell("dumpsys", "activity", "services", APP)[-1500:])
    print(shell("logcat", "-d", "-t", "200", "--pid=" + (pid() or "0"))[-4000:])
    raise SystemExit("FAIL service never reported its port")


TOKEN = None


def api(cmd, session="ci", timeout=180):
    try:
        return _api(cmd, session, timeout)
    except Exception as e:  # the step fails, the run goes on, and the app's own log says why
        print("call %r failed: %r" % (cmd, e), flush=True)
        print("pid now:", pid() or "(gone)", flush=True)
        try:
            print(shell("logcat", "-d", "-t", "300", "-v", "brief", "AndroidRuntime:E", "Crash:E", "VScreenRemoteDisplay:*", "BackgroundOperation:*", "ApiServer:*", "*:S")[-6000:], flush=True)
        except Exception as e2:
            print("logcat failed:", e2)
        return {"ok": False, "code": "CALL_FAILED:" + type(e).__name__}


def _api(cmd, session, timeout):
    body = json.dumps({"cmd": cmd, "session": session}).encode()
    req = urllib.request.Request("http://127.0.0.1:18766/v2/run", data=body, method="POST",
                                 headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json", "X-VScreen-Lang": "en"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def pid():
    try:
        return shell("pidof", APP)
    except subprocess.CalledProcessError:
        return ""


def main_display_top():
    """Package of the resumed activity on display 0 (the user's screen), or None if it cannot be read."""
    out = shell("dumpsys", "activity", "activities")
    block = out.split("Display #0", 1)[-1].split("Display #", 1)[0] if "Display #0" in out else ""
    m = re.search(r"(?:topResumedActivity|mResumedActivity|ResumedActivity)[:=]\s*ActivityRecord\{\S+ \S+ ([\w.]+)/", block)
    return m.group(1) if m else None


def root_extras():
    """The root service outlives the app (screens are adopted, no second su prompt loop) and stops when the user goes back to Shizuku."""
    global TOKEN
    rp = root_pid()
    shell("am", "force-stop", APP)
    time.sleep(2)
    check("root service survives the app being killed", rp != "" and root_pid() == rp, "%s -> %s" % (rp, root_pid()))
    # The log is read by the new process's pid, not cleared first: the system may bring the app back before we start it.
    shell("am", "start", "-W", "-n", APP + "/.ui.MainActivity")
    np = pid()
    def mine(tag):
        return shell("logcat", "-d", "--pid=" + (np or "0"), "-s", tag) if np else ""
    ok = False
    for _ in range(45):
        found = re.findall(r"via=(\w+) state=(\w+)", mine("Privileged:I"))
        if found and found[-1] == ("root", "READY"):
            ok = True
            break
        time.sleep(1)
    check("new app process adopts the running root service", ok, "pid %s: %s" % (np, mine("Privileged:I")[-300:]))
    check("adopted, not restarted", root_pid() == rp, "%s -> %s" % (rp, root_pid()))
    reused = mine("RootBackend:*")
    check("launcher said reused", "AVS reused" in reused, reused[-600:])
    shell("input", "keyevent", "KEYCODE_HOME")
    TOKEN = seed()
    r = api("launch com.android.settings", timeout=180)
    check("launch works after the app restarted", r.get("ok") is True, str(r.get("code")))
    r = api("release")
    check("release after restart", r.get("ok") is True, str(r.get("code")))
    shell("am", "broadcast", "-a", "com.kilon.agentvscreen.CI_SEED", "-p", APP, "--es", "via", "shizuku")
    gone = False
    for _ in range(20):
        if not root_pid():
            gone = True
            break
        time.sleep(1)
    check("switching back to Shizuku stops the root service", gone, root_pid())
    check("state follows the switch", wait_priv(("shizuku", "NOT_INSTALLED"), 20), str(priv_state()))


def control(what):
    """Do what the user's buttons do (CiHook): takeover, handback, stop, resume, quiet:<ms>."""
    shell("run-as", APP, "rm", "-f", "files/ci-control.txt")
    shell("am", "broadcast", "-a", "com.kilon.agentvscreen.CI_SEED", "-p", APP, "--es", "control", what)
    out = ""
    for _ in range(40):
        out = shell("run-as", APP, "cat", "files/ci-control.txt", "2>/dev/null", "||", "true")
        if out.startswith("done"):
            break
        time.sleep(0.5)
    print("control", what, "->", out, flush=True)
    return out


def why(r):
    """The code and the start of the message, so a failed step says what the app answered."""
    return "%s %s" % (r.get("code"), (r.get("error") or r.get("text") or "")[:240].replace("\n", " | "))


def activity(r):
    return (r.get("screen") or {}).get("activity")


def session_and_control(target, x=None, y=None):
    tap = 'act "tap %s"' % target.replace('"', "")

    # launch --keep goes back to the page as it was, without rebuilding the screen.
    r = api(tap)
    inner = activity(r)
    r = api("launch com.android.settings --keep")
    check("launch --keep keeps the page", r.get("ok") is True and (inner is None or activity(r) == inner),
          "%s %s -> %s" % (r.get("code"), inner, activity(r)))

    # park / unpark: a parked app is not operable (APP_PARKED) until it is brought back.
    # (No back key first: on the Settings home page it closes the app, and there is nothing left to park.)
    r = api("handoff park com.android.settings")
    check("park", r.get("ok") is True, why(r))
    r = api("act")
    check("look at a parked app says APP_PARKED", r.get("code") == "APP_PARKED", why(r))
    r = api("handoff unpark com.android.settings")
    check("unpark", r.get("ok") is True, why(r))

    # Stop refuses the agent until the user allows it again; Stop also clears parking.
    api("handoff park com.android.settings")
    control("stop")
    r = api(tap)
    check("Stop refuses the agent", r.get("code") == "USER_STOPPED", str(r.get("code")))
    control("resume")
    r = api("act")
    check("after Stop and resume the agent can look", r.get("ok") is True and r.get("code") != "APP_PARKED", str(r.get("code")))

    # A takeover refuses the agent, and the agent's turn ending (quiet window) does not drop it.
    control("quiet:3000")
    control("takeover")
    r = api(tap)
    check("takeover refuses the agent", r.get("code") == "USER_TAKEOVER", str(r.get("code")))
    time.sleep(8)
    out = control("quiet:180000")
    check("takeover outlives the agent's turn", "takeover=None" not in out, out)
    r = api(tap)
    check("still refused while the user holds it", r.get("code") == "USER_TAKEOVER", str(r.get("code")))
    control("handback")
    r = api("act")
    check("after the hand-back the agent can look", r.get("ok") is True, str(r.get("code")))
    r = api(tap)
    # Not refused is the point: the page may have moved on, so a target that is gone (NOT_FOUND) still counts.
    check("and act again", r.get("ok") is True or r.get("code") not in ("USER_TAKEOVER", "USER_STOPPED", "CONTROL_CHANGED", None), why(r))
    # No back key here: on the Settings home page it closes the app, and the press then waits in the system's input
    # queue for a window that never comes (the emulator run showed a 5 s wait, not a gesture that went on).
    before = activity(api("act"))

    # A long press the user stops midway ends with a cancel: the call comes back early and no tap reaches the app
    # (a lift after a long press opens the entry; a cancel does not).
    res = {}
    def press():
        t0 = time.time()
        # At its coordinates: no reading of the screen before the press, so the Stop lands while it is held.
        where = "%d %d" % (x, y) if x is not None and y is not None else target.replace('"', "")
        res["r"] = api('act "press %s 6s"' % where)
        res["s"] = time.time() - t0
    th = threading.Thread(target=press)
    th.start()
    time.sleep(2.5)
    control("stop")
    th.join(60)
    r = res.get("r") or {}
    check("a stopped long press comes back early", r.get("code") in ("USER_STOPPED", "CONTROL_CHANGED") and res.get("s", 99) < 5.5,
          "%s after %.1fs" % (r.get("code"), res.get("s", -1)))
    control("resume")
    r = api("act")
    check("the stopped long press tapped nothing", before is None or activity(r) == before, "%s -> %s" % (before, activity(r)))


def main():
    global TOKEN
    setup()
    TOKEN = seed()
    with urllib.request.urlopen("http://127.0.0.1:18766/v2/ping", timeout=10) as r:
        check("ping", r.status == 200)
    p0 = pid()
    before = main_display_top()

    st = api("status")
    check("status ok", st.get("ok") is True, st.get("code", ""))

    r = api("launch com.android.settings", timeout=180)
    app = (r.get("screen") or {}).get("app")
    # Element lists come back with every launch/act reply; look is only for pictures.
    els = (r.get("screen") or {}).get("elements") or []
    check("launch builds a screen and opens Settings there", r.get("ok") is True and app == "com.android.settings",
          "%s app=%s" % (r.get("code"), app))
    after = main_display_top()
    check("main screen untouched by launch", after is None or after != "com.android.settings" or before == "com.android.settings",
          "before=%s after=%s" % (before, after))

    check("launch reply lists elements", len(els) > 0, "elements=%d" % len(els))
    if not els:
        print(str(r.get("text"))[:3000], flush=True)
    r = api("look")
    check("look returns a picture", r.get("ok") is True and len(r.get("images") or []) > 0,
          "%s images=%d" % (r.get("code"), len(r.get("images") or [])))

    target = next((e.get("text") for e in els if e.get("tappable") and e.get("text")), None)
    if els and not target:
        print("no tappable text; elements:", [(e.get("text"), e.get("source"), e.get("tappable")) for e in els][:20], flush=True)
        target = next((e.get("text") for e in els if e.get("text")), None)
    if target:
        r = api('act "tap %s; key back"' % target.replace('"', ""))
        check("act tap + back", r.get("ok") is True or r.get("code") in ("NO_CHANGE",), "%s on %r" % (r.get("code"), target))
    else:
        check("act target found", False, "no clickable text in look")

    # A second session must be told the screen is busy, not get a second screen.
    r = api("launch com.android.settings", session="other")
    check("second session told BUSY", r.get("code") == "BUSY", str(r.get("code")))

    if target:
        el = next((e for e in els if e.get("text") == target), {})
        session_and_control(target, el.get("x"), el.get("y"))

    r = api("release")
    check("release", r.get("ok") is True, str(r.get("code")))

    st = api("status")
    text = json.dumps(st)
    m = re.search(r'"background_thread_deaths":\s*(\d+)', text)
    check("no background thread died", m is None or m.group(1) == "0", m.group(0) if m else "not in status")
    check("app process survived the run", p0 != "" and pid() == p0, "pid %s -> %s" % (p0, pid()))
    crashes = shell("logcat", "-d", "-b", "crash")
    check("no crash in logcat", APP not in crashes, crashes[-600:])
    if ROOT:
        root_extras()

    print("SUMMARY", "all passed" if not FAILED else "failed: " + ", ".join(FAILED), flush=True)
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
