#!/usr/bin/env python3
"""Unit and gate tests for tools/eval/run.py (Fix 08) and tools/eval/compare.py (Fix 13)."""

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

EVAL_DIR = Path(__file__).resolve().parent
if str(EVAL_DIR) not in sys.path:
    sys.path.insert(0, str(EVAL_DIR))

import compare  # noqa: E402
import run  # noqa: E402


def _make_row(tid, ok=True, scene="S01", error=None):
    return {
        "id": tid,
        "scene": scene,
        "seed": 7,
        "since": None,
        "ok": ok,
        "turns": 1,
        "chars": 200,
        "est_tokens": 100,
        "structured_est_tokens": 120,
        "image_tokens": 50,
        "phone_ms": 200,
        "wall_ms": 350,
        "reply_est_tokens": [100],
        "reply_image_tokens": [50],
        "quiet_replies": 0,
        "false_quiet": 0,
        "false_quiet_details": [],
        "refetch_after_quiet": 0,
        "loud_reasons": [],
        "rules_fired": [],
        "gestures": 0,
        "reply_has_popup": False,
        "reply_has_toast": False,
        "act_reply_previews": [],
        "error": error,
        "avst_events": [{"scene": scene, "ev": "goal", "ok": ok}],
    }


class EvalRequiredGateTest(unittest.TestCase):
    def test_repo_new_tasks_declare_required_true(self):
        tasks_dir = EVAL_DIR / "tasks"
        defs = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(tasks_dir.glob("*.json"))]
        req = run.collect_required_task_ids(defs)
        for tid in ("T14a", "T15a", "T16a"):
            self.assertIn(tid, req, f"{tid} must be marked required=true")

        merged = run.collect_required_task_ids(defs, cli_required="T01a, T14a, T99x")
        self.assertIn("T01a", merged)
        self.assertIn("T99x", merged)
        self.assertEqual(merged.count("T14a"), 1)

    def test_evaluate_gate_fails_when_required_task_fails_skipped_or_missing_even_if_baseline_passes(self):
        baseline = json.loads((EVAL_DIR / "baseline.json").read_text(encoding="utf-8"))
        base_pass_ids = [t["id"] for t in baseline["tasks"] if t.get("ok")]
        base_rows = [_make_row(tid, ok=True) for tid in base_pass_ids]
        totals = {"false_quiet": 0, "passed": len(base_rows), "total": len(base_rows)}

        # Case 1: required T14a failed while all 13 legacy baseline tasks + beta8 checks passed
        rows_fail = base_rows + [
            _make_row("T14a", ok=False, scene="S14", error="freeze assertion failed"),
            _make_row("T15a", ok=True, scene="S15"),
            _make_row("T16a", ok=True, scene="S16"),
        ]
        ok, msgs, req_fail = run.evaluate_gate(
            rows=rows_fail,
            skipped=[],
            totals=totals,
            baseline=baseline,
            required_ids=["T14a", "T15a", "T16a"],
            beta8_res={"all_ok": True},
        )
        self.assertFalse(ok)
        self.assertEqual([(f["id"], f["reason"]) for f in req_fail], [("T14a", "failed")])
        self.assertTrue(any("T14a (failed)" in m for m in msgs))

        # Case 2: required T15a unexpectedly skipped due to version mismatch
        rows_skip = base_rows + [_make_row("T14a", ok=True), _make_row("T16a", ok=True)]
        ok, msgs, req_fail = run.evaluate_gate(
            rows=rows_skip,
            skipped=[{"id": "T15a", "scene": "S15", "since": "2.0.0-beta8"}],
            totals=totals,
            baseline=baseline,
            required_ids=["T14a", "T15a", "T16a"],
            beta8_res={"all_ok": True},
        )
        self.assertFalse(ok)
        self.assertEqual([(f["id"], f["reason"]) for f in req_fail], [("T15a", "skipped")])

        # Case 3: required T16a missing completely
        ok, msgs, req_fail = run.evaluate_gate(
            rows=base_rows + [_make_row("T14a", ok=True), _make_row("T15a", ok=True)],
            skipped=[],
            totals=totals,
            baseline=baseline,
            required_ids=["T14a", "T15a", "T16a"],
            beta8_res={"all_ok": True},
        )
        self.assertFalse(ok)
        self.assertEqual([(f["id"], f["reason"]) for f in req_fail], [("T16a", "missing")])

        # Case 4: all baseline + all required pass -> gate succeeds
        ok, msgs, req_fail = run.evaluate_gate(
            rows=base_rows + [_make_row("T14a", ok=True), _make_row("T15a", ok=True), _make_row("T16a", ok=True)],
            skipped=[],
            totals=totals,
            baseline=baseline,
            required_ids=["T14a", "T15a", "T16a"],
            beta8_res={"all_ok": True},
        )
        self.assertTrue(ok)
        self.assertEqual(msgs, [])
        self.assertEqual(req_fail, [])

    def test_run_main_exits_nonzero_when_required_task_fails_and_zero_when_all_pass(self):
        baseline_path = EVAL_DIR / "baseline.json"
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            fake_apk = tmp_path / "torture.apk"
            fake_apk.write_bytes(b"PK\x03\x04")
            out_fail = tmp_path / "out_fail"
            out_pass = tmp_path / "out_pass"

            def fake_run_task_failing_t14(_token, task, return_events=False):
                tid = task["id"]
                if tid == "T14a":
                    row = _make_row(tid, ok=False, scene=task["scene"], error="intentional failure")
                else:
                    row = _make_row(tid, ok=True, scene=task["scene"])
                return (row, row["avst_events"]) if return_events else row

            def fake_run_task_all_pass(_token, task, return_events=False):
                row = _make_row(task["id"], ok=True, scene=task["scene"])
                return (row, row["avst_events"]) if return_events else row

            argv_fail = [
                "run.py",
                "--apk", str(fake_apk),
                "--tasks-dir", str(EVAL_DIR / "tasks"),
                "--baseline", str(baseline_path),
                "--out", str(out_fail),
                "--beta8-checks",
            ]
            with mock.patch.object(sys, "argv", argv_fail), \
                 mock.patch.object(run, "ensure_host_setup"), \
                 mock.patch.object(run, "run_cmd"), \
                 mock.patch.object(run, "seed_token", return_value="tok"), \
                 mock.patch.object(run, "ping", return_value={"version": "2.0.1-beta1"}), \
                 mock.patch.object(run, "call_api", return_value={"ok": True}), \
                 mock.patch.object(run, "run_task", side_effect=fake_run_task_failing_t14), \
                 mock.patch.object(run, "run_beta8_checks", return_value={"all_ok": True}):
                with self.assertRaises(SystemExit) as cm:
                    run.main()
                self.assertEqual(cm.exception.code, 1)

            result_fail = json.loads((out_fail / "result.json").read_text(encoding="utf-8"))
            self.assertIn("T14a", result_fail["required_tasks"])
            self.assertEqual(
                [(f["id"], f["reason"]) for f in result_fail["required_task_failures"]],
                [("T14a", "failed")],
            )

            argv_pass = [
                "run.py",
                "--apk", str(fake_apk),
                "--tasks-dir", str(EVAL_DIR / "tasks"),
                "--baseline", str(baseline_path),
                "--out", str(out_pass),
                "--beta8-checks",
            ]
            with mock.patch.object(sys, "argv", argv_pass), \
                 mock.patch.object(run, "ensure_host_setup"), \
                 mock.patch.object(run, "run_cmd"), \
                 mock.patch.object(run, "seed_token", return_value="tok"), \
                 mock.patch.object(run, "ping", return_value={"version": "2.0.1-beta1"}), \
                 mock.patch.object(run, "call_api", return_value={"ok": True}), \
                 mock.patch.object(run, "run_task", side_effect=fake_run_task_all_pass), \
                 mock.patch.object(run, "run_beta8_checks", return_value={"all_ok": True}):
                run.main()

            result_pass = json.loads((out_pass / "result.json").read_text(encoding="utf-8"))
            self.assertEqual(result_pass["required_task_failures"], [])


class EvalCompareTest(unittest.TestCase):
    def test_times_grouped_dict_detects_full_and_partial_regressions_and_fails_cli(self):
        base_times = {
            "app_version": "2.0.1-base",
            "times": 3,
            "tasks": {
                "T01a": [_make_row("T01a", ok=True), _make_row("T01a", ok=True), _make_row("T01a", ok=True)],
                "T04b": [_make_row("T04b", ok=True), _make_row("T04b", ok=True), _make_row("T04b", ok=True)],
            },
        }
        cand_times = {
            "app_version": "2.0.1-cand",
            "times": 3,
            "tasks": {
                "T01a": [_make_row("T01a", ok=False), _make_row("T01a", ok=False), _make_row("T01a", ok=False)],
                "T04b": [_make_row("T04b", ok=True), _make_row("T04b", ok=True), _make_row("T04b", ok=False)],
            },
        }
        md, regressions = compare.render_comparison(base_times, cand_times)
        self.assertEqual(regressions, ["T01a", "T04b"])
        self.assertIn("3/3 (100%)", md)
        self.assertIn("**0/3 (0%) (REGRESSED)**", md)
        self.assertIn("**2/3 (67%) (REGRESSED)**", md)

        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            p_base = tmp_path / "base.json"
            p_cand = tmp_path / "cand.json"
            p_base.write_text(json.dumps(base_times), encoding="utf-8")
            p_cand.write_text(json.dumps(cand_times), encoding="utf-8")
            with mock.patch.object(sys, "argv", ["compare.py", str(p_base), str(p_cand), "--fail-on-regression"]):
                with self.assertRaises(SystemExit) as cm:
                    compare.main()
                self.assertEqual(cm.exception.code, 1)

    def test_missing_legacy_metrics_render_not_collected_instead_of_zero(self):
        baseline = json.loads((EVAL_DIR / "baseline.json").read_text(encoding="utf-8"))
        md, regressions = compare.render_comparison(baseline, baseline)
        self.assertEqual(regressions, [])
        self.assertIn(f"| Structured est_tokens | {compare.NOT_COLLECTED} |", md)
        self.assertIn(f"| Wall ms (total) | {compare.NOT_COLLECTED} |", md)
        self.assertNotIn("| Structured est_tokens | 0 -> 0 |", md)
        self.assertNotIn("| Wall ms (total) | 0ms -> 0ms |", md)

    def test_malformed_tasks_shape_fails_loudly_with_exit_2(self):
        bad_shapes = [
            {"app_version": "bad", "tasks": "not-a-list-or-dict"},
            {"app_version": "bad", "tasks": {"T01a": []}},
            {"app_version": "bad", "tasks": [{"id": "T01a"}]},  # missing boolean ok
        ]
        for bad in bad_shapes:
            with self.assertRaises(ValueError):
                compare.render_comparison(bad, bad)

        with tempfile.TemporaryDirectory() as tmp:
            p_bad = Path(tmp) / "bad.json"
            p_bad.write_text(json.dumps({"tasks": 12345}), encoding="utf-8")
            with mock.patch.object(sys, "argv", ["compare.py", str(p_bad), str(p_bad)]):
                with self.assertRaises(SystemExit) as cm:
                    compare.main()
                self.assertEqual(cm.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
