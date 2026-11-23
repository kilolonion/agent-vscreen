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
    "--stop", "--watch", "--timeout", "--wait", "--after", "--detail",
    "--every", "--for", "--for-ms", "--session", "--id", "--retry",
})
ACT_FLAG_OPTS = frozenset({
    "--full", "--fast", "--bare", "--human", "--async", "--keep", "--no-wait",
})


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
    """3.11 & 3.12: count quiet replies whose wall-clock window `(prev_returned_at, returned_at]`
    contained any AVST event in FALSE_QUIET_EVENTS (`popup_shown`, `snap_back`, `fail`, `target_missed`).
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
        hits = []
        for ev in events:
            name = ev.get("ev")
            if name not in FALSE_QUIET_EVENTS:
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
    reply_texts = []
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
            reply_texts.append(str(reply.get("text") or ""))
            cmd_records.append({
                "index": idx,
                "argv": list(argv),
                "prev_returned_at": prev_returned_at,
                "returned_at": cur_returned_at,
                "is_quiet": is_q,
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

    task_files = sorted(Path(args.tasks_dir).glob("*.json"))
    if not task_files:
        raise SystemExit(f"No task files in {args.tasks_dir}")

    rows = []
    skipped = []
    repeat_rows = [] if args.repeat_check else None

    for tf in task_files:
        task = json.loads(tf.read_text(encoding="utf-8"))
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

    out_dir = Path(args.out)
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
    if regressed:
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
