#!/usr/bin/env python3
"""Run the host suite with stdlib trace and enforce weighted source coverage."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path


SUMMARY_LINE = re.compile(
    r"^\s*(?P<lines>\d+)\s+(?P<percent>\d+)%\s+"
    r"(?P<module>kittyecho_asr_bench(?:\.[A-Za-z0-9_]+)+)\s+",
    re.MULTILINE,
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--threshold", type=float, default=90.0)
    arguments = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    environment = os.environ.copy()
    existing_path = environment.get("PYTHONPATH")
    environment["PYTHONPATH"] = (
        str(root / "src")
        if not existing_path
        else str(root / "src") + os.pathsep + existing_path
    )
    ignore_dirs = os.pathsep.join(
        sorted({sys.base_prefix, sys.prefix, "/Library", "/usr"})
    )
    with tempfile.TemporaryDirectory(prefix="kittyecho-asr-coverage-") as cover_dir:
        command = [
            sys.executable,
            "-m",
            "trace",
            "--count",
            "--missing",
            "--summary",
            "--coverdir",
            cover_dir,
            "--ignore-dir",
            ignore_dirs,
            "--module",
            "unittest",
            "discover",
            "-s",
            "tests",
        ]
        completed = subprocess.run(
            command,
            cwd=root,
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )
    if completed.stdout:
        print(completed.stdout, end="")
    if completed.stderr:
        print(completed.stderr, file=sys.stderr, end="")
    if completed.returncode != 0:
        return completed.returncode

    modules = [
        (int(match["lines"]), int(match["percent"]), match["module"])
        for match in SUMMARY_LINE.finditer(completed.stdout)
    ]
    if not modules:
        print("error: no KittyEcho source coverage rows were produced", file=sys.stderr)
        return 2
    total_lines = sum(lines for lines, _, _ in modules)
    weighted_covered = sum(lines * percent / 100 for lines, percent, _ in modules)
    coverage = weighted_covered / total_lines * 100
    print(
        f"Weighted KittyEcho source coverage: {coverage:.1f}% "
        f"({len(modules)} modules, threshold {arguments.threshold:.1f}%)"
    )
    return 0 if coverage >= arguments.threshold else 1


if __name__ == "__main__":
    raise SystemExit(main())
