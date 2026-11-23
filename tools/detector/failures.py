#!/usr/bin/env python3
"""Why did actions fail? Counts the reasons in the app's own command record, so we can see whether "target not
found / tap missed" is really the main failure before and after the detector.

  python failures.py diag.txt [more diag files...]      (the output of `vscreen diag`, real phone or eval run)

It reads the "recent commands" lines (`MM-dd HH:mm:ss kind token cmd → CODE 123ms`) and groups the codes:
  not_found     NOT_FOUND, AMBIGUOUS, STALE_ELEMENT: the agent could not name the target
  missed        NOT_MET, NO_CHANGE: the step ran but what it expected did not happen (often a tap beside the target)
  out_of_bounds OUT_OF_BOUNDS: a guessed coordinate off the screen
  other         every other non-OK code
Generic: it knows codes, not apps.
"""
import collections, re, sys

LINE = re.compile(r"^\s+\d\d-\d\d \d\d:\d\d:\d\d (\S+) (\S+) (.*?) → (\S*)")
GROUPS = {"NOT_FOUND": "not_found", "AMBIGUOUS": "not_found", "STALE_ELEMENT": "not_found",
          "NOT_MET": "missed", "NO_CHANGE": "missed", "OUT_OF_BOUNDS": "out_of_bounds"}


def main():
    groups, codes, total = collections.Counter(), collections.Counter(), 0
    for path in sys.argv[1:]:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                m = LINE.match(line)
                if not m:
                    continue
                total += 1
                code = m.group(4) or "OK"
                if code in ("OK", "", "0"):
                    continue
                codes[code] += 1
                groups[GROUPS.get(code, "other")] += 1
    print(f"{total} commands, {sum(codes.values())} not OK")
    for g, n in groups.most_common():
        print(f"  {g:14s} {n:5d}  ({n / max(total, 1):.1%} of commands)")
    print("codes:", ", ".join(f"{c} {n}" for c, n in codes.most_common()))


if __name__ == "__main__":
    main()
