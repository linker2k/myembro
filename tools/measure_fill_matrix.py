#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Candidate fill observations, NOT an independent gate verdict or a threshold waiver.

Uses existing pyembroidery for decoding and existing numpy/scipy for distances.
Never imports/executes Ink/Stitch or modifies oracle/gate/criteria files.
"""
import argparse
from collections import Counter
import gzip
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n")


def decode(jobs):
    import pyembroidery as pe
    for job in json.loads(Path(jobs).read_text()):
        source, destination = Path(job["source"]), Path(job["snapshot"])
        checksum = digest(source)
        if destination.is_file():
            with gzip.open(destination, "rt") as stream:
                if json.load(stream).get("sha256") == checksum:
                    continue
        pattern = pe.read_pes(str(source))
        commands = [[x / 10, y / 10, int(command) & 255] for x, y, command in pattern.stitches]
        needles = [[x, y] for x, y, command in commands if command == pe.STITCH]
        previous = (0, 0)
        lengths = []
        for x, y, command in commands:
            if command == pe.STITCH:
                lengths.append(math.dist(previous, (x, y)))
            previous = x, y
        value = dict(sha256=checksum, needles_mm=needles, lengths_mm=lengths, commands=commands,
                     thread_rgb=[thread.color & 0xffffff for thread in pattern.threadlist],
                     decoder="pyembroidery (candidate observations; no independent bounded-walk cross-check)")
        with gzip.open(destination, "wt", encoding="utf-8") as stream:
            json.dump(value, stream, separators=(",", ":"))


def hole_segment_length(a, b, radius):
    # Liang-Barsky intersection with an inset axis-aligned hole, in centered mm.
    lower, upper = 0.0, 1.0
    for start, delta in zip(a, (b[0] - a[0], b[1] - a[1])):
        if abs(delta) < 1e-12:
            if not -radius < start < radius:
                return 0.0
        else:
            t0, t1 = sorted(((-radius - start) / delta, (radius - start) / delta))
            lower, upper = max(lower, t0), min(upper, t1)
            if upper <= lower:
                return 0.0
    return (upper - lower) * math.dist(a, b)


def observations(data, case):
    import numpy as np
    from scipy.spatial import cKDTree
    needles = np.array(data["needles_mm"], dtype=float)
    center = (needles.max(axis=0) + needles.min(axis=0)) / 2
    positive = [length for length in data["lengths_mm"] if length > 1e-7]
    exact = Counter(tuple(point) for point in data["needles_mm"])
    cells = Counter((math.floor(x / .5), math.floor(y / .5)) for x, y in data["needles_mm"])
    result = dict(needle_count=len(needles), positive_short_lt03=sum(x < .3 - 1e-8 for x in positive),
                  positive_short_lt027=sum(x < .27 - 1e-8 for x in positive),
                  zero_count=len(data["lengths_mm"]) - len(positive), max_exact_coordinate_visits=max(exact.values()),
                  cell_peak_05mm_single_phase=max(cells.values()))
    if case.get("shape") != "hole":
        return result
    height = case["reference_extent_mm"]
    radius = .2 * height - .05
    normalized = needles - center
    result["needles_inside_hole_inset005mm"] = int(np.all(np.abs(normalized) < radius, axis=1).sum())
    previous = None
    crossings, intrusion, sampled = 0, 0.0, []
    for x, y, command in data["commands"]:
        current = (x - center[0], y - center[1])
        if command == 0 and previous is not None:
            length = hole_segment_length(previous, current, radius)
            if length > .01:
                crossings += 1
                intrusion += length
            # Coverage queries use sampled STITCH SEGMENTS, not merely needle positions.
            count = max(1, math.ceil(math.dist(previous, current) / .2))
            sampled.extend((previous[0] + (current[0] - previous[0]) * i / count,
                            previous[1] + (current[1] - previous[1]) * i / count) for i in range(count + 1))
        previous = current
    result["sewn_segments_entering_hole_inset005mm"] = crossings
    result["sewn_length_inside_hole_inset005mm"] = intrusion
    grid = [(x, y) for x in np.linspace(-.48 * height, .48 * height, 61)
            for y in np.linspace(-.48 * height, .48 * height, 61)
            if not (abs(x) <= .2 * height and abs(y) <= .2 * height)]
    spacing = case["parameters"]["row_spacing_mm"]
    distances = cKDTree(np.array(sampled)).query(np.array(grid))[0]
    missing = distances > spacing / 2 + .15
    result["material_grid_points"] = len(grid)
    result["material_grid_uncovered"] = int(missing.sum())
    result["material_grid_coverage_ratio"] = float(1 - missing.mean())
    result["coverage_note"] = "61x61 centered ring sample, segment samples <=0.2mm, radius=row_spacing/2+0.15mm; diagnostic only"
    return result


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--decode-jobs":
        decode(sys.argv[2])
        return
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-json", required=True)
    parser.add_argument("--pes-python", required=True, help="Existing interpreter with pyembroidery; no installs")
    parser.add_argument("--oracle-root", default="/tmp/myembro-inkstitch-oracle")
    parser.add_argument("--matrix", default="/tmp/myembro-quality-gate/matrix-inkstitch.json")
    parser.add_argument("--output-dir")
    args = parser.parse_args()
    import numpy as np
    from scipy.spatial import cKDTree
    from scipy.stats import wasserstein_distance
    manifest = json.loads(Path(args.results_json).read_text())
    matrix_path = Path(args.matrix)
    assert digest(matrix_path) == manifest["matrix_sha256"], "MATRIX_IDENTITY_MISMATCH"
    matrix = {case["id"]: case for case in json.loads(matrix_path.read_text())["cases"]}
    root = Path(args.oracle_root)
    freeze = json.loads((root / "oracle-freeze.json").read_text())
    assert digest(root / "equivalence-criteria.json") == freeze["criteria_sha256"], "CRITERIA_CHANGED"
    output = Path(args.output_dir or (Path(args.results_json).parent / "observations")).resolve()
    output.mkdir(parents=True, exist_ok=True)
    snapshots = output / "coordinates"
    snapshots.mkdir(exist_ok=True)
    jobs, pairs, unavailable = [], [], []
    for case in manifest["cases"]:
        ident = case["id"]
        official_path = root / "out/fill_stitch" / (ident + ".oracle.json")
        official = json.loads(official_path.read_text()) if official_path.is_file() else {}
        if official.get("status") != "GENERATED" or case.get("status") != "GENERATED":
            unavailable.append(dict(id=ident, port_status=case.get("status"), oracle_status=official.get("status")))
            continue
        paths = []
        for entry in (official, case):
            source = Path(entry["path"])
            checksum = digest(source)
            assert checksum == entry["sha256"], "PES_IDENTITY_MISMATCH"
            target = snapshots / (checksum + ".json.gz")
            jobs.append(dict(source=str(source), snapshot=str(target)))
            paths.append(target)
        pairs.append((ident, official["sha256"], case["sha256"], paths))
    jobfile = output / "decode-jobs.json"
    dump(jobfile, jobs)
    subprocess.run([args.pes_python, str(Path(__file__).resolve()), "--decode-jobs", str(jobfile)], check=True)
    rows = []
    for ident, oracle_hash, port_hash, paths in pairs:
        data = []
        for path in paths:
            with gzip.open(path, "rt") as stream:
                data.append(json.load(stream))
        a, b = data
        an, bn = (np.array(item["needles_mm"], dtype=float) for item in data)
        an -= (an.max(axis=0) + an.min(axis=0)) / 2
        bn -= (bn.max(axis=0) + bn.min(axis=0)) / 2
        al, bl = (np.array(item["lengths_mm"]) for item in data)
        fractions = np.linspace(0, 1, max(len(an), len(bn)))
        paths_by_index = [np.column_stack([np.interp(fractions, np.linspace(0, 1, len(points)), points[:, i])
                                          for i in (0, 1)]) for points in (an, bn)]
        ordered = np.linalg.norm(paths_by_index[0] - paths_by_index[1], axis=1)
        row = dict(id=ident, oracle_sha256=oracle_hash, port_sha256=port_hash,
                   stitch_count_relative_difference=abs(len(an) - len(bn)) / len(an),
                   symmetric_needle_coordinate_hausdorff_mm=max(float(cKDTree(an).query(bn)[0].max()), float(cKDTree(bn).query(an)[0].max())),
                   bbox_dimension_delta_mm=np.abs(np.ptp(an, axis=0) - np.ptp(bn, axis=0)).tolist(),
                   length_wasserstein_mm=float(wasserstein_distance(al, bl)),
                   length_quantiles_oracle_port_mm={str(q): [float(np.quantile(al, q)), float(np.quantile(bl, q))] for q in (.1, .5, .9, .99)},
                   ordered_index_p95_mm=float(np.quantile(ordered, .95)), ordered_index_max_mm=float(ordered.max()),
                   thread_rgb_equal=a["thread_rgb"] == b["thread_rgb"],
                   oracle=observations(a, matrix[ident]), port=observations(b, matrix[ident]))
        rows.append(row)
    report = dict(kind="CANDIDATE_OBSERVATIONS_NOT_GATE_VERDICT", commit=manifest["commit"],
                  matrix_sha256=manifest["matrix_sha256"], criteria_sha256=freeze["criteria_sha256"],
                  limitation="pyembroidery-only observation; official bounded decoder/comparator and Codex review remain required",
                  compared=len(rows), unavailable=unavailable, cases=rows)
    dump(output / "measurements.json", report)
    print("Measured", len(rows), "pairs; unavailable", len(unavailable), ";", output / "measurements.json")


if __name__ == "__main__":
    main()
