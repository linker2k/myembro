#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Submit generated worker SVG fixtures to the independent, unmodified PES gate.

Generate fixtures with :engine:test first. No Ink/Stitch modules are imported/run.
The synthetic font axis identifies fixtures, not product font coverage. The gate
currently treats every matrix entry as text; general PASS never overrides BLOCK.
"""

import argparse
import json
from pathlib import Path
import subprocess
import sys


FAMILIES = {
    "locks": ("inkstitch-locks", (
        "default", "half_stitch", "arrow", "back_forth", "bowtie", "cross",
        "star", "simple", "triangle", "zigzag", "custom")),
    "stroke": ("inkstitch-stroke", ("running", "manual", "zigzag", "bean")),
    "ripple": ("inkstitch-ripple", ("ripple-square", "ripple-clipped-hole")),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--family", choices=["all", *FAMILIES], default="all")
    parser.add_argument("--gate-dir", type=Path, default=Path("/tmp/myembro-quality-gate"))
    parser.add_argument("--report-dir", type=Path, default=Path("/tmp/myembro-worker-inkstitch-quality"))
    parser.add_argument("--general", action="store_true", help="Also record general-design checks; release exit status stays authoritative")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    families = FAMILIES if args.family == "all" else {args.family: FAMILIES[args.family]}
    cases = []
    for family, (directory, names) in families.items():
        for name in names:
            path = root / "engine" / "build" / directory / (name + ".pes")
            if not path.is_file():
                parser.error(f"Missing {path}; run JAVA_HOME=<jdk17> ./gradlew :engine:test --rerun-tasks")
            cases.append({
                "font": f"{family}-svg-{name}", "height_mm": 10, "style": "running",
                "path": str(path), "source_method": family, "fixture_id": name,
            })
    args.report_dir.mkdir(parents=True, exist_ok=True)
    matrix = args.report_dir / (args.family + "-matrix.json")
    matrix.write_text(json.dumps({
        "axes": {"fonts": [case["font"] for case in cases], "heights_mm": [10], "styles": ["running"]},
        "cases": cases,
    }, indent=2) + "\n")
    report = args.report_dir / (args.family + "-release.json")
    release = subprocess.run([
        str(args.gate_dir / "check-release"), "--matrix", str(matrix), "--json", str(report),
    ], capture_output=True, text=True)
    (args.report_dir / (args.family + "-release.log")).write_text(release.stdout + release.stderr)
    if report.is_file():
        data = json.loads(report.read_text())
        print(f"release: {data.get('verdict')} ({data.get('cases')} cases, {data.get('blocked')} blocked)")
        for result in data.get("results", []):
            print(f"  {result['font']}: {result['verdict']}" + (" — " + "; ".join(result.get("reasons", [])) if result.get("reasons") else ""))
    else:
        print(release.stdout + release.stderr, file=sys.stderr)
    general_failure = False
    if args.general:
        for case in cases:
            output = args.report_dir / (case["font"] + "-general.json")
            checked = subprocess.run([
                str(args.gate_dir / "quality-gate"), "check", case["path"], "--json", str(output),
            ], capture_output=True, text=True)
            general_failure |= checked.returncode != 0
            verdict = json.loads(output.read_text()).get("verdict") if output.is_file() else "TOOL_ERROR"
            print(f"general: {case['font']} {verdict}")
    print(f"Reports: {args.report_dir}")
    return release.returncode if release.returncode else (1 if general_failure else 0)


if __name__ == "__main__":
    sys.exit(main())
