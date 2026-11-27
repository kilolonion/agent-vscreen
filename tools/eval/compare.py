#!/usr/bin/env python3
"""Compare two Agent-VScreen evaluation result JSON files and emit a Markdown regression report.

Supports both single-run reports (`tasks: [...]`) and `--times` multi-run reports (`tasks: {id: [...]}`).
Missing metrics are displayed explicitly as "未采集" rather than coerced to 0, and malformed inputs fail loudly.

Usage:
  python3 tools/eval/compare.py <baseline.json> <candidate.json> [--out report.md] [--fail-on-regression]
"""
import argparse
import json
import sys
from pathlib import Path

NOT_COLLECTED = "未采集"


def _opt_int(obj, key):
    if not isinstance(obj, dict) or key not in obj or obj[key] is None:
        return None
    val = obj[key]
    if isinstance(val, bool) or not isinstance(val, (int, float)):
        raise ValueError(f"Metric '{key}' must be numeric, got {type(val).__name__}")
    return int(val)


def _sum_opt(runs, key):
    vals = [_opt_int(r, key) for r in runs]
    present = [v for v in vals if v is not None]
    if not present:
        return None
    return sum(present)


def _avg_opt(runs, key):
    vals = [_opt_int(r, key) for r in runs]
    present = [v for v in vals if v is not None]
    if not present:
        return None
    return int(round(sum(present) / len(present)))


def _normalize_report(report, label):
    if not isinstance(report, dict):
        raise ValueError(f"{label}: expected JSON object at top level, got {type(report).__name__}")
    if "tasks" not in report:
        raise ValueError(f"{label}: missing required 'tasks' field")
    raw_tasks = report["tasks"]
    tasks = {}
    multi_run = False

    if isinstance(raw_tasks, list):
        for idx, item in enumerate(raw_tasks):
            if not isinstance(item, dict):
                raise ValueError(f"{label}: tasks[{idx}] must be an object, got {type(item).__name__}")
            tid = item.get("id")
            if not isinstance(tid, str) or not tid.strip():
                raise ValueError(f"{label}: tasks[{idx}] missing non-empty string 'id'")
            if not isinstance(item.get("ok"), bool):
                raise ValueError(f"{label}: task '{tid}' missing boolean 'ok'")
            ok = item["ok"]
            tasks[tid] = {
                "id": tid,
                "scene": item.get("scene", "-"),
                "passed": 1 if ok else 0,
                "total": 1,
                "rate": 1.0 if ok else 0.0,
                "ok": ok,
                "multi": False,
                "est_tokens": _opt_int(item, "est_tokens"),
                "structured_est_tokens": _opt_int(item, "structured_est_tokens"),
                "image_tokens": _opt_int(item, "image_tokens"),
                "phone_ms": _opt_int(item, "phone_ms"),
                "wall_ms": _opt_int(item, "wall_ms"),
                "false_quiet": _opt_int(item, "false_quiet"),
            }
    elif isinstance(raw_tasks, dict):
        multi_run = True
        for tid, runs in raw_tasks.items():
            if not isinstance(tid, str) or not tid.strip():
                raise ValueError(f"{label}: grouped tasks map has empty task id")
            if not isinstance(runs, list) or not runs:
                raise ValueError(f"{label}: grouped tasks['{tid}'] must be a non-empty list of runs")
            for idx, r in enumerate(runs):
                if not isinstance(r, dict):
                    raise ValueError(f"{label}: tasks['{tid}'][{idx}] must be an object")
                if not isinstance(r.get("ok"), bool):
                    raise ValueError(f"{label}: tasks['{tid}'][{idx}] missing boolean 'ok'")
            passed = sum(1 for r in runs if r["ok"])
            total = len(runs)
            tasks[tid] = {
                "id": tid,
                "scene": runs[0].get("scene", "-"),
                "passed": passed,
                "total": total,
                "rate": passed / total,
                "ok": passed == total,
                "multi": True,
                "est_tokens": _avg_opt(runs, "est_tokens"),
                "structured_est_tokens": _avg_opt(runs, "structured_est_tokens"),
                "image_tokens": _avg_opt(runs, "image_tokens"),
                "phone_ms": _avg_opt(runs, "phone_ms"),
                "wall_ms": _avg_opt(runs, "wall_ms"),
                "false_quiet": _sum_opt(runs, "false_quiet"),
            }
    else:
        raise ValueError(
            f"{label}: unsupported 'tasks' shape ({type(raw_tasks).__name__}); "
            "expected list (single-run) or object (run.py --times grouped output)"
        )

    raw_totals = report.get("totals")
    if raw_totals is not None and not isinstance(raw_totals, dict):
        raise ValueError(f"{label}: 'totals' must be an object if present")

    if isinstance(raw_totals, dict):
        totals = {
            "passed": _opt_int(raw_totals, "passed"),
            "total": _opt_int(raw_totals, "total"),
            "est_tokens": _opt_int(raw_totals, "est_tokens"),
            "structured_est_tokens": _opt_int(raw_totals, "structured_est_tokens"),
            "image_tokens": _opt_int(raw_totals, "image_tokens"),
            "phone_ms": _opt_int(raw_totals, "phone_ms"),
            "wall_ms": _opt_int(raw_totals, "wall_ms"),
            "false_quiet": _opt_int(raw_totals, "false_quiet"),
        }
    else:
        # Synthesize totals from task rows (e.g. run.py --times output).
        all_runs = (
            [r for runs in raw_tasks.values() for r in runs]
            if multi_run
            else list(raw_tasks)
        )
        totals = {
            "passed": sum(1 for r in all_runs if r["ok"]),
            "total": len(all_runs),
            "est_tokens": _sum_opt(all_runs, "est_tokens"),
            "structured_est_tokens": _sum_opt(all_runs, "structured_est_tokens"),
            "image_tokens": _sum_opt(all_runs, "image_tokens"),
            "phone_ms": _sum_opt(all_runs, "phone_ms"),
            "wall_ms": _sum_opt(all_runs, "wall_ms"),
            "false_quiet": _sum_opt(all_runs, "false_quiet"),
        }

    return {
        "app_version": report.get("app_version", label),
        "multi_run": multi_run,
        "times": report.get("times"),
        "tasks": tasks,
        "totals": totals,
    }


def _fmt_delta(a, b, suffix=""):
    if a is None and b is None:
        return NOT_COLLECTED
    if a is None:
        return f"{NOT_COLLECTED} -> {b}{suffix}"
    if b is None:
        return f"{a}{suffix} -> {NOT_COLLECTED}"
    diff = b - a
    sign = "+" if diff > 0 else ""
    pct = f" ({sign}{(diff / a) * 100:.1f}%)" if a else ""
    return f"{a}{suffix} -> {b}{suffix} ({sign}{diff}{suffix}{pct})"


def _fmt_pair(a, b):
    sa = NOT_COLLECTED if a is None else str(a)
    sb = NOT_COLLECTED if b is None else str(b)
    return f"{sa} -> {sb}"


def _fmt_status(entry):
    if entry is None:
        return "—"
    if entry["multi"]:
        return f"{entry['passed']}/{entry['total']} ({entry['rate'] * 100:.0f}%)"
    return "PASS" if entry["ok"] else "FAIL"


def render_comparison(base, cand):
    nb = _normalize_report(base, "baseline")
    nc = _normalize_report(cand, "candidate")
    bt = nb["totals"]
    ct = nc["totals"]
    base_tasks = nb["tasks"]
    cand_tasks = nc["tasks"]
    all_ids = sorted(set(base_tasks) | set(cand_tasks))

    regressions = []
    improvements = []
    for tid in all_ids:
        b = base_tasks.get(tid)
        c = cand_tasks.get(tid)
        b_rate = b["rate"] if b else 0.0
        c_rate = c["rate"] if c else 0.0
        if b and b_rate > 0.0 and (c is None or c_rate < b_rate):
            regressions.append(tid)
        elif c and c_rate > b_rate:
            improvements.append(tid)

    b_pass_str = (
        f"{bt['passed']}/{bt['total']}"
        if bt["passed"] is not None and bt["total"] is not None
        else NOT_COLLECTED
    )
    c_pass_str = (
        f"{ct['passed']}/{ct['total']}"
        if ct["passed"] is not None and ct["total"] is not None
        else NOT_COLLECTED
    )
    fq_str = _fmt_pair(bt["false_quiet"], ct["false_quiet"])

    lines = [
        f"# Eval Comparison: `{nb['app_version']}` vs `{nc['app_version']}`",
        "",
        "## Summary",
        "",
        "| Metric | Change |",
        "|---|---|",
        f"| Passed / Total | {b_pass_str} -> {c_pass_str} |",
        f"| Text est_tokens | {_fmt_delta(bt['est_tokens'], ct['est_tokens'])} |",
        f"| Structured est_tokens | {_fmt_delta(bt['structured_est_tokens'], ct['structured_est_tokens'])} |",
        f"| Image tokens | {_fmt_delta(bt['image_tokens'], ct['image_tokens'])} |",
        f"| Phone ms (total) | {_fmt_delta(bt['phone_ms'], ct['phone_ms'], 'ms')} |",
        f"| Wall ms (total) | {_fmt_delta(bt['wall_ms'], ct['wall_ms'], 'ms')} |",
        f"| False quiet | {fq_str} |",
        f"| Regressions | {', '.join(regressions) if regressions else 'none'} |",
        f"| Improvements | {', '.join(improvements) if improvements else 'none'} |",
        "",
        "## Per-Task Breakdown",
        "",
        "| Task | Scene | Baseline | Candidate | Text Tokens | Image Tokens | Phone ms |",
        "|---|---|---|---|---:|---:|---:|",
    ]

    for tid in all_ids:
        b = base_tasks.get(tid)
        c = cand_tasks.get(tid)
        scene = (c or b or {}).get("scene", "-")
        b_st = _fmt_status(b)
        c_st = _fmt_status(c)
        if tid in regressions:
            c_st = f"**{c_st} (REGRESSED)**"
        btok = b["est_tokens"] if b else None
        ctok = c["est_tokens"] if c else None
        bimg = b["image_tokens"] if b else None
        cimg = c["image_tokens"] if c else None
        bms = b["phone_ms"] if b else None
        cms = c["phone_ms"] if c else None
        lines.append(
            f"| `{tid}` | {scene} | {b_st} | {c_st} | {_fmt_pair(btok, ctok)} | {_fmt_pair(bimg, cimg)} | {_fmt_pair(bms, cms)} |"
        )

    return "\n".join(lines) + "\n", regressions


def main():
    ap = argparse.ArgumentParser(description="Compare two Agent-VScreen eval JSON reports.")
    ap.add_argument("baseline", type=Path, help="Path to baseline JSON (e.g. tools/eval/baseline.json)")
    ap.add_argument("candidate", type=Path, help="Path to candidate result.json")
    ap.add_argument("--out", type=Path, default=None, help="Optional output Markdown path")
    ap.add_argument("--fail-on-regression", action="store_true", help="Exit non-zero if any passing task regressed")
    args = ap.parse_args()

    try:
        base = json.loads(args.baseline.read_text(encoding="utf-8"))
        cand = json.loads(args.candidate.read_text(encoding="utf-8"))
        md, regressions = render_comparison(base, cand)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(2)

    if args.out:
        args.out.write_text(md, encoding="utf-8")
    else:
        print(md, end="")
    if args.fail_on_regression and regressions:
        print(f"REGRESSION DETECTED: {regressions}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
