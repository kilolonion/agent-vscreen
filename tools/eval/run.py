#!/usr/bin/env python3
"""Agent-VScreen 2.0 scripted evaluation runner (stdlib only).

Usage:
  python3 tools/eval/run.py --apk <scene-debug.apk> --out <dir> [--repeat-check]
"""
import argparse
import json
import re
import statistics
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

APP_PKG = "com.kilon.agentvscreen"
TORTURE_PKG = "com.kilon.avstorture"
SHIZUKU_MANAGER = "moe.shizuku.privileged.api"
LOCAL_PORT = 18766
BASE_URL = f"http://127.0.0.1:{LOCAL_PORT}"


def run_cmd(*argv, timeout=120):
    return subprocess.check_output(argv, text=True, timeout=timeout, stderr=subprocess.STDOUT).strip()


def adb_shell(*args, timeout=120):
    return run_cmd("adb", "shell", *args, timeout=timeout)


def shizuku_state():
    states = re.findall(r"state=(\w+)", adb_shell("logcat", "-d", "-s", "ShizukuManager:I"))
    return states[-1] if states else None


def wait_shizuku(expected, secs=60):
    for _ in range(secs):
        if shizuku_state() == expected:
            return True
        time.sleep(1)
    return False


def tap_bounds(bounds):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
    adb_shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def press_text(package, labels, tries=20):
    want = {x.casefold() for x in labels}
    for _ in range(tries):
        if "dumped" in adb_shell("uiautomator", "dump", "/data/local/tmp/w.xml").lower():
            anr_wait = None
            for node in ET.fromstring(adb_shell("cat", "/data/local/tmp/w.xml")).iter("node"):
                a = node.attrib
                label = (a.get("text") or a.get("content-desc", "")).strip()
                if a.get("package") == package and label.casefold() in want:
                    tap_bounds(a["bounds"])
                    return True
                if a.get("package") == "android" and label in ("Wait", "等待"):
                    anr_wait = a["bounds"]
            if anr_wait:
                tap_bounds(anr_wait)
        time.sleep(1)
    return False


def ensure_host_setup(app_apk, shizuku_apk):
    """Ensure Agent-VScreen debug APK + Shizuku are installed and granted."""
    installed = adb_shell("pm", "list", "packages", APP_PKG)
    if f"package:{APP_PKG}" not in installed or shizuku_state() != "READY":
        if shizuku_apk and Path(shizuku_apk).is_file():
            run_cmd("adb", "install", "-r", str(shizuku_apk), timeout=240)
            adb_shell("monkey", "-p", SHIZUKU_MANAGER, "1")
            pkgdir = Path(adb_shell("pm", "path", SHIZUKU_MANAGER).removeprefix("package:")).parent
            lib = adb_shell("find", str(pkgdir / "lib"), "-name", "libshizuku.so").splitlines()[0]
            adb_shell(lib)
            adb_shell("input", "keyevent", "KEYCODE_HOME")
        if app_apk and Path(app_apk).is_file():
            run_cmd("adb", "install", "-r", "-g", str(app_apk), timeout=240)
        adb_shell("settings", "put", "secure", "enabled_accessibility_services", f"{APP_PKG}/.host.VScreenAccessibilityService")
        adb_shell("settings", "put", "secure", "accessibility_enabled", "1")
        adb_shell("am", "start", "-W", "-n", f"{APP_PKG}/.ui.MainActivity", "--es", "page", "SETUP")
        if not wait_shizuku("NEED_PERMISSION", 45) and shizuku_state() != "READY":
            raise RuntimeError(f"App never saw Shizuku: {shizuku_state()}")
        for _ in range(3):
            if shizuku_state() == "READY":
                break
            press_text(APP_PKG, ["Grant Shizuku", "授权 Shizuku", "Grant", "授权"])
            press_text(SHIZUKU_MANAGER, ["Allow all the time", "始终允许", "Allow", "允许"])
            wait_shizuku("READY", 10)
        if not wait_shizuku("READY", 45):
            raise RuntimeError(f"Shizuku not READY: {shizuku_state()}")
        adb_shell("input", "keyevent", "KEYCODE_HOME")


def seed_token():
    adb_shell("am", "broadcast", "-a", "com.kilon.agentvscreen.CI_SEED", "-p", APP_PKG)
    for _ in range(90):
        out = adb_shell("run-as", APP_PKG, "cat", "files/ci.txt", "2>/dev/null", "||", "true")
        parts = out.split()
        if len(parts) == 2 and parts[1] != "0":
            run_cmd("adb", "forward", f"tcp:{LOCAL_PORT}", f"tcp:{parts[1]}")
            return parts[0]
        time.sleep(1)
    raise RuntimeError("Service never reported its port in files/ci.txt")


def ping():
    with urllib.request.urlopen(f"{BASE_URL}/v2/ping", timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def call_api(token, argv, session, timeout=90):
    payload = json.dumps({"args": list(argv), "session": session}).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE_URL}/v2/run",
        data=payload,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "X-VScreen-Lang": "zh",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def version_allows(app_version, since):
    if not since:
        return True
    m_need = re.search(r"beta(\d+)", since)
    if not m_need:
        return True
    need_beta = int(m_need.group(1))
    if app_version.startswith("2.") and "-beta" not in app_version:
        return True
    m_cur = re.search(r"^2\.0\.0-beta(\d+)", app_version)
    if not m_cur:
        return False
    return int(m_cur.group(1)) >= need_beta


def find_scene_display_id():
    out = adb_shell("dumpsys", "activity", "activities")
    cur_display = None
    for line in out.splitlines():
        m = re.search(r"Display #(\d+)", line)
        if m:
            cur_display = int(m.group(1))
        elif cur_display and cur_display != 0 and f"{TORTURE_PKG}/" in line:
            return cur_display
    return None


def read_avst_events(scene):
    raw = adb_shell("logcat", "-d", "-s", "AVST:I", "-v", "raw")
    events = []
    for line in raw.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if obj.get("scene") == scene:
            events.append(obj)
    # The run starts at the scene's first `enter`. Anything logged before it comes from the instance the
    # app's own launch opened, still ticking until the seeded restart replaces it (seen: S09 target_shown
    # between `logcat -c` and the restart), and must not count toward the task or its repeat sequence.
    first_enter = next((i for i, e in enumerate(events) if e.get("ev") == "enter"), None)
    return events[first_enter:] if first_enter is not None else events


def normalize_avst_event(ev):
    """Ignore timestamp `t` and `touch` x/y coordinates for repeat-determinism checks."""
    out = {k: v for k, v in ev.items() if k != "t"}
    if out.get("ev") == "touch" and isinstance(out.get("data"), dict):
        out["data"] = {k: v for k, v in out["data"].items() if k not in ("x", "y")}
    return out


def normalize_avst_sequence(events):
    return [normalize_avst_event(e) for e in events]


def matches_pattern(event_obj, pattern):
    for k, v in pattern.items():
        if k not in event_obj:
            return False
        if isinstance(v, dict):
            if not isinstance(event_obj[k], dict) or not matches_pattern(event_obj[k], v):
                return False
        elif event_obj[k] != v:
            return False
    return True


def evaluate_events(events, expect_list, forbid_list):
    for exp in expect_list:
        if not any(matches_pattern(ev, exp) for ev in events):
            return False
    for fb in forbid_list:
        if any(matches_pattern(ev, fb) for ev in events):
            return False
    return True


def check_baseline_regressions(rows, baseline):
    """Return task IDs that passed in `baseline` but failed or were missing in `rows`."""
    if not baseline or "tasks" not in baseline:
        return []
    cur_ok = {r["id"]: bool(r.get("ok")) for r in rows}
    regressed = []
    for bt in baseline.get("tasks") or []:
        tid = bt.get("id")
        if tid and bt.get("ok") and not cur_ok.get(tid, False):
            regressed.append(tid)
    return regressed


FALSE_QUIET_EVENTS = frozenset({"popup_shown", "snap_back", "fail", "target_missed"})
REFETCH_CMDS = frozenset({"look", "zoom", "frames"})
ACT_VALUE_OPTS = frozenset({
    "--stop", "--on", "--watch", "--timeout", "--wait", "--after", "--detail",
    "--every", "--for", "--for-ms", "--session", "--id", "--retry",
})
ACT_FLAG_OPTS = frozenset({
    "--full", "--fast", "--bare", "--human", "--async", "--keep", "--no-wait", "--hold",
})


def _extras_argv(extras):
    """Convert optional task `extras` dict into `am` CLI flags (--ez / --ei / --es)."""
    if not isinstance(extras, dict):
        return []
    out = []
    for k, v in extras.items():
        if isinstance(v, bool):
            out.extend(["--ez", str(k), "true" if v else "false"])
        elif isinstance(v, int):
            out.extend(["--ei", str(k), str(v)])
        elif v is not None:
            out.extend(["--es", str(k), str(v)])
    return out


def is_quiet_reply(argv, reply):
    """True when an `act` command returned a short (quiet) reply (`loud == []`)."""
    if not argv or argv[0] != "act" or not isinstance(reply, dict):
        return False
    loud = reply.get("loud")
    return isinstance(loud, list) and len(loud) == 0


def is_refetch_command(argv):
    """3.11: `act --full`, `look`/`zoom`/`frames`, or `act` without positional steps."""
    if not argv:
        return False
    sub = argv[0]
    if sub in REFETCH_CMDS:
        return True
    if sub != "act":
        return False
    args = list(argv[1:])
    if "--full" in args:
        return True
    i = 0
    positional = []
    while i < len(args):
        tok = args[i]
        if tok == "--":
            positional.extend(args[i + 1 :])
            break
        if tok.startswith("--"):
            key = tok.split("=", 1)[0]
            if "=" not in tok and key in ACT_VALUE_OPTS and i + 1 < len(args):
                i += 2
                continue
            i += 1
            continue
        positional.append(tok)
        i += 1
    return len(positional) == 0


def count_false_quiet(cmd_records, events):
    """3.11, 3.12 & 5.8 #2: count quiet replies whose wall-clock window `(prev_returned_at, returned_at]`
    contained any AVST event in FALSE_QUIET_EVENTS (`popup_shown`, `snap_back`, `fail`, `target_missed`).
    5.8 #2: a `popup_shown` closed by a fired `--on` rule within the same quiet turn (`rules_fired` non-empty
    and `popup_closed` in `(prev_ret, cur_ret]`) is intentionally handled and does not count as false quiet.
    """
    count = 0
    details = []
    for rec in cmd_records:
        if not rec.get("is_quiet"):
            continue
        cur_ret = int(rec.get("returned_at") or 0)
        if cur_ret <= 0:
            continue
        prev_ret = int(rec.get("prev_returned_at") or 0)
        rule_closed_popup = bool(rec.get("rules_fired")) and any(
            ev.get("ev") == "popup_closed" and prev_ret < int(ev.get("t") or 0) <= cur_ret
            for ev in events
        )
        hits = []
        for ev in events:
            name = ev.get("ev")
            if name not in FALSE_QUIET_EVENTS:
                continue
            if name == "popup_shown" and rule_closed_popup:
                continue
            t = int(ev.get("t") or 0)
            if prev_ret < t <= cur_ret:
                hits.append({"ev": name, "t": t})
        if hits:
            count += 1
            details.append({
                "cmd_index": rec["index"],
                "argv": rec["argv"],
                "from_ms": prev_ret,
                "to_ms": cur_ret,
                "events": hits,
            })
    return count, details


def run_task(token, task, return_events=False):
    task_id = task["id"]
    scene = task["scene"]
    seed_val = int(task.get("seed", 7))
    extras_args = _extras_argv(task.get("extras"))
    max_seconds = float(task.get("max_seconds", 60))
    expect_list = task.get("expect") or []
    forbid_list = task.get("forbid") or []
    session = f"eval_{task_id}"

    adb_shell("am", "force-stop", TORTURE_PKG)
    adb_shell(
        "am", "broadcast", "-W", "--include-stopped-packages",
        "-n", f"{TORTURE_PKG}/.PresetReceiver",
        "--es", "scene", scene,
        "--ei", "seed", str(seed_val),
        *extras_args,
    )
    adb_shell("logcat", "-c")

    turns = 0
    chars = 0
    est_tokens = 0
    image_tokens = 0
    phone_ms = 0
    reply_est_tokens = []
    reply_image_tokens = []
    quiet_replies = 0
    refetch_after_quiet = 0
    loud_reasons = []
    rules_fired_all = []
    reply_texts = []
    step_gestures = []
    cmd_records = []
    prev_returned_at = 0
    prev_was_quiet = False
    timed_out = False
    error_detail = ""
    t0 = time.monotonic()

    try:
        for idx, argv in enumerate(task["commands"]):
            rem = max_seconds - (time.monotonic() - t0)
            if rem <= 0:
                timed_out = True
                error_detail = "max_seconds exceeded"
                break
            if prev_was_quiet and is_refetch_command(argv):
                refetch_after_quiet += 1
            reply = call_api(token, argv, session=session, timeout=max(10, int(rem) + 15))
            turns += 1
            meter = reply.get("meter") or {}
            chars += int(meter.get("chars", 0))
            et = int(meter.get("est_tokens", 0))
            est_tokens += et
            reply_est_tokens.append(et)
            itok = int(meter.get("image_tokens", 0))
            image_tokens += itok
            reply_image_tokens.append(itok)
            phone_ms += int((reply.get("timing") or {}).get("phone_ms", 0))

            cur_returned_at = int(reply.get("returned_at") or 0)
            is_q = is_quiet_reply(argv, reply)
            if is_q:
                quiet_replies += 1
            if argv and argv[0] == "act" and isinstance(reply.get("loud"), list):
                loud_reasons.append(list(reply["loud"]))
            rf = list(reply.get("rules_fired") or []) if isinstance(reply.get("rules_fired"), list) else []
            if rf:
                rules_fired_all.append(rf)
            for st in (reply.get("steps") or []):
                if isinstance(st, dict) and isinstance(st.get("gesture"), dict):
                    step_gestures.append(dict(st["gesture"]))
            reply_texts.append(str(reply.get("text") or ""))
            cmd_records.append({
                "index": idx,
                "argv": list(argv),
                "prev_returned_at": prev_returned_at,
                "returned_at": cur_returned_at,
                "is_quiet": is_q,
                "rules_fired": rf,
            })
            if cur_returned_at > 0:
                prev_returned_at = cur_returned_at
            prev_was_quiet = is_q

            if idx == 0 and argv and argv[0] == "launch":
                disp_id = find_scene_display_id()
                if disp_id:
                    adb_shell("logcat", "-c")
                    adb_shell(
                        "am", "start", "-W", "--activity-clear-top",
                        "--display", str(disp_id),
                        "-n", f"{TORTURE_PKG}/.SceneActivity",
                        "--es", "scene", scene,
                        "--ei", "seed", str(seed_val),
                        *extras_args,
                    )
                    time.sleep(0.15)

        events = read_avst_events(scene)
        settle_deadline = min(t0 + max_seconds, time.monotonic() + 2.0)
        while not evaluate_events(events, expect_list, forbid_list) and time.monotonic() < settle_deadline:
            time.sleep(0.25)
            events = read_avst_events(scene)
    except Exception as exc:
        error_detail = f"{type(exc).__name__}: {exc}"
        events = read_avst_events(scene)
    finally:
        try:
            call_api(token, ["release"], session=session, timeout=30)
        except Exception:
            pass
        adb_shell("am", "force-stop", TORTURE_PKG)

    false_quiet, false_quiet_details = count_false_quiet(cmd_records, events)
    all_text = "\n".join(reply_texts)
    ok = (not timed_out) and (not error_detail) and evaluate_events(events, expect_list, forbid_list)
    row = {
        "id": task_id,
        "scene": scene,
        "seed": seed_val,
        "since": task.get("since"),
        "ok": ok,
        "turns": turns,
        "chars": chars,
        "est_tokens": est_tokens,
        "image_tokens": image_tokens,
        "phone_ms": phone_ms,
        "reply_est_tokens": reply_est_tokens,
        "reply_image_tokens": reply_image_tokens,
        "quiet_replies": quiet_replies,
        "false_quiet": false_quiet,
        "false_quiet_details": false_quiet_details,
        "refetch_after_quiet": refetch_after_quiet,
        "loud_reasons": loud_reasons,
        "rules_fired": rules_fired_all,
        "gestures": step_gestures,
        "reply_has_popup": ("弹出" in all_text) or (" popup [" in all_text),
        "reply_has_toast": ("toast：" in all_text) or ("toast:" in all_text),
        "act_reply_previews": [t[:400] for i, t in enumerate(reply_texts) if i < len(task["commands"]) and task["commands"][i] and task["commands"][i][0] == "act"],
        "error": error_detail,
    }
    if return_events:
        return row, events
    return row


def compute_totals(rows):
    total = len(rows)
    passed = sum(1 for r in rows if r["ok"])
    all_replies = [t for r in rows for t in r.get("reply_est_tokens", [])]
    return {
        "total": total,
        "passed": passed,
        "failed": total - passed,
        "pass_rate": round(passed / total, 4) if total else 0.0,
        "turns": sum(r["turns"] for r in rows),
        "chars": sum(r["chars"] for r in rows),
        "est_tokens": sum(r["est_tokens"] for r in rows),
        "image_tokens": sum(r["image_tokens"] for r in rows),
        "phone_ms": sum(r["phone_ms"] for r in rows),
        "median_reply_est_tokens": round(statistics.median(all_replies), 1) if all_replies else 0.0,
        "quiet_replies": sum(int(r.get("quiet_replies", 0)) for r in rows),
        "false_quiet": sum(int(r.get("false_quiet", 0)) for r in rows),
        "refetch_after_quiet": sum(int(r.get("refetch_after_quiet", 0)) for r in rows),
    }


def compute_beta3_comparison(rows, baseline):
    """3.10, 3.12 & 3.13: compare `since == beta3` (b) tasks against same-scene (a) tasks.
    Reports both:
      - All replies (including `launch`) vs `baseline.json`;
      - `act`-only replies (`[1:]`): text median vs `baseline.json` `[1:]`,
        image token sum vs same-run same-scene `a` tasks `[1:]` (baseline has no per-reply image breakdown).
    """
    b_rows = [r for r in rows if r.get("since") == "beta3"]
    if not b_rows or not baseline or "tasks" not in baseline:
        return None
    base_by_scene = {t["scene"]: t for t in (baseline.get("tasks") or []) if "scene" in t}
    cur_a_by_scene = {r["scene"]: r for r in rows if not r.get("since") and "scene" in r}
    pairs = []
    base_replies = []
    new_replies = []
    base_act_replies = []
    new_act_replies = []
    base_img = 0
    new_img = 0
    same_run_a_act_img = 0
    new_act_img = 0
    for br in b_rows:
        ar = base_by_scene.get(br["scene"])
        if not ar:
            continue
        cur_ar = cur_a_by_scene.get(br["scene"])
        ar_rep = ar.get("reply_est_tokens") or []
        br_rep = br.get("reply_est_tokens") or []
        ar_act_rep = ar_rep[1:]
        br_act_rep = br_rep[1:]
        base_replies.extend(ar_rep)
        new_replies.extend(br_rep)
        base_act_replies.extend(ar_act_rep)
        new_act_replies.extend(br_act_rep)
        base_img += int(ar.get("image_tokens", 0))
        new_img += int(br.get("image_tokens", 0))
        cur_ar_act_img_val = sum((cur_ar.get("reply_image_tokens") or [])[1:]) if cur_ar else 0
        br_act_img_val = sum((br.get("reply_image_tokens") or [])[1:])
        same_run_a_act_img += cur_ar_act_img_val
        new_act_img += br_act_img_val
        pairs.append({
            "scene": br["scene"],
            "base_id": ar["id"],
            "new_id": br["id"],
            "ok": bool(br["ok"]),
            "base_median": round(statistics.median(ar_rep), 1) if ar_rep else 0.0,
            "new_median": round(statistics.median(br_rep), 1) if br_rep else 0.0,
            "base_act_median": round(statistics.median(ar_act_rep), 1) if ar_act_rep else 0.0,
            "new_act_median": round(statistics.median(br_act_rep), 1) if br_act_rep else 0.0,
            "base_est_tokens": int(ar.get("est_tokens", 0)),
            "new_est_tokens": int(br.get("est_tokens", 0)),
            "base_image_tokens": int(ar.get("image_tokens", 0)),
            "new_image_tokens": int(br.get("image_tokens", 0)),
            "same_run_a_act_image_tokens": cur_ar_act_img_val,
            "new_act_image_tokens": br_act_img_val,
            "quiet_replies": int(br.get("quiet_replies", 0)),
            "false_quiet": int(br.get("false_quiet", 0)),
            "loud_reasons": br.get("loud_reasons") or [],
        })
    if not pairs:
        return None
    base_med = round(statistics.median(base_replies), 1) if base_replies else 0.0
    new_med = round(statistics.median(new_replies), 1) if new_replies else 0.0
    med_drop = round((base_med - new_med) / base_med, 4) if base_med > 0 else 0.0
    img_drop = round((base_img - new_img) / base_img, 4) if base_img > 0 else 0.0

    base_act_med = round(statistics.median(base_act_replies), 1) if base_act_replies else 0.0
    new_act_med = round(statistics.median(new_act_replies), 1) if new_act_replies else 0.0
    act_med_drop = round((base_act_med - new_act_med) / base_act_med, 4) if base_act_med > 0 else 0.0
    act_img_drop = round((same_run_a_act_img - new_act_img) / same_run_a_act_img, 4) if same_run_a_act_img > 0 else 0.0

    # 3.10 scene checks (S02 popup BEFORE+弹出, S12 carousel no BEFORE/AFTER, S07 toast event)
    by_id = {r["id"]: r for r in rows}
    s02 = by_id.get("T02a")
    s12a = by_id.get("T12a")
    s12b = by_id.get("T12b")
    s07a = by_id.get("T07a")
    s07b = by_id.get("T07b")

    def flat_reasons(row):
        return [c for sub in (row.get("loud_reasons") or []) for c in sub] if row else []

    scene_checks = {
        "S02_before_and_popup": bool(s02 and ("BEFORE" in flat_reasons(s02)) and s02.get("reply_has_popup")),
        "S02_loud": flat_reasons(s02),
        "S02_has_popup": bool(s02 and s02.get("reply_has_popup")),
        "S12a_no_before_after": bool(s12a and "BEFORE" not in flat_reasons(s12a) and "AFTER" not in flat_reasons(s12a)),
        "S12b_no_before_after": bool(s12b and "BEFORE" not in flat_reasons(s12b) and "AFTER" not in flat_reasons(s12b)),
        "S12a_loud": flat_reasons(s12a),
        "S12b_loud": flat_reasons(s12b),
        "S07a_has_toast": bool(s07a and s07a.get("reply_has_toast")),
        "S07b_has_toast": bool(s07b and s07b.get("reply_has_toast")),
    }
    return {
        "pairs": pairs,
        "base_median_reply_est_tokens": base_med,
        "new_median_reply_est_tokens": new_med,
        "median_reply_drop_pct": med_drop,
        "base_image_tokens": base_img,
        "new_image_tokens": new_img,
        "image_tokens_drop_pct": img_drop,
        "base_act_median_reply_est_tokens": base_act_med,
        "new_act_median_reply_est_tokens": new_act_med,
        "act_median_reply_drop_pct": act_med_drop,
        "same_run_a_act_image_tokens": same_run_a_act_img,
        "new_act_image_tokens": new_act_img,
        "act_image_tokens_drop_pct": act_img_drop,
        "scene_checks": scene_checks,
    }


def fmt_diff(val, is_float=False):
    if is_float:
        return f"{val:+.2%}"
    return f"{val:+d}"


def render_markdown(app_version, rows, skipped, totals, diff, regressed, repeat_rows=None, gate=None, beta3_cmp=None):
    lines = [
        f"# Agent-VScreen 评测结果（{app_version}）",
        "",
        "| 任务 | 场景 | 结果 | 误静默 | 轮数 | 总字数 | 总估算 token | 图片 token | 手机端耗时 (ms) |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for r in rows:
        status = "成功" if r["ok"] else "失败"
        lines.append(
            f"| {r['id']} | {r['scene']} | {status} | {r.get('false_quiet', 0)} | {r['turns']} | {r['chars']} | "
            f"{r['est_tokens']} | {r['image_tokens']} | {r['phone_ms']} |"
        )
    lines.append(
        f"| **合计** | **{totals['passed']}/{totals['total']} ({totals['pass_rate']:.1%})** | "
        f"**{'通过' if totals['failed'] == 0 and totals.get('false_quiet', 0) == 0 else '有失败'}** | "
        f"**{totals.get('false_quiet', 0)}** | **{totals['turns']}** | **{totals['chars']}** | "
        f"**{totals['est_tokens']}** | **{totals['image_tokens']}** | **{totals['phone_ms']}** |"
    )
    if diff is not None:
        gate_txt = "无退化" if not regressed else f"退化: {', '.join(regressed)}"
        lines.append(
            f"| **与 baseline 差值** | **{fmt_diff(diff['pass_rate'], is_float=True)}** | **{gate_txt}** | "
            f"— | **{fmt_diff(diff['turns'])}** | **{fmt_diff(diff['chars'])}** | "
            f"**{fmt_diff(diff['est_tokens'])}** | **{fmt_diff(diff['image_tokens'])}** | "
            f"**{fmt_diff(diff['phone_ms'])}** |"
        )
    lines.extend([
        "",
        f"短回复计量：`quiet_replies={totals.get('quiet_replies', 0)}` · "
        f"`false_quiet={totals.get('false_quiet', 0)}` · "
        f"`refetch_after_quiet={totals.get('refetch_after_quiet', 0)}` · "
        f"`median_reply_est_tokens={totals.get('median_reply_est_tokens', 0.0)}`",
    ])
    if skipped:
        lines.extend(["", f"跳过未到阶段的任务（{len(skipped)} 个）：" + ", ".join(f"{s['id']}({s['since']})" for s in skipped)])

    if beta3_cmp is not None:
        lines.extend([
            "",
            "## 新写法对比（beta3 `b` 任务 vs 同场景 `a` 任务）",
            "",
            "| 场景 | baseline | beta3 | 结果 | act 中位 token (base → b) | act 图 token (本次 a → b) | 全回复中位 (base → b) | 全回复图 (base → b) | 短回复 | loud 原因 |",
            "| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |",
        ])
        for p in beta3_cmp["pairs"]:
            loud_str = "; ".join(",".join(x) if x else "quiet" for x in p["loud_reasons"]) or "-"
            lines.append(
                f"| {p['scene']} | {p['base_id']} | {p['new_id']} | {'成功' if p['ok'] else '失败'} | "
                f"{p['base_act_median']} → {p['new_act_median']} | "
                f"{p['same_run_a_act_image_tokens']} → {p['new_act_image_tokens']} | "
                f"{p['base_median']} → {p['new_median']} | "
                f"{p['base_image_tokens']} → {p['new_image_tokens']} | "
                f"{p['quiet_replies']} | `{loud_str}` |"
            )
        lines.extend([
            "",
            f"- **仅 `act` 口径（3.13）文字中位数（vs baseline `[1:]`）**：`{beta3_cmp['base_act_median_reply_est_tokens']}` → `{beta3_cmp['new_act_median_reply_est_tokens']}`（降幅 **{beta3_cmp['act_median_reply_drop_pct']:.1%}**，目标 ≥ 50%）",
            f"- **仅 `act` 口径（3.13）图片 token 合计（vs 本次同场景 `a` `[1:]`）**：`{beta3_cmp['same_run_a_act_image_tokens']}` → `{beta3_cmp['new_act_image_tokens']}`（降幅 **{beta3_cmp['act_image_tokens_drop_pct']:.1%}**，目标 ≥ 60%）",
            f"- 全回复口径（含 `launch`）文字中位数（vs baseline）：`{beta3_cmp['base_median_reply_est_tokens']}` → `{beta3_cmp['new_median_reply_est_tokens']}`（降幅 **{beta3_cmp['median_reply_drop_pct']:.1%}**）",
            f"- 全回复口径（含 `launch`）图片 token 合计（vs baseline）：`{beta3_cmp['base_image_tokens']}` → `{beta3_cmp['new_image_tokens']}`（降幅 **{beta3_cmp['image_tokens_drop_pct']:.1%}**）",
        ])
        sc = beta3_cmp["scene_checks"]
        lines.extend([
            f"- S02（弹窗 `BEFORE` + `弹出`）：`{'通过' if sc['S02_before_and_popup'] else '未命中'}`（loud=`{sc['S02_loud']}`, popup=`{sc['S02_has_popup']}`）",
            f"- S12（轮播不触发 `BEFORE`/`AFTER`）：T12a=`{'通过' if sc['S12a_no_before_after'] else '未命中'}` `{sc['S12a_loud']}`，T12b=`{'通过' if sc['S12b_no_before_after'] else '未命中'}` `{sc['S12b_loud']}`",
            f"- S07（toast 事件）：T07a=`{sc['S07a_has_toast']}`，T07b=`{sc['S07b_has_toast']}`",
        ])

    if repeat_rows is not None:
        matched_cnt = sum(1 for item in repeat_rows if item["matched"])
        lines.extend([
            "",
            f"## 重复一致性检查（`--repeat-check`：{matched_cnt}/{len(repeat_rows)} 一致）",
            "",
            "| 任务 | 场景 | seed | 第 1 次事件数 | 第 2 次事件数 | 序列一致 | 事件摘要 |",
            "| --- | --- | ---: | ---: | ---: | --- | --- |",
        ])
        for item in repeat_rows:
            summary = " -> ".join(
                f"{e['ev']}({e['data']['a']})" if e.get("ev") == "touch" and isinstance(e.get("data"), dict) and "a" in e["data"]
                else str(e.get("ev"))
                for e in item["seq1"]
            )
            if len(summary) > 96:
                summary = summary[:93] + "..."
            lines.append(
                f"| {item['id']} | {item['scene']} | {item['seed']} | {len(item['seq1'])} | "
                f"{len(item['seq2'])} | {'一致' if item['matched'] else '不一致'} | `{summary}` |"
            )

    lines.extend(["", "## 守门人统计（`status.host.gate`）", ""])
    if isinstance(gate, dict):
        lines.extend([
            "| port | stale_count | wake_ok | wake_fail | not_ready | last_wake_ms |",
            "| ---: | ---: | ---: | ---: | ---: | ---: |",
            f"| {gate.get('port', 0)} | {gate.get('stale_count', 0)} | {gate.get('wake_ok', 0)} | "
            f"{gate.get('wake_fail', 0)} | {gate.get('not_ready', 0)} | {gate.get('last_wake_ms', 0)} |",
        ])
    else:
        lines.append("`null`（未启用守门人或状态未取回）")
    return "\n".join(lines) + "\n"


def _extract_gesture(reply):
    for st in (reply.get("steps") or []):
        if isinstance(st, dict) and isinstance(st.get("gesture"), dict):
            return dict(st["gesture"])
    return {}


def _fmt_ms_stats(p50s, maxs):
    p50_txt = f"{round(statistics.median(p50s), 1)}" if p50s else "-"
    max_txt = f"{max(maxs)}" if maxs else "-"
    return p50_txt, max_txt


def _pick_s04_outside_target(card, slots):
    """Pick a release coordinate clearly outside every slot's 60dp (~158px) snap zone and >300px from start card."""
    import math
    candidates = [
        (200, 1080),
        (880, 1080),
        (200, 1140),
        (880, 1140),
        (540, 1220),
    ]
    best = candidates[0]
    best_score = -1.0
    for cx, cy in candidates:
        min_slot = min(math.hypot(cx - s[0], cy - s[1]) for s in slots) if slots else 999.0
        dist_card = math.hypot(cx - card[0], cy - card[1])
        score = min(min_slot, dist_card)
        if score > best_score:
            best_score = score
            best = (cx, cy)
    return best


def _start_scene_on_display(disp_id, scene, seed_val, extras=None):
    adb_shell("logcat", "-c")
    adb_shell(
        "am", "start", "-W", "--activity-clear-top",
        "--display", str(disp_id),
        "-n", f"{TORTURE_PKG}/.SceneActivity",
        "--es", "scene", scene,
        "--ei", "seed", str(seed_val),
        *_extras_argv(extras),
    )
    time.sleep(0.25)


def _wait_enter_event(scene, timeout_s=3.0):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        evs = read_avst_events(scene)
        for ev in evs:
            if ev.get("ev") == "enter":
                return ev
        time.sleep(0.15)
    return None


def _launch_scene_session(token, scene, seed_val, session, extras=None):
    adb_shell("am", "force-stop", TORTURE_PKG)
    adb_shell(
        "am", "broadcast", "-W", "--include-stopped-packages",
        "-n", f"{TORTURE_PKG}/.PresetReceiver",
        "--es", "scene", scene,
        "--ei", "seed", str(seed_val),
        *_extras_argv(extras),
    )
    adb_shell("logcat", "-c")
    call_api(token, ["launch", TORTURE_PKG], session=session, timeout=120)
    disp_id = find_scene_display_id()
    if not disp_id:
        raise RuntimeError(f"Could not find torture display id after launching {scene}")
    return disp_id


def run_gesture_stats(token, out_dir, app_version, s04_runs=40, s10_runs=20, s11_runs=20, s05_runs=20):
    """2.0.0-beta4 (4.6 & 4.7) & beta5 (5.7 #2): run 40x S04, 20x S10, 20x S11 (with img_age_ms), 20x S05."""
    from collections import Counter

    stats = {
        "app_version": app_version,
        "generated_at": int(time.time()),
        "s04": {"runs": [], "summary": {}},
        "s10": {"runs": [], "summary": {}},
        "s11": {"runs": [], "summary": {}},
        "s05": {"runs": [], "summary": {}},
    }

    # ── S04: 40 drags (even=in-slot via hold until color, odd=outside-slot via normal drag) ──
    if s04_runs > 0:
        session = "gstats_s04"
        try:
            disp_id = _launch_scene_session(token, "S04", 100, session)
            for i in range(s04_runs):
                seed_val = 100 + i
                want_in_slot = (i % 2 == 0)
                _start_scene_on_display(disp_id, "S04", seed_val)
                enter = _wait_enter_event("S04")
                data = (enter or {}).get("data") or {}
                cards = data.get("cards") or [[200, 1500]]
                slots = data.get("slots") or [[220, 600], [540, 600], [860, 600], [540, 960]]
                card = cards[0]
                slot = slots[1] if len(slots) > 1 else slots[0]
                if want_in_slot:
                    step = f"drag {card[0]} {card[1]} {slot[0]} {slot[1]} hold until color:{slot[0]},{slot[1]}=#4CAF50 2s"
                else:
                    tx, ty = _pick_s04_outside_target(card, slots)
                    step = f"drag {card[0]} {card[1]} {tx} {ty} 600ms"
                reply = call_api(token, ["act", step], session=session, timeout=90)
                time.sleep(0.15)
                evs = read_avst_events("S04")
                avst = "snap" if any(e.get("ev") == "snap" for e in evs) else (
                    "snap_back" if any(e.get("ev") == "snap_back" for e in evs) else "none"
                )
                g = _extract_gesture(reply)
                ar = g.get("after_release") or "missing"
                expected_ar = "stayed" if avst == "snap" else ("snapped_back" if avst == "snap_back" else "")
                matched = bool(expected_ar and ar == expected_ar)
                loud = list(reply.get("loud") or [])
                rec = {
                    "run": i + 1,
                    "seed": seed_val,
                    "mode": "in_slot" if want_in_slot else "out_slot",
                    "avst": avst,
                    "after_release": ar,
                    "score": g.get("score"),
                    "picked_up": g.get("picked_up"),
                    "followed": g.get("followed"),
                    "poll_ms_p50": g.get("poll_ms_p50"),
                    "poll_ms_max": g.get("poll_ms_max"),
                    "loud": loud,
                    "matched": matched,
                }
                stats["s04"]["runs"].append(rec)
                print(
                    f"[S04 {i + 1}/{s04_runs}] seed={seed_val} mode={rec['mode']} avst={avst} "
                    f"after_release={ar} score={rec['score']} poll={rec['poll_ms_p50']}/{rec['poll_ms_max']} "
                    f"loud={loud} match={matched}",
                    flush=True,
                )
        finally:
            try:
                call_api(token, ["release"], session=session, timeout=30)
            except Exception:
                pass
            adb_shell("am", "force-stop", TORTURE_PKG)

    # ── S10: 20 seek runs (`drag 100 800 980 800 seek until text:70 in 400,600,700,700`) ──
    if s10_runs > 0:
        session = "gstats_s10"
        try:
            disp_id = _launch_scene_session(token, "S10", 200, session)
            for i in range(s10_runs):
                seed_val = 200 + i
                _start_scene_on_display(disp_id, "S10", seed_val)
                reply = call_api(
                    token,
                    ["act", "drag 100 800 980 800 seek until text:70 in 400,600,700,700"],
                    session=session,
                    timeout=90,
                )
                time.sleep(0.15)
                evs = read_avst_events("S10")
                val_evs = [e for e in evs if e.get("ev") == "value"]
                val = (val_evs[-1].get("data") or {}).get("value") if val_evs else None
                ok = isinstance(val, int) and 68 <= val <= 72
                g = _extract_gesture(reply)
                reason = "ok" if ok else (
                    "no_value" if val is None else (f"low({val})" if val < 68 else f"high({val})")
                )
                rec = {
                    "run": i + 1,
                    "seed": seed_val,
                    "value": val,
                    "ok": ok,
                    "reason": reason,
                    "code": reply.get("code"),
                    "poll_ms_p50": g.get("poll_ms_p50"),
                    "poll_ms_max": g.get("poll_ms_max"),
                }
                stats["s10"]["runs"].append(rec)
                print(
                    f"[S10 {i + 1}/{s10_runs}] seed={seed_val} value={val} ok={ok} "
                    f"poll={rec['poll_ms_p50']}/{rec['poll_ms_max']} code={rec['code']}",
                    flush=True,
                )
        finally:
            try:
                call_api(token, ["release"], session=session, timeout=30)
            except Exception:
                pass
            adb_shell("am", "force-stop", TORTURE_PKG)

    # ── S11: 20 press-hold charge runs (4.8: color on inner left of charge bar turning #16A34A at >=90%) ──
    if s11_runs > 0:
        session = "gstats_s11"
        try:
            disp_id = _launch_scene_session(token, "S11", 5300, session)
            for i in range(s11_runs):
                seed_val = 5300 + i
                _start_scene_on_display(disp_id, "S11", seed_val)
                enter = _wait_enter_event("S11")
                data = (enter or {}).get("data") or {}
                probe = data.get("bar_probe")
                if isinstance(probe, list) and len(probe) >= 2:
                    bx, by = int(probe[0]), int(probe[1])
                else:
                    bar = data.get("bar")
                    if isinstance(bar, list) and len(bar) >= 4:
                        bl, bt, br, bb = int(bar[0]), int(bar[1]), int(bar[2]), int(bar[3])
                        bx = bl + max(bb - bt, int((br - bl) * 0.12))
                        by = (bt + bb) // 2
                    else:
                        bx, by = 220, 1340
                reply = call_api(
                    token,
                    ["act", f"press 540 1600 hold until color:{bx},{by}=#16A34A~40 8s"],
                    session=session,
                    timeout=90,
                )
                time.sleep(0.15)
                evs = read_avst_events("S11")
                goal = any(e.get("ev") == "goal" and e.get("ok") is True for e in evs)
                overloaded = any(e.get("ev") == "fail" for e in evs)
                down_evs = [
                    e for e in evs
                    if e.get("ev") == "touch" and isinstance(e.get("data"), dict) and e["data"].get("a") == "down"
                ]
                up_evs = [
                    e for e in evs
                    if e.get("ev") == "touch" and isinstance(e.get("data"), dict) and e["data"].get("a") == "up"
                ]
                end_evs = [e for e in evs if e.get("ev") in ("goal", "fail")]
                held_ms = None
                end_ms = None
                if down_evs:
                    dt0 = int(down_evs[0].get("t") or 0)
                    if up_evs:
                        held_ms = int(up_evs[-1].get("t") or 0) - dt0
                    if end_evs:
                        end_ms = int(end_evs[0].get("t") or 0) - dt0
                g = _extract_gesture(reply)
                reason = "ok" if (goal and not overloaded) else ("overload" if overloaded else "early_or_missed")
                rec = {
                    "run": i + 1,
                    "seed": seed_val,
                    "goal": goal,
                    "overload": overloaded,
                    "ok": goal and not overloaded,
                    "reason": reason,
                    "code": reply.get("code"),
                    "held_ms": held_ms,
                    "end_ms": end_ms,
                    "poll_ms_p50": g.get("poll_ms_p50"),
                    "poll_ms_max": g.get("poll_ms_max"),
                    "img_age_ms": g.get("img_age_ms"),
                }
                stats["s11"]["runs"].append(rec)
                print(
                    f"[S11 {i + 1}/{s11_runs}] seed={seed_val} goal={goal} overload={overloaded} "
                    f"held_ms={held_ms} end_ms={end_ms} poll={rec['poll_ms_p50']}/{rec['poll_ms_max']} "
                    f"img_age={rec['img_age_ms']} code={rec['code']}",
                    flush=True,
                )
        finally:
            try:
                call_api(token, ["release"], session=session, timeout=30)
            except Exception:
                pass
            adb_shell("am", "force-stop", TORTURE_PKG)

    # ── S05: 20 press-hold menu runs (`press 540 1200 hold until text:菜单已打开 in 280,760,800,1060 3s`) ──
    if s05_runs > 0:
        session = "gstats_s05"
        try:
            disp_id = _launch_scene_session(token, "S05", 300, session)
            for i in range(s05_runs):
                seed_val = 300 + i
                _start_scene_on_display(disp_id, "S05", seed_val)
                reply = call_api(
                    token,
                    ["act", "press 540 1200 hold until text:菜单已打开 in 280,760,800,1060 3s"],
                    session=session,
                    timeout=90,
                )
                time.sleep(0.15)
                evs = read_avst_events("S05")
                menu_evs = [e for e in evs if e.get("ev") == "menu_shown" and e.get("ok") is True]
                up_evs = [
                    e for e in evs
                    if e.get("ev") == "touch" and isinstance(e.get("data"), dict) and e["data"].get("a") == "up"
                ]
                menu_shown = bool(menu_evs)
                lag_ms = None
                if menu_shown and up_evs:
                    mt = int(menu_evs[0].get("t") or 0)
                    after_ups = [int(u.get("t") or 0) for u in up_evs if int(u.get("t") or 0) >= mt]
                    if after_ups:
                        lag_ms = after_ups[0] - mt
                ok = menu_shown and (lag_ms is not None) and lag_ms <= 300
                g = _extract_gesture(reply)
                reason = "ok" if ok else ("no_menu" if not menu_shown else (f"lag>{300}ms({lag_ms})" if lag_ms is not None else "no_up_after_menu"))
                rec = {
                    "run": i + 1,
                    "seed": seed_val,
                    "menu_shown": menu_shown,
                    "release_lag_ms": lag_ms,
                    "ok": ok,
                    "reason": reason,
                    "code": reply.get("code"),
                    "poll_ms_p50": g.get("poll_ms_p50"),
                    "poll_ms_max": g.get("poll_ms_max"),
                }
                stats["s05"]["runs"].append(rec)
                print(
                    f"[S05 {i + 1}/{s05_runs}] seed={seed_val} menu={menu_shown} lag_ms={lag_ms} ok={ok} "
                    f"poll={rec['poll_ms_p50']}/{rec['poll_ms_max']} code={rec['code']}",
                    flush=True,
                )
        finally:
            try:
                call_api(token, ["release"], session=session, timeout=30)
            except Exception:
                pass
            adb_shell("am", "force-stop", TORTURE_PKG)

    # ── Summaries & Markdown table ("手势统计") ──
    s04_list = stats["s04"]["runs"]
    s04_match = sum(1 for r in s04_list if r["matched"])
    s04_in = [r for r in s04_list if r["mode"] == "in_slot"]
    s04_out = [r for r in s04_list if r["mode"] == "out_slot"]
    s04_fail = Counter(f"{r['mode']}:{r['avst']}->{r['after_release']}" for r in s04_list if not r["matched"])
    s04_p50, s04_max = _fmt_ms_stats(
        [r["poll_ms_p50"] for r in s04_list if r["poll_ms_p50"] is not None],
        [r["poll_ms_max"] for r in s04_list if r["poll_ms_max"] is not None],
    )
    stats["s04"]["summary"] = {
        "total": len(s04_list),
        "matched": s04_match,
        "rate": round(s04_match / len(s04_list), 4) if s04_list else 0.0,
        "in_slot_matched": sum(1 for r in s04_in if r["matched"]),
        "in_slot_total": len(s04_in),
        "out_slot_matched": sum(1 for r in s04_out if r["matched"]),
        "out_slot_total": len(s04_out),
        "snap_back_loud": sum(1 for r in s04_out if "SNAP_BACK" in r["loud"]),
        "poll_ms_p50": s04_p50,
        "poll_ms_max": s04_max,
        "failures": dict(s04_fail),
    }

    s10_list = stats["s10"]["runs"]
    s10_ok = sum(1 for r in s10_list if r["ok"])
    s10_fail = Counter(
        "no_value" if r["value"] is None else ("low(<68)" if r["value"] < 68 else "high(>72)")
        for r in s10_list if not r["ok"]
    )
    s10_p50, s10_max = _fmt_ms_stats(
        [r["poll_ms_p50"] for r in s10_list if r["poll_ms_p50"] is not None],
        [r["poll_ms_max"] for r in s10_list if r["poll_ms_max"] is not None],
    )
    stats["s10"]["summary"] = {
        "total": len(s10_list),
        "passed": s10_ok,
        "rate": round(s10_ok / len(s10_list), 4) if s10_list else 0.0,
        "values": [r["value"] for r in s10_list],
        "poll_ms_p50": s10_p50,
        "poll_ms_max": s10_max,
        "failures": dict(s10_fail),
    }

    s11_list = stats["s11"]["runs"]
    s11_ok = sum(1 for r in s11_list if r["ok"])
    s11_over = sum(1 for r in s11_list if r["overload"])
    s11_fail = Counter(r["reason"] for r in s11_list if not r["ok"])
    s11_p50, s11_max = _fmt_ms_stats(
        [r["poll_ms_p50"] for r in s11_list if r["poll_ms_p50"] is not None],
        [r["poll_ms_max"] for r in s11_list if r["poll_ms_max"] is not None],
    )
    s11_ages = [r["img_age_ms"] for r in s11_list if r.get("img_age_ms") is not None]
    s11_age_p50 = round(statistics.median(s11_ages), 1) if s11_ages else None
    s11_age_max = max(s11_ages) if s11_ages else None
    stats["s11"]["summary"] = {
        "total": len(s11_list),
        "passed": s11_ok,
        "rate": round(s11_ok / len(s11_list), 4) if s11_list else 0.0,
        "overload": s11_over,
        "overload_rate": round(s11_over / len(s11_list), 4) if s11_list else 0.0,
        "poll_ms_p50": s11_p50,
        "poll_ms_max": s11_max,
        "img_age_ms_p50": s11_age_p50,
        "img_age_ms_max": s11_age_max,
        "failures": dict(s11_fail),
    }

    s05_list = stats["s05"]["runs"]
    s05_menu = sum(1 for r in s05_list if r["menu_shown"])
    s05_ok = sum(1 for r in s05_list if r["ok"])
    s05_lags = [r["release_lag_ms"] for r in s05_list if r["release_lag_ms"] is not None]
    s05_fail = Counter(
        "no_menu" if not r["menu_shown"] else ("no_up_after_menu" if r["release_lag_ms"] is None else "lag>300ms")
        for r in s05_list if not r["ok"]
    )
    s05_p50, s05_max = _fmt_ms_stats(
        [r["poll_ms_p50"] for r in s05_list if r["poll_ms_p50"] is not None],
        [r["poll_ms_max"] for r in s05_list if r["poll_ms_max"] is not None],
    )
    stats["s05"]["summary"] = {
        "total": len(s05_list),
        "menu_shown": s05_menu,
        "within_300ms": s05_ok,
        "rate": round(s05_ok / len(s05_list), 4) if s05_list else 0.0,
        "lag_ms_p50": round(statistics.median(s05_lags), 1) if s05_lags else None,
        "lag_ms_max": max(s05_lags) if s05_lags else None,
        "poll_ms_p50": s05_p50,
        "poll_ms_max": s05_max,
        "failures": dict(s05_fail),
    }

    def fmt_fail(d):
        return ", ".join(f"{k}:{v}" for k, v in sorted(d.items())) if d else "无"

    md_lines = [
        f"# 手势统计（{app_version}）",
        "",
        "| 场景 | 次数 | 达标 / 总数 | 指标详情 | 验收标准 | poll_ms p50 / max | 失败分布 |",
        "| --- | ---: | ---: | --- | --- | ---: | --- |",
    ]
    if s04_list:
        s4 = stats["s04"]["summary"]
        md_lines.append(
            f"| S04 | {s4['total']} | {s4['matched']}/{s4['total']} ({s4['rate']:.1%}) | "
            f"进槽 {s4['in_slot_matched']}/{s4['in_slot_total']}，槽外 {s4['out_slot_matched']}/{s4['out_slot_total']}（SNAP_BACK {s4['snap_back_loud']}/{s4['out_slot_total']}） | "
            f"一致率 ≥ 95% | {s4['poll_ms_p50']} / {s4['poll_ms_max']} | {fmt_fail(s4['failures'])} |"
        )
    if s10_list:
        s10 = stats["s10"]["summary"]
        md_lines.append(
            f"| S10 | {s10['total']} | {s10['passed']}/{s10['total']} ({s10['rate']:.1%}) | "
            f"68–72 命中 {s10['passed']} 次 | ≥ 90% | {s10['poll_ms_p50']} / {s10['poll_ms_max']} | {fmt_fail(s10['failures'])} |"
        )
    if s11_list:
        s11 = stats["s11"]["summary"]
        age_txt = (
            f"帧龄 p50={s11['img_age_ms_p50']}ms / max={s11['img_age_ms_max']}ms"
            if s11["img_age_ms_p50"] is not None
            else "帧龄 - / -"
        )
        md_lines.append(
            f"| S11 | {s11['total']} | {s11['passed']}/{s11['total']} ({s11['rate']:.1%}) | "
            f"goal {s11['passed']}/{s11['total']}，过载 {s11['overload']}/{s11['total']} ({s11['overload_rate']:.1%})，{age_txt} | "
            f"goal ≥ 90%，过载 ≤ 5% | {s11['poll_ms_p50']} / {s11['poll_ms_max']} | {fmt_fail(s11['failures'])} |"
        )
    if s05_list:
        s5 = stats["s05"]["summary"]
        md_lines.append(
            f"| S05 | {s5['total']} | {s5['within_300ms']}/{s5['total']} ({s5['rate']:.1%}) | "
            f"菜单出现 {s5['menu_shown']}/{s5['total']}，松手延迟 p50={s5['lag_ms_p50']}ms / max={s5['lag_ms_max']}ms | "
            f"全部出现且 ≤ 300ms | {s5['poll_ms_p50']} / {s5['poll_ms_max']} | {fmt_fail(s5['failures'])} |"
        )
    md = "\n".join(md_lines) + "\n"

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "gesture-stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out_dir / "gesture-stats.md").write_text(md, encoding="utf-8")
    print("\n" + md, flush=True)
    return stats


def _wchan_of(pkg):
    try:
        out = adb_shell("ps", "-A", "-o", "NAME,WCHAN")
    except subprocess.CalledProcessError:
        return ""
    for line in out.splitlines():
        parts = line.strip().split()
        if len(parts) >= 2 and parts[0] == pkg:
            return parts[1]
    return ""


def _pid_of(pkg):
    try:
        return adb_shell("pidof", pkg).strip()
    except subprocess.CalledProcessError:
        return ""


def run_s5_stats(token, out_dir, app_version, s02_runs=20, s09_runs=20, hold_rounds=2, hold_wait_s=10.0):
    """2.0.0-beta5 (5.5 & 5.8 #1/#4): Stage 5 acceptance stats:
      - S02 x20: `--on "text:限时优惠 => tap 980,420" "wait 3.2s; tap 确定 until text:已确定"`
        (goal >= 19/20, AVST touch_down == 1 + len(rules_fired), quiet short replies counted).
      - S09 (keep_cycling=true) x20: `--on "text:点我 => tap 点我 x3" --watch 10s`
        (>= 18/20 with target_hit >= 2 and AVST touch_down == len(rules_fired)).
      - S09 + --hold (API 34/35): 2 consecutive HOLD/UNHOLD rounds (10s each, 0 target_shown while held,
        resumes after next command), then send app to background (Settings + HOME) and reopen same process
        confirming target_shown resumes with no lingering sticky freeze.
    """
    from collections import Counter

    sdk = int(adb_shell("getprop", "ro.build.version.sdk") or "0")
    stats = {
        "app_version": app_version,
        "sdk": sdk,
        "generated_at": int(time.time()),
        "s02": {"runs": [], "summary": {}},
        "s09": {"runs": [], "summary": {}},
        "hold": {"rounds": [], "reopen": {}, "summary": {}},
    }

    # ── S02 x20 ──
    if s02_runs > 0:
        session = "s5_s02"
        try:
            disp_id = _launch_scene_session(token, "S02", 100, session)
            for i in range(s02_runs):
                seed_val = 100 + i
                _start_scene_on_display(disp_id, "S02", seed_val)
                reply = call_api(
                    token,
                    ["act", "--on", "text:限时优惠 => tap 980,420", "wait 3.2s; tap 确定 until text:已确定"],
                    session=session,
                    timeout=90,
                )
                time.sleep(0.15)
                evs = read_avst_events("S02")
                goal = any(e.get("ev") == "goal" and e.get("ok") is True for e in evs)
                popup_closed = any(e.get("ev") == "popup_closed" and e.get("ok") is True for e in evs)
                touch_downs = sum(
                    1 for e in evs
                    if e.get("ev") == "touch" and isinstance(e.get("data"), dict) and e["data"].get("a") == "down"
                )
                rf = list(reply.get("rules_fired") or []) if isinstance(reply.get("rules_fired"), list) else []
                expected_touches = 1 + len(rf)
                touch_exact = (touch_downs == expected_touches)
                loud = list(reply.get("loud") or []) if isinstance(reply.get("loud"), list) else ["missing"]
                quiet = (len(loud) == 0)
                ok = goal and popup_closed and touch_exact
                reason = "ok" if ok else (
                    "no_goal" if not goal else ("no_popup_closed" if not popup_closed else f"touches({touch_downs}!={expected_touches})")
                )
                rec = {
                    "run": i + 1,
                    "seed": seed_val,
                    "goal": goal,
                    "popup_closed": popup_closed,
                    "rules_fired": len(rf),
                    "touch_downs": touch_downs,
                    "expected_touches": expected_touches,
                    "touch_exact": touch_exact,
                    "quiet": quiet,
                    "loud": loud,
                    "ok": ok,
                    "reason": reason,
                    "code": reply.get("code"),
                }
                stats["s02"]["runs"].append(rec)
                print(
                    f"[S5-S02 {i + 1}/{s02_runs}] seed={seed_val} goal={goal} closed={popup_closed} "
                    f"rf={len(rf)} touches={touch_downs}/{expected_touches} quiet={quiet} loud={loud} ok={ok}",
                    flush=True,
                )
        finally:
            try:
                call_api(token, ["release"], session=session, timeout=30)
            except Exception:
                pass
            adb_shell("am", "force-stop", TORTURE_PKG)

    # ── S09 keep_cycling=true x20 ──
    if s09_runs > 0:
        session = "s5_s09"
        try:
            disp_id = _launch_scene_session(token, "S09", 200, session, extras={"keep_cycling": True})
            for i in range(s09_runs):
                seed_val = 200 + i
                _start_scene_on_display(disp_id, "S09", seed_val, extras={"keep_cycling": True})
                reply = call_api(
                    token,
                    ["act", "--on", "text:点我 => tap 点我 x3", "--watch", "10s"],
                    session=session,
                    timeout=90,
                )
                time.sleep(0.15)
                evs = read_avst_events("S09")
                hits = sum(1 for e in evs if e.get("ev") == "target_hit" and e.get("ok") is True)
                goals = sum(1 for e in evs if e.get("ev") == "goal" and e.get("ok") is True)
                shown = sum(1 for e in evs if e.get("ev") == "target_shown")
                touch_downs = sum(
                    1 for e in evs
                    if e.get("ev") == "touch" and isinstance(e.get("data"), dict) and e["data"].get("a") == "down"
                )
                rf = list(reply.get("rules_fired") or []) if isinstance(reply.get("rules_fired"), list) else []
                touch_exact = (touch_downs == len(rf))
                ok = (hits >= 2) and (goals == 1) and touch_exact
                reason = "ok" if ok else (
                    f"hits({hits}<2)" if hits < 2 else (f"goals({goals}!=1)" if goals != 1 else f"touches({touch_downs}!={len(rf)})")
                )
                rec = {
                    "run": i + 1,
                    "seed": seed_val,
                    "target_shown": shown,
                    "target_hit": hits,
                    "goal_count": goals,
                    "rules_fired": len(rf),
                    "touch_downs": touch_downs,
                    "touch_exact": touch_exact,
                    "ok": ok,
                    "reason": reason,
                    "code": reply.get("code"),
                }
                stats["s09"]["runs"].append(rec)
                print(
                    f"[S5-S09 {i + 1}/{s09_runs}] seed={seed_val} shown={shown} hits={hits} goals={goals} "
                    f"rf={len(rf)} touches={touch_downs} ok={ok}",
                    flush=True,
                )
        finally:
            try:
                call_api(token, ["release"], session=session, timeout=30)
            except Exception:
                pass
            adb_shell("am", "force-stop", TORTURE_PKG)

    # ── S09 + --hold (2 consecutive HOLD/UNHOLD rounds + background/reopen check per 5.5 & 5.8 #1) ──
    if hold_rounds > 0 and sdk >= 34:
        session = "s5_hold"
        try:
            disp_id = _launch_scene_session(token, "S09", 900, session, extras={"keep_cycling": True})
            _start_scene_on_display(disp_id, "S09", 900, extras={"keep_cycling": True})
            # Wait for initial target_shown so we know S09 is actively cycling before round 1.
            t_init = time.monotonic()
            while time.monotonic() - t_init < 5.0:
                if any(e.get("ev") == "target_shown" for e in read_avst_events("S09")):
                    break
                time.sleep(0.2)

            def _hold_file_text():
                return adb_shell("cat", "/data/local/tmp/avs-holds.txt", "2>/dev/null", "||", "true")

            def _wchan_readable(w):
                return w not in ("", "0", "-")

            for r in range(1, hold_rounds + 1):
                h_rep = call_api(token, ["act", "--hold"], session=session, timeout=60)
                # Wait briefly for post-send freeze to settle (check avs-holds.txt; WCHAN may be '0' as uid 2000).
                wchan_held = ""
                hold_file_held = ""
                held_confirmed = False
                for _ in range(25):
                    hold_file_held = _hold_file_text()
                    wchan_held = _wchan_of(TORTURE_PKG)
                    if TORTURE_PKG in hold_file_held and (not _wchan_readable(wchan_held) or wchan_held == "do_freezer_trap"):
                        held_confirmed = True
                        break
                    time.sleep(0.2)
                adb_shell("logcat", "-c")
                time.sleep(hold_wait_s)
                evs_held = read_avst_events("S09")
                shown_during_hold = sum(1 for e in evs_held if e.get("ev") == "target_shown")

                # Next command must unhold before doing anything, and report "已恢复".
                adb_shell("logcat", "-c")
                u_rep = call_api(token, ["look"], session=session, timeout=60)
                hold_file_after = _hold_file_text()
                wchan_after = _wchan_of(TORTURE_PKG)
                resumed_ms = u_rep.get("resumed_after_ms")
                has_resumed_text = ("已恢复" in str(u_rep.get("text") or "")) or ("Resumed" in str(u_rep.get("text") or ""))
                # Wait up to 5s for target_shown to resume (period 3s).
                shown_after = 0
                t_res = time.monotonic()
                while time.monotonic() - t_res < 5.0:
                    shown_after = sum(1 for e in read_avst_events("S09") if e.get("ev") == "target_shown")
                    if shown_after >= 1:
                        break
                    time.sleep(0.25)

                round_ok = (
                    h_rep.get("ok") is True
                    and held_confirmed
                    and shown_during_hold == 0
                    and u_rep.get("ok") is True
                    and TORTURE_PKG not in hold_file_after
                    and wchan_after != "do_freezer_trap"
                    and shown_after >= 1
                    and has_resumed_text
                    and isinstance(resumed_ms, int) and resumed_ms > 0
                )
                rrec = {
                    "round": r,
                    "held_confirmed": held_confirmed,
                    "hold_file_held": hold_file_held,
                    "wchan_held": wchan_held,
                    "shown_during_hold": shown_during_hold,
                    "hold_file_after": hold_file_after,
                    "wchan_after": wchan_after,
                    "shown_after": shown_after,
                    "resumed_after_ms": resumed_ms,
                    "has_resumed_text": has_resumed_text,
                    "ok": round_ok,
                }
                stats["hold"]["rounds"].append(rrec)
                print(
                    f"[S5-HOLD R{r}/{hold_rounds} API {sdk}] held={held_confirmed} wchan={wchan_held!r} "
                    f"shown_during={shown_during_hold} after_wchan={wchan_after!r} "
                    f"shown_after={shown_after} resumed_ms={resumed_ms} ok={round_ok}",
                    flush=True,
                )

            # 5.8 #1: after round 2, return to home/background, wait 3s, reopen app without force-stop -> target_shown still fires!
            pid_before = _pid_of(TORTURE_PKG)
            adb_shell("input", "keyevent", "KEYCODE_HOME")
            adb_shell("am", "start", "-W", "--display", str(disp_id), "-n", "com.android.settings/.Settings")
            time.sleep(3.0)
            wchan_bg = _wchan_of(TORTURE_PKG)
            adb_shell("logcat", "-c")
            adb_shell(
                "am", "start", "-W", "--activity-clear-top",
                "--display", str(disp_id),
                "-n", f"{TORTURE_PKG}/.SceneActivity",
                "--es", "scene", "S09",
                "--ei", "seed", "909",
                "--ez", "keep_cycling", "true",
            )
            pid_after = _pid_of(TORTURE_PKG)
            wchan_reopen = _wchan_of(TORTURE_PKG)
            shown_reopen = 0
            t_ro = time.monotonic()
            while time.monotonic() - t_ro < 6.0:
                shown_reopen = sum(1 for e in read_avst_events("S09") if e.get("ev") == "target_shown")
                if shown_reopen >= 1:
                    break
                time.sleep(0.25)
            reopen_ok = (wchan_reopen != "do_freezer_trap") and (shown_reopen >= 1)
            stats["hold"]["reopen"] = {
                "pid_before": pid_before,
                "pid_after": pid_after,
                "wchan_bg": wchan_bg,
                "wchan_reopen": wchan_reopen,
                "shown_reopen": shown_reopen,
                "ok": reopen_ok,
            }
            print(
                f"[S5-HOLD REOPEN API {sdk}] pid={pid_before}->{pid_after} wchan_bg={wchan_bg!r} "
                f"wchan_reopen={wchan_reopen!r} shown_reopen={shown_reopen} ok={reopen_ok}",
                flush=True,
            )
        finally:
            try:
                call_api(token, ["release"], session=session, timeout=30)
            except Exception:
                pass
            try:
                adb_shell("am", "unfreeze", "--sticky", TORTURE_PKG)
                adb_shell("am", "unfreeze", TORTURE_PKG)
            except Exception:
                pass
            adb_shell("am", "force-stop", TORTURE_PKG)

    # ── Summaries & Markdown ──
    s02_list = stats["s02"]["runs"]
    s02_ok = sum(1 for r in s02_list if r["ok"])
    s02_goal = sum(1 for r in s02_list if r["goal"])
    s02_touch_ok = sum(1 for r in s02_list if r["touch_exact"])
    s02_quiet = sum(1 for r in s02_list if r["quiet"])
    s02_fail = Counter(r["reason"] for r in s02_list if not r["ok"])
    stats["s02"]["summary"] = {
        "total": len(s02_list),
        "passed": s02_ok,
        "goal": s02_goal,
        "touch_exact": s02_touch_ok,
        "quiet": s02_quiet,
        "rate": round(s02_ok / len(s02_list), 4) if s02_list else 0.0,
        "failures": dict(s02_fail),
    }

    s09_list = stats["s09"]["runs"]
    s09_ok = sum(1 for r in s09_list if r["ok"])
    s09_hits2 = sum(1 for r in s09_list if r["target_hit"] >= 2)
    s09_touch_ok = sum(1 for r in s09_list if r["touch_exact"])
    s09_fail = Counter(r["reason"] for r in s09_list if not r["ok"])
    stats["s09"]["summary"] = {
        "total": len(s09_list),
        "passed": s09_ok,
        "hits_ge_2": s09_hits2,
        "touch_exact": s09_touch_ok,
        "rate": round(s09_ok / len(s09_list), 4) if s09_list else 0.0,
        "failures": dict(s09_fail),
    }

    h_rounds = stats["hold"]["rounds"]
    h_reopen = stats["hold"]["reopen"]
    h_ok = bool(h_rounds) and all(r["ok"] for r in h_rounds) and bool(h_reopen.get("ok"))
    stats["hold"]["summary"] = {
        "rounds_passed": sum(1 for r in h_rounds if r["ok"]),
        "rounds_total": len(h_rounds),
        "reopen_ok": bool(h_reopen.get("ok")),
        "ok": h_ok,
    }

    def fmt_fail(d):
        return ", ".join(f"{k}:{v}" for k, v in sorted(d.items())) if d else "无"

    md_lines = [
        f"# 阶段 5 专项验收统计（{app_version}，API {sdk}）",
        "",
        "| 场景 | 次数 | 达标 / 总数 | 指标详情 | 验收标准 | 失败分布 |",
        "| --- | ---: | ---: | --- | --- | --- |",
    ]
    if s02_list:
        s2 = stats["s02"]["summary"]
        md_lines.append(
            f"| S02 (`--on`) | {s2['total']} | {s2['passed']}/{s2['total']} ({s2['rate']:.1%}) | "
            f"goal {s2['goal']}/{s2['total']}，touch 精确 {s2['touch_exact']}/{s2['total']}，短回复 {s2['quiet']}/{s2['total']} | "
            f"≥ 19/20 goal 且无多余点击 | {fmt_fail(s2['failures'])} |"
        )
    if s09_list:
        s9 = stats["s09"]["summary"]
        md_lines.append(
            f"| S09 (`--on`, `keep_cycling`) | {s9['total']} | {s9['passed']}/{s9['total']} ({s9['rate']:.1%}) | "
            f"target_hit ≥ 2: {s9['hits_ge_2']}/{s9['total']}，touch==触发数 {s9['touch_exact']}/{s9['total']} | "
            f"≥ 18/20 target_hit ≥ 2 且 touch==触发数 | {fmt_fail(s9['failures'])} |"
        )
    if h_rounds:
        hs = stats["hold"]["summary"]
        md_lines.append(
            f"| S09 (`--hold` API {sdk}) | {hs['rounds_total']} 轮 + 重开 | {'通过' if hs['ok'] else '失败'} | "
            f"冻结轮次 {hs['rounds_passed']}/{hs['rounds_total']}，后台重开恢复={'通过' if hs['reopen_ok'] else '失败'} | "
            f"冻结期 0 target_shown，解冻及重开后恢复 | {'无' if hs['ok'] else '见 JSON'} |"
        )
    md = "\n".join(md_lines) + "\n"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "s5-stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out_dir / "s5-stats.md").write_text(md, encoding="utf-8")
    print("\n" + md, flush=True)
    return stats


def render_times_markdown(app_version, grouped_rows):
    lines = [
        f"# 手势统计（{app_version}，`--times` 多 seed 运行）",
        "",
        "| 任务 | 场景 | 次数 | 成功 / 总数 | 成功率 | poll_ms p50 / max | 备注 |",
        "| --- | --- | ---: | ---: | ---: | ---: | --- |",
    ]
    for tid, runs in grouped_rows.items():
        scene = runs[0]["scene"]
        passed = sum(1 for r in runs if r["ok"])
        p50s = [g["poll_ms_p50"] for r in runs for g in (r.get("gestures") or []) if g.get("poll_ms_p50") is not None]
        maxs = [g["poll_ms_max"] for r in runs for g in (r.get("gestures") or []) if g.get("poll_ms_max") is not None]
        p50_txt, max_txt = _fmt_ms_stats(p50s, maxs)
        fails = [f"seed={r['seed']}({r['error'] or 'miss'})" for r in runs if not r["ok"]]
        note = ", ".join(fails[:5]) + (f" ...+{len(fails) - 5}" if len(fails) > 5 else "") if fails else "全部通过"
        lines.append(
            f"| {tid} | {scene} | {len(runs)} | {passed}/{len(runs)} | {passed / len(runs):.1%} | {p50_txt} / {max_txt} | {note} |"
        )
    return "\n".join(lines) + "\n"


def main():
    repo_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description="Run Agent-VScreen evaluation suite.")
    parser.add_argument("--apk", required=True, help="Path to scene debug APK")
    parser.add_argument("--out", required=True, help="Output directory for result.json and result.md")
    parser.add_argument("--app-apk", default=None, help="Optional path to Agent-VScreen debug APK")
    parser.add_argument("--shizuku-apk", default=None, help="Optional path to Shizuku APK")
    parser.add_argument("--tasks-dir", default=str(repo_root / "tools" / "eval" / "tasks"))
    parser.add_argument("--baseline", default=str(repo_root / "tools" / "eval" / "baseline.json"))
    parser.add_argument("--write-baseline", action="store_true", help="Copy result.json to baseline path")
    parser.add_argument("--tasks", default=None, help="Comma-separated task IDs to run (e.g. T04b,T10b,T11b,T05b)")
    parser.add_argument("--times", type=int, default=1, help="Repeat each selected task N times with seed +1 per run")
    parser.add_argument("--gesture-stats", action="store_true", help="Run Stage 4 gesture acceptance suite (40x S04, 20x S10, 20x S11, 20x S05)")
    parser.add_argument("--s04-runs", type=int, default=40, help="S04 drag runs for --gesture-stats")
    parser.add_argument("--s10-runs", type=int, default=20, help="S10 seek runs for --gesture-stats")
    parser.add_argument("--s11-runs", type=int, default=20, help="S11 press-hold charge runs for --gesture-stats")
    parser.add_argument("--s05-runs", type=int, default=20, help="S05 press-hold menu runs for --gesture-stats")
    parser.add_argument("--s5-stats", action="store_true", help="Run Stage 5 acceptance suite (20x S02, 20x S09 keep_cycling, S09 --hold 2 rounds + reopen)")
    parser.add_argument("--s02-runs", type=int, default=20, help="S02 rule runs for --s5-stats")
    parser.add_argument("--s09-runs", type=int, default=20, help="S09 keep_cycling rule runs for --s5-stats")
    parser.add_argument("--hold-rounds", type=int, default=2, help="S09 --hold consecutive rounds for --s5-stats")
    parser.add_argument(
        "--repeat-check",
        action="store_true",
        help="Run every base (a) task twice with the same seed and verify normalized AVST event sequences match",
    )
    args = parser.parse_args()

    scene_apk = Path(args.apk)
    if not scene_apk.is_file():
        raise SystemExit(f"Missing APK: {scene_apk}")

    app_apk = args.app_apk or str(scene_apk.parent / "app-debug.apk")
    shizuku_apk = args.shizuku_apk or str(scene_apk.parent / "shizuku.apk")

    ensure_host_setup(app_apk, shizuku_apk)
    run_cmd("adb", "install", "-r", "-g", str(scene_apk), timeout=240)
    token = seed_token()
    info = ping()
    app_version = info.get("version", "2.0.0-beta1")
    # 3.13: acknowledge current version once before eval so AgentNews banner (~2000 tokens) does not skew tasks.
    try:
        call_api(token, ["remembered", "eval"], session="eval-setup", timeout=30)
    except Exception as e:
        print(f"WARN: remembered eval failed: {e}", flush=True)

    out_dir = Path(args.out)
    if args.gesture_stats:
        run_gesture_stats(
            token,
            out_dir,
            app_version,
            s04_runs=args.s04_runs,
            s10_runs=args.s10_runs,
            s11_runs=args.s11_runs,
            s05_runs=args.s05_runs,
        )
        return
    if args.s5_stats:
        s5 = run_s5_stats(
            token,
            out_dir,
            app_version,
            s02_runs=args.s02_runs,
            s09_runs=args.s09_runs,
            hold_rounds=args.hold_rounds,
        )
        failed = False
        if args.s02_runs > 0 and s5["s02"]["summary"]["passed"] < max(1, int(args.s02_runs * 0.95)):
            print(f"FAIL: S02 --on passed {s5['s02']['summary']['passed']}/{args.s02_runs}", file=sys.stderr)
            failed = True
        if args.s09_runs > 0 and s5["s09"]["summary"]["passed"] < max(1, int(args.s09_runs * 0.90)):
            print(f"FAIL: S09 --on passed {s5['s09']['summary']['passed']}/{args.s09_runs}", file=sys.stderr)
            failed = True
        if args.hold_rounds > 0 and s5["sdk"] >= 34 and not s5["hold"]["summary"]["ok"]:
            print(f"FAIL: S09 --hold failed: {s5['hold']['summary']}", file=sys.stderr)
            failed = True
        if failed:
            sys.exit(1)
        return

    task_files = sorted(Path(args.tasks_dir).glob("*.json"))
    if not task_files:
        raise SystemExit(f"No task files in {args.tasks_dir}")
    only_ids = {x.strip() for x in args.tasks.split(",") if x.strip()} if args.tasks else None

    if args.times > 1:
        grouped = {}
        for tf in task_files:
            task = json.loads(tf.read_text(encoding="utf-8"))
            if only_ids is not None and task["id"] not in only_ids:
                continue
            if not version_allows(app_version, task.get("since")):
                continue
            base_seed = int(task.get("seed", 7))
            runs = []
            for k in range(args.times):
                t_copy = dict(task)
                t_copy["seed"] = base_seed + k
                res = run_task(token, t_copy)
                runs.append(res)
                print(
                    f"[{'PASS' if res['ok'] else 'FAIL'}] {res['id']} ({res['scene']}) "
                    f"run={k + 1}/{args.times} seed={t_copy['seed']} gestures={res['gestures']} "
                    f"phone_ms={res['phone_ms']}" + (f" err={res['error']}" if res["error"] else ""),
                    flush=True,
                )
            grouped[task["id"]] = runs
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "result.json").write_text(
            json.dumps({"app_version": app_version, "times": args.times, "tasks": grouped}, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        md = render_times_markdown(app_version, grouped)
        (out_dir / "result.md").write_text(md, encoding="utf-8")
        print("\n" + md, flush=True)
        return

    rows = []
    skipped = []
    repeat_rows = [] if args.repeat_check else None

    for tf in task_files:
        task = json.loads(tf.read_text(encoding="utf-8"))
        if only_ids is not None and task["id"] not in only_ids:
            continue
        since = task.get("since")
        if not version_allows(app_version, since):
            skipped.append({"id": task["id"], "scene": task["scene"], "since": since})
            continue
        res, evs1 = run_task(token, task, return_events=True)
        print(
            f"[{'PASS' if res['ok'] else 'FAIL'}] {res['id']} ({res['scene']}) "
            f"turns={res['turns']} est_tokens={res['est_tokens']} img_tokens={res['image_tokens']} "
            f"quiet={res['quiet_replies']} false_quiet={res['false_quiet']} loud={res['loud_reasons']} "
            f"phone_ms={res['phone_ms']}"
            + (f" err={res['error']}" if res["error"] else ""),
            flush=True,
        )
        if res["false_quiet_details"]:
            print(f"  FALSE_QUIET details: {json.dumps(res['false_quiet_details'], ensure_ascii=False)}", flush=True)
        rows.append(res)

        if args.repeat_check and not since:
            _, evs2 = run_task(token, task, return_events=True)
            seq1 = normalize_avst_sequence(evs1)
            seq2 = normalize_avst_sequence(evs2)
            matched = len(seq1) > 0 and seq1 == seq2
            print(
                f"[REPEAT {'PASS' if matched else 'FAIL'}] {task['id']} ({task['scene']}) "
                f"seed={task.get('seed', 7)} len1={len(seq1)} len2={len(seq2)}",
                flush=True,
            )
            if not matched:
                print(f"  seq1={json.dumps(seq1, ensure_ascii=False)}", flush=True)
                print(f"  seq2={json.dumps(seq2, ensure_ascii=False)}", flush=True)
            repeat_rows.append({
                "id": task["id"],
                "scene": task["scene"],
                "seed": int(task.get("seed", 7)),
                "matched": matched,
                "seq1": seq1,
                "seq2": seq2,
            })

    gate_status = None
    try:
        st_resp = call_api(token, ["status"], "eval-gate", timeout=30)
        res_obj = st_resp.get("result") if isinstance(st_resp, dict) and isinstance(st_resp.get("result"), dict) else st_resp
        host_obj = res_obj.get("host") if isinstance(res_obj, dict) and isinstance(res_obj.get("host"), dict) else None
        if isinstance(host_obj, dict) and isinstance(host_obj.get("gate"), dict):
            gate_status = host_obj["gate"]
    except Exception as e:
        print(f"WARN: failed to read status.host.gate at end of eval: {e}", flush=True)

    totals = compute_totals(rows)
    baseline_path = Path(args.baseline)
    baseline = json.loads(baseline_path.read_text(encoding="utf-8")) if baseline_path.is_file() else None
    regressed = check_baseline_regressions(rows, baseline)
    diff = None
    if baseline and "totals" in baseline:
        bt = baseline["totals"]
        diff = {
            "pass_rate": round(totals["pass_rate"] - float(bt.get("pass_rate", 0.0)), 4),
            "turns": totals["turns"] - int(bt.get("turns", 0)),
            "chars": totals["chars"] - int(bt.get("chars", 0)),
            "est_tokens": totals["est_tokens"] - int(bt.get("est_tokens", 0)),
            "image_tokens": totals["image_tokens"] - int(bt.get("image_tokens", 0)),
            "phone_ms": totals["phone_ms"] - int(bt.get("phone_ms", 0)),
        }
    beta3_cmp = compute_beta3_comparison(rows, baseline)

    result_obj = {
        "app_version": app_version,
        "generated_at": int(time.time()),
        "tasks": rows,
        "skipped_tasks": skipped,
        "totals": totals,
        "diff_vs_baseline": diff,
        "regressed_tasks": regressed,
        "beta3_comparison": beta3_cmp,
        "gate": gate_status,
    }
    if repeat_rows is not None:
        result_obj["repeat_check"] = {
            "total": len(repeat_rows),
            "matched": sum(1 for item in repeat_rows if item["matched"]),
            "tasks": repeat_rows,
        }

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "result.json").write_text(json.dumps(result_obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    md = render_markdown(
        app_version, rows, skipped, totals, diff, regressed,
        repeat_rows=repeat_rows, gate=gate_status, beta3_cmp=beta3_cmp,
    )
    (out_dir / "result.md").write_text(md, encoding="utf-8")
    print("\n" + md, flush=True)

    if args.write_baseline:
        base_copy = dict(result_obj)
        base_copy["diff_vs_baseline"] = None
        base_copy["regressed_tasks"] = []
        base_copy.pop("repeat_check", None)
        base_copy.pop("gate", None)
        base_copy.pop("beta3_comparison", None)
        baseline_path.write_text(json.dumps(base_copy, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    failed_gate = False
    if totals["false_quiet"] > 0:
        print(f"FAIL: false_quiet (误静默) = {totals['false_quiet']} (must be 0)", file=sys.stderr)
        failed_gate = True
    if regressed and only_ids is None:
        print(f"FAIL: baseline-passing task(s) failed or missing: {', '.join(regressed)}", file=sys.stderr)
        failed_gate = True
    if repeat_rows is not None:
        mismatched = [item["id"] for item in repeat_rows if not item["matched"]]
        if mismatched:
            print(f"FAIL: repeat-check AVST sequence mismatch in: {', '.join(mismatched)}", file=sys.stderr)
            failed_gate = True
    if failed_gate:
        sys.exit(1)


if __name__ == "__main__":
    main()
