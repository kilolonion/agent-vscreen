#!/usr/bin/env python3
"""Agent-VScreen 2.0 scripted evaluation runner (stdlib only).

Usage:
  python3 tools/eval/run.py --apk <torture.apk> --out <dir> [--app-apk <app-debug.apk>] [--baseline <baseline.json>]
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
    """Ensure Agent-VScreen debug APK + Shizuku are installed and granted (mirrors agent_e2e.py)."""
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


def find_torture_display_id():
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
    return events


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


def run_task(token, task):
    task_id = task["id"]
    scene = task["scene"]
    seed_val = int(task.get("seed", 7))
    max_seconds = float(task.get("max_seconds", 60))
    expect_list = task.get("expect") or []
    forbid_list = task.get("forbid") or []
    session = f"eval_{task_id}"

    # Ensure previous state is clean (not counted in task metrics).
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
            reply = call_api(token, argv, session=session, timeout=max(10, int(rem) + 15))
            turns += 1
            meter = reply.get("meter") or {}
            chars += int(meter.get("chars", 0))
            et = int(meter.get("est_tokens", 0))
            est_tokens += et
            reply_est_tokens.append(et)
            image_tokens += int(meter.get("image_tokens", 0))
            phone_ms += int((reply.get("timing") or {}).get("phone_ms", 0))

            # Fallback if launch did not pass scene extras (Section 1.3).
            if idx == 0 and argv and argv[0] == "launch":
                evs = read_avst_events(scene)
                if not any(e.get("ev") == "enter" for e in evs):
                    disp_id = find_torture_display_id()
                    if disp_id:
                        adb_shell("logcat", "-c")
                        adb_shell(
                            "am", "start", "-W", "--activity-clear-top",
                            "--display", str(disp_id),
                            "-n", f"{TORTURE_PKG}/.SceneActivity",
                            "--es", "scene", scene,
                            "--ei", "seed", str(seed_val),
                        )

        # Allow brief settle window for async AVST events within max_seconds.
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

    ok = (not timed_out) and (not error_detail) and evaluate_events(events, expect_list, forbid_list)
    return {
        "id": task_id,
        "scene": scene,
        "seed": seed_val,
        "ok": ok,
        "turns": turns,
        "chars": chars,
        "est_tokens": est_tokens,
        "image_tokens": image_tokens,
        "phone_ms": phone_ms,
        "reply_est_tokens": reply_est_tokens,
        "error": error_detail,
    }


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
    }


def fmt_diff(val, is_float=False):
    if is_float:
        return f"{val:+.2%}"
    return f"{val:+d}"


def render_markdown(app_version, rows, skipped, totals, diff):
    lines = [
        f"# Agent-VScreen 评测结果（{app_version}）",
        "",
        "| 任务 | 场景 | 结果 | 轮数 | 总字数 | 总估算 token | 图片 token | 手机端耗时 (ms) |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for r in rows:
        status = "成功" if r["ok"] else "失败"
        lines.append(
            f"| {r['id']} | {r['scene']} | {status} | {r['turns']} | {r['chars']} | "
            f"{r['est_tokens']} | {r['image_tokens']} | {r['phone_ms']} |"
        )
    lines.append(
        f"| **合计** | **{totals['passed']}/{totals['total']} ({totals['pass_rate']:.1%})** | "
        f"**{'通过' if totals['failed'] == 0 else '有失败'}** | **{totals['turns']}** | **{totals['chars']}** | "
        f"**{totals['est_tokens']}** | **{totals['image_tokens']}** | **{totals['phone_ms']}** |"
    )
    if diff is not None:
        lines.append(
            f"| **与 baseline 差值** | **{fmt_diff(diff['pass_rate'], is_float=True)}** | — | "
            f"**{fmt_diff(diff['turns'])}** | **{fmt_diff(diff['chars'])}** | "
            f"**{fmt_diff(diff['est_tokens'])}** | **{fmt_diff(diff['image_tokens'])}** | "
            f"**{fmt_diff(diff['phone_ms'])}** |"
        )
    if skipped:
        lines.extend(["", f"跳过未到阶段的任务（{len(skipped)} 个）：" + ", ".join(f"{s['id']}({s['since']})" for s in skipped)])
    return "\n".join(lines) + "\n"


def main():
    repo_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description="Run Agent-VScreen torture evaluation suite.")
    parser.add_argument("--apk", required=True, help="Path to torture debug APK")
    parser.add_argument("--out", required=True, help="Output directory for result.json and result.md")
    parser.add_argument("--app-apk", default=None, help="Optional path to Agent-VScreen debug APK")
    parser.add_argument("--shizuku-apk", default=None, help="Optional path to Shizuku APK")
    parser.add_argument("--tasks-dir", default=str(repo_root / "tools" / "eval" / "tasks"))
    parser.add_argument("--baseline", default=str(repo_root / "tools" / "eval" / "baseline.json"))
    parser.add_argument("--write-baseline", action="store_true", help="Copy result.json to baseline path")
    args = parser.parse_args()

    torture_apk = Path(args.apk)
    if not torture_apk.is_file():
        raise SystemExit(f"Missing torture APK: {torture_apk}")

    app_apk = args.app_apk or str(torture_apk.parent / "app-debug.apk")
    shizuku_apk = args.shizuku_apk or str(torture_apk.parent / "shizuku.apk")

    ensure_host_setup(app_apk, shizuku_apk)
    run_cmd("adb", "install", "-r", "-g", str(torture_apk), timeout=240)
    token = seed_token()
    info = ping()
    app_version = info.get("version", "2.0.0-beta1")

    task_files = sorted(Path(args.tasks_dir).glob("*.json"))
    if not task_files:
        raise SystemExit(f"No task files in {args.tasks_dir}")

    rows = []
    skipped = []
    for tf in task_files:
        task = json.loads(tf.read_text(encoding="utf-8"))
        since = task.get("since")
        if not version_allows(app_version, since):
            skipped.append({"id": task["id"], "scene": task["scene"], "since": since})
            continue
        res = run_task(token, task)
        print(
            f"[{'PASS' if res['ok'] else 'FAIL'}] {res['id']} ({res['scene']}) "
            f"turns={res['turns']} est_tokens={res['est_tokens']} img_tokens={res['image_tokens']} phone_ms={res['phone_ms']}"
            + (f" err={res['error']}" if res["error"] else ""),
            flush=True,
        )
        rows.append(res)

    totals = compute_totals(rows)
    baseline_path = Path(args.baseline)
    baseline = json.loads(baseline_path.read_text(encoding="utf-8")) if baseline_path.is_file() else None
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

    result_obj = {
        "app_version": app_version,
        "generated_at": int(time.time()),
        "tasks": rows,
        "skipped_tasks": skipped,
        "totals": totals,
        "diff_vs_baseline": diff,
    }

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "result.json").write_text(json.dumps(result_obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    md = render_markdown(app_version, rows, skipped, totals, diff)
    (out_dir / "result.md").write_text(md, encoding="utf-8")
    print("\n" + md, flush=True)

    if args.write_baseline:
        baseline_path.write_text(json.dumps(result_obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if baseline and "totals" in baseline:
        base_rate = float(baseline["totals"].get("pass_rate", 0.0))
        if totals["pass_rate"] + 1e-9 < base_rate:
            print(f"FAIL: pass_rate {totals['pass_rate']:.2%} < baseline {base_rate:.2%}", file=sys.stderr)
            sys.exit(1)


if __name__ == "__main__":
    main()
