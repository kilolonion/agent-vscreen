#!/usr/bin/env python3
"""Compare two Agent-VScreen evaluation result JSON files and emit a Markdown regression report.

Usage:
  python3 tools/eval/compare.py <baseline.json> <candidate.json> [--out report.md] [--fail-on-regression]
"""
import argparse
import json
import sys
from pathlib import Path


def _fmt_delta(a, b, suffix=""):
    diff = b - a
    sign = "+" if diff > 0 else ""
    pct = f" ({sign}{(diff / a) * 100:.1f}%)" if a else ""
    return f"{a}{suffix} -> {b}{suffix} ({sign}{diff}{suffix}{pct})"


def render_comparison(base, cand):
    base_ver = base.get("app_version", "baseline")
    cand_ver = cand.get("app_version", "candidate")
    bt = base.get("totals") or {}
    ct = cand.get("totals") or {}

    base_tasks = {t["id"]: t for t in (base.get("tasks") or []) if "id" in t}
    cand_tasks = {t["id"]: t for t in (cand.get("tasks") or []) if "id" in t}
    all_ids = sorted(set(base_tasks) | set(cand_tasks))

    regressions = []
    improvements = []
    for tid in all_ids:
        b = base_tasks.get(tid)
        c = cand_tasks.get(tid)
        if b and b.get("ok") and (not c or not c.get("ok")):
            regressions.append(tid)
        elif (not b or not b.get("ok")) and c and c.get("ok"):
            improvements.append(tid)

    lines = [
        f"# Eval Comparison: `{base_ver}` vs `{cand_ver}`",
        "",
        "## Summary",
        "",
        "| Metric | Change |",
        "|---|---|",
        f"| Passed / Total | {bt.get('passed', 0)}/{bt.get('total', 0)} -> {ct.get('passed', 0)}/{ct.get('total', 0)} |",
        f"| Text est_tokens | {_fmt_delta(int(bt.get('est_tokens', 0)), int(ct.get('est_tokens', 0)))} |",
        f"| Structured est_tokens | {_fmt_delta(int(bt.get('structured_est_tokens', 0)), int(ct.get('structured_est_tokens', 0)))} |",
        f"| Image tokens | {_fmt_delta(int(bt.get('image_tokens', 0)), int(ct.get('image_tokens', 0)))} |",
        f"| Phone ms (total) | {_fmt_delta(int(bt.get('phone_ms', 0)), int(ct.get('phone_ms', 0)), 'ms')} |",
        f"| Wall ms (total) | {_fmt_delta(int(bt.get('wall_ms', 0)), int(ct.get('wall_ms', 0)), 'ms')} |",
        f"| False quiet | {bt.get('false_quiet', 0)} -> {ct.get('false_quiet', 0)} |",
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
        b_st = "PASS" if (b and b.get("ok")) else ("FAIL" if b else "—")
        c_st = "PASS" if (c and c.get("ok")) else ("FAIL" if c else "—")
        if b_st == "PASS" and c_st != "PASS":
            c_st = f"**{c_st} (REGRESSED)**"
        btok = int(b.get("est_tokens", 0)) if b else 0
        ctok = int(c.get("est_tokens", 0)) if c else 0
        bimg = int(b.get("image_tokens", 0)) if b else 0
        cimg = int(c.get("image_tokens", 0)) if c else 0
        bms = int(b.get("phone_ms", 0)) if b else 0
        cms = int(c.get("phone_ms", 0)) if c else 0
        lines.append(
            f"| `{tid}` | {scene} | {b_st} | {c_st} | {btok} -> {ctok} | {bimg} -> {cimg} | {bms} -> {cms} |"
        )

    return "\n".join(lines) + "\n", regressions


def main():
    ap = argparse.ArgumentParser(description="Compare two Agent-VScreen eval JSON reports.")
    ap.add_argument("baseline", type=Path, help="Path to baseline JSON (e.g. tools/eval/baseline.json)")
    ap.add_argument("candidate", type=Path, help="Path to candidate result.json")
    ap.add_argument("--out", type=Path, default=None, help="Optional output Markdown path")
    ap.add_argument("--fail-on-regression", action="store_true", help="Exit non-zero if any passing task regressed")
    args = ap.parse_args()

    base = json.loads(args.baseline.read_text(encoding="utf-8"))
    cand = json.loads(args.candidate.read_text(encoding="utf-8"))
    md, regressions = render_comparison(base, cand)
    if args.out:
        args.out.write_text(md, encoding="utf-8")
    else:
        print(md, end="")
    if args.fail_on_regression and regressions:
        print(f"REGRESSION DETECTED: {regressions}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
