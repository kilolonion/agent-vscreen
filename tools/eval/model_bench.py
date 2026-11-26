#!/usr/bin/env python3
"""Real-model benchmark for Agent-VScreen 2.0 using Anthropic Messages API (stdlib only).

Model is fixed to `claude-sonnet-5-5`, API key read from `ANTHROPIC_API_KEY`.
Single tool `vscreen(argv: string[])` forwards to the HTTP API; system prompt comes from `GET /skill` (guide).
Not executed in CI.
"""
import argparse
import base64
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run as eval_run  # noqa: E402

MODEL_ID = "claude-sonnet-5-5"
ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
MAX_TURNS = 30

BENCH_TASKS = [
    {
        "id": "M01_S02",
        "scene": "S02",
        "seed": 7,
        "goal_prompt": "打开应用 com.kilon.avstorture，如果出现弹窗请先关掉弹窗，然后点击页面上的“确定”按钮使页面显示已确定，完成后执行 done。",
        "expect": [{"ev": "goal", "ok": True}],
        "forbid": [{"ev": "fail"}],
    },
    {
        "id": "M02_S04",
        "scene": "S04",
        "seed": 7,
        "goal_prompt": "打开应用 com.kilon.avstorture，把画面下方的三张卡片全部拖进上方的槽位中吸附放稳（松手后不弹回），完成后执行 done。",
        "expect": [{"ev": "goal", "ok": True}],
        "forbid": [{"ev": "fail"}],
    },
    {
        "id": "M03_S10",
        "scene": "S10",
        "seed": 7,
        "goal_prompt": "打开应用 com.kilon.avstorture，将滑条拖动调节到 70（允许 68–72）后松手，完成后执行 done。",
        "expect": [{"ev": "goal", "ok": True}],
        "forbid": [{"ev": "fail"}],
    },
    {
        "id": "M04_S11",
        "scene": "S11",
        "seed": 7,
        "goal_prompt": "打开应用 com.kilon.avstorture，按住“按住充能”按钮，在进度达到 90%–100% 之间松手（切勿超过 100% 导致过载），完成后执行 done。",
        "expect": [{"ev": "goal", "ok": True}],
        "forbid": [{"ev": "fail"}],
    },
    {
        "id": "M05_S13",
        "scene": "S13",
        "seed": 7,
        "goal_prompt": "打开应用 com.kilon.avstorture，在长列表中向下翻找到“目标 147”并点击打开它，完成后执行 done。",
        "expect": [{"ev": "goal", "ok": True}],
        "forbid": [{"ev": "fail"}],
    },
]

TOOL_DEF = {
    "name": "vscreen",
    "description": "Execute an Agent-VScreen command on the phone's background virtual screen, passing argv tokens after `vscreen` (e.g. [\"launch\", \"com.kilon.avstorture\"] or [\"act\", \"tap 确定\"]).",
    "input_schema": {
        "type": "object",
        "properties": {
            "argv": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Command tokens after vscreen",
            }
        },
        "required": ["argv"],
    },
}


def fetch_guide():
    req = urllib.request.Request(f"{eval_run.BASE_URL}/skill", headers={"X-VScreen-Lang": "zh"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        text = resp.read().decode("utf-8")
    if text.startswith("---\n"):
        end = text.find("\n---\n", 4)
        if end != -1:
            text = text[end + 5 :].lstrip()
    return text


def fetch_shot_base64(token, url):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return base64.b64encode(resp.read()).decode("ascii")


def anthropic_call(api_key, system_prompt, messages):
    body = json.dumps({
        "model": MODEL_ID,
        "max_tokens": 1024,
        "system": system_prompt,
        "tools": [TOOL_DEF],
        "messages": messages,
    }).encode("utf-8")
    req = urllib.request.Request(
        ANTHROPIC_URL,
        data=body,
        method="POST",
        headers={
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))


def run_one(api_key, token, system_prompt, spec, run_idx, reply_mode="quiet"):
    scene = spec["scene"]
    seed_val = spec["seed"] + run_idx
    session = f"mbench_{reply_mode}_{spec['id']}_{run_idx}"

    eval_run.adb_shell("am", "force-stop", eval_run.TORTURE_PKG)
    eval_run.adb_shell(
        "am", "broadcast", "-W", "--include-stopped-packages",
        "-n", f"{eval_run.TORTURE_PKG}/.PresetReceiver",
        "--es", "scene", scene,
        "--ei", "seed", str(seed_val),
    )
    eval_run.adb_shell("logcat", "-c")
    eval_run.call_api(token, ["note", "reply", reply_mode], session=session, timeout=30)

    messages = [{"role": "user", "content": spec["goal_prompt"]}]
    turns = 0
    in_tokens = 0
    out_tokens = 0
    quiet_replies = 0
    refetch_after_quiet = 0
    cmd_records = []
    cmd_idx = 0
    prev_returned_at = 0
    prev_was_quiet = False
    t0 = time.monotonic()

    try:
        for _ in range(MAX_TURNS):
            resp = anthropic_call(api_key, system_prompt, messages)
            turns += 1
            usage = resp.get("usage") or {}
            in_tokens += int(usage.get("input_tokens", 0))
            out_tokens += int(usage.get("output_tokens", 0))

            content = resp.get("content") or []
            messages.append({"role": "assistant", "content": content})

            tool_uses = [b for b in content if b.get("type") == "tool_use"]
            if not tool_uses:
                break

            tool_results = []
            finished = False
            for tu in tool_uses:
                argv = (tu.get("input") or {}).get("argv") or []
                if prev_was_quiet and eval_run.is_refetch_command(argv):
                    refetch_after_quiet += 1
                reply = eval_run.call_api(token, argv, session=session, timeout=90)
                cur_returned_at = int(reply.get("returned_at") or 0)
                is_q = eval_run.is_quiet_reply(argv, reply)
                if is_q:
                    quiet_replies += 1
                cmd_records.append({
                    "index": cmd_idx,
                    "argv": list(argv),
                    "prev_returned_at": prev_returned_at,
                    "returned_at": cur_returned_at,
                    "is_quiet": is_q,
                })
                cmd_idx += 1
                if cur_returned_at > 0:
                    prev_returned_at = cur_returned_at
                prev_was_quiet = is_q

                blocks = [{"type": "text", "text": reply.get("text", "")}]
                for img in reply.get("images") or []:
                    img_url = img.get("url")
                    if img_url:
                        try:
                            b64 = fetch_shot_base64(token, img_url)
                            blocks.append({
                                "type": "image",
                                "source": {"type": "base64", "media_type": "image/jpeg", "data": b64},
                            })
                        except Exception:
                            pass
                tool_results.append({
                    "type": "tool_result",
                    "tool_use_id": tu["id"],
                    "content": blocks,
                })
                if argv and argv[0] in ("done", "release"):
                    finished = True
            messages.append({"role": "user", "content": tool_results})
            if finished:
                break
    finally:
        wall_s = round(time.monotonic() - t0, 2)
        events = eval_run.read_avst_events(scene)
        try:
            eval_run.call_api(token, ["release"], session=session, timeout=30)
        except Exception:
            pass
        eval_run.adb_shell("am", "force-stop", eval_run.TORTURE_PKG)

    false_quiet, false_quiet_details = eval_run.count_false_quiet(cmd_records, events)
    ok = eval_run.evaluate_events(events, spec["expect"], spec["forbid"])
    return {
        "mode": reply_mode,
        "task": spec["id"],
        "scene": scene,
        "run": run_idx + 1,
        "ok": ok,
        "turns": turns,
        "input_tokens": in_tokens,
        "output_tokens": out_tokens,
        "wall_seconds": wall_s,
        "quiet_replies": quiet_replies,
        "false_quiet": false_quiet,
        "false_quiet_details": false_quiet_details,
        "refetch_after_quiet": refetch_after_quiet,
    }


def summarize_mode(records):
    total = len(records)
    passed = sum(1 for r in records if r["ok"])
    return {
        "passed": passed,
        "total": total,
        "pass_rate": round(passed / total, 4) if total else 0.0,
        "avg_turns": round(sum(r["turns"] for r in records) / max(1, total), 2),
        "input_tokens": sum(r["input_tokens"] for r in records),
        "output_tokens": sum(r["output_tokens"] for r in records),
        "wall_seconds": round(sum(r["wall_seconds"] for r in records), 2),
        "quiet_replies": sum(int(r.get("quiet_replies", 0)) for r in records),
        "false_quiet": sum(int(r.get("false_quiet", 0)) for r in records),
        "refetch_after_quiet": sum(int(r.get("refetch_after_quiet", 0)) for r in records),
    }


def compare_quiet_vs_full(quiet_tot, full_tot):
    """3.10 & 3.11 gate: quiet pass_rate >= full, avg_turns <= full * 1.10, false_quiet == 0."""
    turns_change = round((quiet_tot["avg_turns"] - full_tot["avg_turns"]) / full_tot["avg_turns"], 4) if full_tot["avg_turns"] > 0 else 0.0
    in_drop = round((full_tot["input_tokens"] - quiet_tot["input_tokens"]) / full_tot["input_tokens"], 4) if full_tot["input_tokens"] > 0 else 0.0
    gate_ok = (
        quiet_tot["pass_rate"] >= full_tot["pass_rate"]
        and (full_tot["avg_turns"] == 0 or quiet_tot["avg_turns"] <= full_tot["avg_turns"] * 1.10)
        and quiet_tot["false_quiet"] == 0
    )
    return {
        "pass_rate_delta": round(quiet_tot["pass_rate"] - full_tot["pass_rate"], 4),
        "avg_turns_change_pct": turns_change,
        "input_tokens_drop_pct": in_drop,
        "false_quiet": quiet_tot["false_quiet"],
        "refetch_after_quiet": quiet_tot["refetch_after_quiet"],
        "gate_pass": gate_ok,
    }


def render_markdown(summary):
    lines = [
        f"# Agent-VScreen 模型对照评测（{summary['model']}，每任务 {summary['runs_per_task']} 次）",
        "",
        "| 模式 | 通过率 | 平均轮数 | Input Tokens | Output Tokens | 误静默 | 短回复数 | refetch_after_quiet | 耗时 (s) |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for mode, tot in summary["by_mode"].items():
        lines.append(
            f"| `{mode}` | {tot['passed']}/{tot['total']} ({tot['pass_rate']:.1%}) | {tot['avg_turns']} | "
            f"{tot['input_tokens']} | {tot['output_tokens']} | {tot['false_quiet']} | "
            f"{tot['quiet_replies']} | {tot['refetch_after_quiet']} | {tot['wall_seconds']} |"
        )
    cmp = summary.get("comparison")
    if cmp:
        lines.extend([
            "",
            "## quiet vs full 对照结论（3.10 / 3.11）",
            f"- 成功率差值：`{cmp['pass_rate_delta']:+.1%}`（要求 ≥ 0%）",
            f"- 平均轮数变化：`{cmp['avg_turns_change_pct']:+.1%}`（要求 ≤ +10%）",
            f"- Input token 降幅：`{cmp['input_tokens_drop_pct']:.1%}`（3.10 目标 ≥ 40%）",
            f"- 误静默：`{cmp['false_quiet']}`（要求 = 0）；`refetch_after_quiet`：`{cmp['refetch_after_quiet']}`",
            f"- 3.11 门槛：**{'通过' if cmp['gate_pass'] else '未通过'}**",
        ])
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description="Run Anthropic model benchmark against AVSTorture.")
    parser.add_argument("--apk", required=True, help="Path to torture debug APK")
    parser.add_argument("--out", default="eval-model-out", help="Output directory")
    parser.add_argument("--runs", type=int, default=3, help="Runs per task per mode (default: 3)")
    parser.add_argument("--mode", choices=["both", "quiet", "full"], default="both", help="Reply mode(s) to benchmark (default: both)")
    parser.add_argument("--task", default=None, help="Optional single task id filter (e.g. M01_S02)")
    args = parser.parse_args()

    api_key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("ANTHROPIC_API_KEY environment variable is required")

    torture_apk = Path(args.apk)
    eval_run.ensure_host_setup(str(torture_apk.parent / "app-debug.apk"), str(torture_apk.parent / "shizuku.apk"))
    eval_run.run_cmd("adb", "install", "-r", "-g", str(torture_apk), timeout=240)
    token = eval_run.seed_token()
    guide = fetch_guide()

    selected = [t for t in BENCH_TASKS if args.task in (None, t["id"], t["scene"])]
    if not selected:
        raise SystemExit(f"No matching benchmark task for {args.task}")

    modes = ["quiet", "full"] if args.mode == "both" else [args.mode]
    records = []
    by_mode_records = {}
    try:
        for mode in modes:
            mode_recs = []
            for spec in selected:
                for r in range(args.runs):
                    rec = run_one(api_key, token, guide, spec, r, reply_mode=mode)
                    print(
                        f"[{mode}] [{'PASS' if rec['ok'] else 'FAIL'}] {rec['task']} #{rec['run']} "
                        f"turns={rec['turns']} in={rec['input_tokens']} out={rec['output_tokens']} "
                        f"quiet={rec['quiet_replies']} false_quiet={rec['false_quiet']} "
                        f"refetch={rec['refetch_after_quiet']} wall={rec['wall_seconds']}s",
                        flush=True,
                    )
                    mode_recs.append(rec)
                    records.append(rec)
            by_mode_records[mode] = mode_recs
    finally:
        try:
            eval_run.call_api(token, ["note", "reply", "quiet"], session="mbench_cleanup", timeout=30)
        except Exception:
            pass

    by_mode = {m: summarize_mode(recs) for m, recs in by_mode_records.items()}
    comparison = compare_quiet_vs_full(by_mode["quiet"], by_mode["full"]) if "quiet" in by_mode and "full" in by_mode else None

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    summary = {
        "model": MODEL_ID,
        "mode": args.mode,
        "runs_per_task": args.runs,
        "records": records,
        "by_mode": by_mode,
        "comparison": comparison,
        "totals": summarize_mode(records),
    }
    (out_dir / "model_bench.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    md = render_markdown(summary)
    (out_dir / "model_bench.md").write_text(md, encoding="utf-8")
    print("\n" + md, flush=True)


if __name__ == "__main__":
    main()
