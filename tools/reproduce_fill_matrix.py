#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Replay fixed official fill SVG inputs using only our JVM preview, never Ink/Stitch.

PES and provenance go to /tmp/myembro-port-out/model by default. Unmerged shared
planner source is explicitly supplied and frozen byte-for-byte before compilation.
No libraries are installed; only the existing Gradle/JDK toolchain is used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = Path("io/github/linker2k/myembro/engine/inkstitch")
SHARED = ["stitch_plan/LockStitches.kt", "stitch_plan/StitchPlanBuilder.kt", "stitch_plan/SewingOptions.kt", "stitch_plan/GeneratedPatternProcessor.kt", "svg/PathEndPoints.kt"]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", default="/tmp/myembro-quality-gate/matrix-inkstitch.json")
    parser.add_argument("--owner", default="embro-model", choices=["embro-model"])
    parser.add_argument("--oracle-input-dir", default="/tmp/myembro-inkstitch-oracle/inputs")
    parser.add_argument("--output-dir", default="/tmp/myembro-port-out/model")
    parser.add_argument("--results-json")
    parser.add_argument("--commit", help="Full source SHA; required in an archive without .git")
    parser.add_argument("--planner-source-root", help="Explicit shared planner Kotlin source root, until S1 lands")
    parser.add_argument("--case-filter", default=".*", help="Debug subset; default replays ALL assigned fill cases")
    args = parser.parse_args()
    if (ROOT / ".git").exists():
        actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        if args.commit and args.commit != actual:
            parser.error("--commit does not identify this checkout")
        dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()
        if dirty:
            parser.error("Use a clean fixed-commit checkout for oracle submissions")
        commit = actual
    elif args.commit:
        commit = args.commit
    else:
        parser.error("An archive without .git requires --commit")
    output = Path(args.output_dir).resolve()
    output.mkdir(parents=True, exist_ok=True)
    matrix = Path(args.matrix).resolve()
    if not matrix.is_file():
        parser.error("Frozen matrix does not exist")
    source_root = ROOT / "engine/src/main/kotlin"
    external = Path(args.planner_source_root).resolve() if args.planner_source_root else None
    dependencies = []
    snapshot = output / "dependencies/source"
    for relative in SHARED:
        source = source_root / PACKAGE / relative
        if source.is_file():
            continue
        if external is None:
            parser.error("S1 shared planner is not merged: provide --planner-source-root explicitly")
        source = external / PACKAGE / relative
        if not source.is_file():
            parser.error(f"Missing shared planner source: {source}")
        target = snapshot / PACKAGE / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        dependencies.append({"relative_path": str(PACKAGE / relative), "input": str(source),
                             "snapshot": str(target), "sha256": sha256(target)})
    provenance = output / "dependencies.json"
    provenance.write_text(json.dumps({"note": "Explicit unmerged shared planner snapshot; not part of model commit",
                                      "files": dependencies}, indent=2) + "\n")
    results = Path(args.results_json).resolve() if args.results_json else output / "results.json"
    arguments = [str(ROOT / "gradlew"), "-I", str(ROOT / "tools/inkstitch-fill-preview.gradle"),
                 "--console=plain", ":engine:exportFillOracleMatrix"]
    if dependencies:
        arguments.append("-PfillPlannerSourceRoot=" + str(snapshot))
    values = {"matrix": str(matrix), "owner": args.owner,
              "oracle-input-dir": str(Path(args.oracle_input_dir).resolve()), "output-dir": str(output),
              "results-json": str(results), "commit": commit, "case-filter": args.case_filter,
              "dependency-manifest": str(provenance)}
    arguments.extend(f"-PfillMatrix.{key}={value}" for key, value in values.items())
    environment = dict(os.environ)
    if "JAVA_HOME" not in environment:
        parser.error("Set JAVA_HOME to an existing JDK 17 installation")
    print("matrix:", str(matrix), "sha256:", sha256(matrix), flush=True)
    print("commit:", commit, "PES/results:", str(output), flush=True)
    subprocess.run(arguments, cwd=ROOT, env=environment, check=True)


if __name__ == "__main__":
    main()
