#!/usr/bin/env python3
"""CI gate: verify aoci.code.txt covers 100% of app/src/main/java/**/*.kt with zero orphan entries."""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

SECTION_RE = re.compile(r"^===/<repository>/(.*)===$")
ENTRY_RE = re.compile(r"^([^\[\r\n]+)\[[A-Za-z0-9.]+\]:\s")


def parse_aoci_code_index(index_path: Path) -> tuple[list[str], list[str]]:
    """Return (ordered_paths, duplicate_paths) parsed from an AOCI code volume."""
    current_dir: str | None = None
    paths: list[str] = []
    seen: set[str] = set()
    duplicates: list[str] = []

    for lineno, raw in enumerate(index_path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        sec = SECTION_RE.match(line)
        if sec:
            rel_dir = sec.group(1).strip("/")
            current_dir = rel_dir
            continue
        ent = ENTRY_RE.match(line)
        if ent:
            if current_dir is None:
                raise ValueError(f"{index_path}:{lineno}: entry outside section header: {line[:80]}")
            name = ent.group(1).strip()
            rel = f"{current_dir}/{name}" if current_dir else name
            if rel in seen:
                duplicates.append(rel)
            else:
                seen.add(rel)
                paths.append(rel)

    return paths, duplicates


def check_coverage(repo_root: Path, index_rel: str = "aoci.code.txt") -> int:
    index_path = repo_root / index_rel
    if not index_path.is_file():
        print(f"::error::AOCI code index not found: {index_path}", file=sys.stderr)
        return 1

    indexed_list, duplicates = parse_aoci_code_index(index_path)
    indexed = set(indexed_list)

    main_java = repo_root / "app" / "src" / "main" / "java"
    if not main_java.is_dir():
        print(f"::error::Expected Kotlin source directory not found: {main_java}", file=sys.stderr)
        return 1

    main_kt = sorted(p.relative_to(repo_root).as_posix() for p in main_java.rglob("*.kt"))
    missing = [p for p in main_kt if p not in indexed]
    orphans = sorted(p for p in indexed if not (repo_root / p).is_file())

    failed = False
    if duplicates:
        failed = True
        print(f"::error::Duplicate entries in {index_rel} ({len(duplicates)}):", file=sys.stderr)
        for p in sorted(set(duplicates)):
            print(f"  DUPLICATE: {p}", file=sys.stderr)

    if missing:
        failed = True
        print(
            f"::error::{index_rel} is missing {len(missing)} main Kotlin source(s). "
            "Run aoci_maintain / aoci_update_entry to index them:",
            file=sys.stderr,
        )
        for p in missing:
            print(f"  MISSING: {p}", file=sys.stderr)

    if orphans:
        failed = True
        print(
            f"::error::{index_rel} contains {len(orphans)} orphan entr(y/ies) pointing to non-existent files:",
            file=sys.stderr,
        )
        for p in orphans:
            print(f"  ORPHAN: {p}", file=sys.stderr)

    if failed:
        return 1

    print(
        f"OK: {index_rel} covers all {len(main_kt)} main Kotlin sources "
        f"({len(indexed)} total indexed entries, 0 orphans, 0 duplicates)."
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Check AOCI code index coverage and orphan entries.")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="Repository root directory (defaults to two levels above this script).",
    )
    parser.add_argument(
        "--index",
        default="aoci.code.txt",
        help="Relative path to AOCI code volume (default: aoci.code.txt).",
    )
    args = parser.parse_args()
    return check_coverage(args.root, args.index)


if __name__ == "__main__":
    raise SystemExit(main())
