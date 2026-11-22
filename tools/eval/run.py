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
    return events


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

            if idx == 0 and argv and argv[0] == "launch":
                m_disp = re.search(r"(?:副屏|screen)\s+(\d+)", reply.get("text", ""))
                disp_id = int(m_disp.group(1)) if m_disp else find_scene_display_id()
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

    ok = (not timed_out) and (not error_detail) and evaluate_events(events, expect_list, forbid_list)
    row = {
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
    }


def fmt_diff(val, is_float=False):
    if is_float:
        return f"{val:+.2%}"
    return f"{val:+d}"


def render_markdown(app_version, rows, skipped, totals, diff, regressed, repeat_rows=None):
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
        gate_txt = "无退化" if not regressed else f"退化: {', '.join(regressed)}"
        lines.append(
            f"| **与 baseline 差值** | **{fmt_diff(diff['pass_rate'], is_float=True)}** | **{gate_txt}** | "
            f"**{fmt_diff(diff['turns'])}** | **{fmt_diff(diff['chars'])}** | "
            f"**{fmt_diff(diff['est_tokens'])}** | **{fmt_diff(diff['image_tokens'])}** | "
            f"**{fmt_diff(diff['phone_ms'])}** |"
        )
    if skipped:
        lines.extend(["", f"跳过未到阶段的任务（{len(skipped)} 个）：" + ", ".join(f"{s['id']}({s['since']})" for s in skipped)])

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
            f"turns={res['turns']} est_tokens={res['est_tokens']} img_tokens={res['image_tokens']} phone_ms={res['phone_ms']}"
            + (f" err={res['error']}" if res["error"] else ""),
            flush=True,
        )
        rows.append(res)

        if args.repeat_check and not since:
            _, evs2 = run_task(token, task, return_events=True)
            seq1 = normalize_avst_sequence(evs1)
            seq2 = normalize_avst_sequence(evs2)
            matched = seq1 == seq2
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

    result_obj = {
        "app_version": app_version,
        "generated_at": int(time.time()),
        "tasks": rows,
        "skipped_tasks": skipped,
        "totals": totals,
        "diff_vs_baseline": diff,
        "regressed_tasks": regressed,
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
    md = render_markdown(app_version, rows, skipped, totals, diff, regressed, repeat_rows=repeat_rows)
    (out_dir / "result.md").write_text(md, encoding="utf-8")
    print("\n" + md, flush=True)

    if args.write_baseline:
        base_copy = dict(result_obj)
        base_copy["diff_vs_baseline"] = None
        base_copy["regressed_tasks"] = []
        base_copy.pop("repeat_check", None)
        baseline_path.write_text(json.dumps(base_copy, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    failed_gate = False
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
