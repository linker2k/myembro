#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read Kotlin writer outputs with pyembroidery 1.5.1 and independently check semantics.

Run JAVA_HOME=/path/to/jdk17 ./gradlew :engine:test first, then:
    /path/to/venv/bin/python tools/check_writer.py

Uses the original inputs and expectations exported by WriterParityTest, not just Kotlin
reader dumps. No network access, fixture regeneration or source modifications are needed.
"""

import argparse
import json
from importlib.metadata import version
from pathlib import Path

import pyembroidery as pe
from pyembroidery.EmbThread import find_nearest_color_index
from pyembroidery.EmbThreadPec import get_thread_set
from pyembroidery.PecGraphics import draw_scaled, get_blank

COMMANDS = {getattr(pe, name): name for name in (
    "STITCH", "JUMP", "TRIM", "STOP", "COLOR_CHANGE", "END"
)}
DEFAULT_COLORS = [0x0A55A3, 0xED171F, 0x70BC1F, 0xFEBA35, 0x913697, 0, 0x2584BB, 0xB27624]


def normalize(source):
    """Independent specification of integer splitting, controls and trim-jump pairing."""
    result = []
    x = y = 0
    for tx, ty, command in source["stitches"]:
        if command in ("STITCH", "JUMP") or (tx, ty) != (x, y):
            motion = command if command in ("STITCH", "JUMP") else "JUMP"
            dx, dy = tx - x, ty - y
            parts = max(1, (max(abs(dx), abs(dy)) + 2046) // 2047)
            # Only the final part keeps the command: a long move punches no holes on the way.
            result.extend([x + round(dx * i / parts), y + round(dy * i / parts),
                           motion if i == parts else "JUMP"]
                          for i in range(1, parts + 1))
            x, y = tx, ty
        if command not in ("STITCH", "JUMP"):
            result.append([x, y, command])
        if command == "END":
            break
    if not result or result[-1][2] != "END":
        result.append([x, y, "END"])
    # Blocks that sew nothing are dropped with their boundary; a change back to the same
    # thread color becomes a STOP. Motion inside dropped blocks is kept.
    segments, boundaries = [[]], []
    for stitch in result:
        if stitch[2] in ("COLOR_CHANGE", "STOP"):
            boundaries.append(stitch[2])
            segments.append([])
        else:
            segments[-1].append(stitch)
    segment_thread, thread = [], 0
    for i in range(len(segments)):
        segment_thread.append(thread)
        if i < len(boundaries) and boundaries[i] == "COLOR_CHANGE":
            thread += 1

    def color(i):
        return source["threads"][i] if i < len(source["threads"]) else DEFAULT_COLORS[i % 8]

    result, used, x, y = [], [], 0, 0
    for i, segment in enumerate(segments):
        if any(s[2] == "STITCH" for s in segment):
            t = segment_thread[i]
            if not used:
                used.append(t)
            elif color(used[-1]) == color(t):
                result.append([x, y, "STOP"])
            else:
                result.append([x, y, "COLOR_CHANGE"])
                used.append(t)
        for s in segment:
            result.append(s)
            x, y = s[0], s[1]
    used = used or [0]

    paired = []
    for i, stitch in enumerate(result):
        paired.append(stitch)
        if stitch[2] == "TRIM" and result[i + 1][2] != "JUMP":
            paired.append([*stitch[:2], "JUMP"])

    colors = [color(i) for i in used]
    # Deterministic build_unique_palette: reserve exact colors, then use upstream's
    # red-mean metric and tie breaking on the remaining entries.
    chart = get_thread_set()
    allocated = {}
    for color in dict.fromkeys(colors):
        for i, thread in enumerate(chart):
            if thread is not None and thread.color & 0xFFFFFF == color:
                allocated[color] = i
                chart[i] = None
                break
    for color in dict.fromkeys(colors):
        if color not in allocated:
            index = find_nearest_color_index(color, chart)
            assert index is not None, "palette exhausted"
            allocated[color] = index
            chart[index] = None
    palette = get_thread_set()
    return {"stitches": paired, "threads": [palette[allocated[c]].color & 0xFFFFFF for c in colors]}


def verify_layout(path, expected):
    data = path.read_bytes()
    if data[:8] == b"#PEC0001":
        base = 8
    else:
        assert data[:8] == b"#PES0001", f"{path.name}: not PES v1"
        base = int.from_bytes(data[8:12], "little")
        assert base == 22 and data[12:22] == bytes(10), "invalid minimal PES header"
    assert data[base:base + 3] == b"LA:"
    assert data[base + 34:base + 36] == bytes((6, 38))
    count = data[base + 48] + 1
    palette = get_thread_set()
    blocks = [[]]
    thread_index = 0
    expected_palette = [expected["threads"][0]]
    for x, y, command in expected["stitches"]:
        if command == "STITCH":
            blocks[-1].append([x, y, pe.STITCH])
        elif command in ("COLOR_CHANGE", "STOP"):
            if command == "COLOR_CHANGE":
                thread_index += 1
            expected_palette.append(expected["threads"][thread_index])
            blocks.append([])
    indices = data[base + 49:base + 49 + count]
    assert [palette[i].color & 0xFFFFFF for i in indices] == expected_palette, "color/STOP table"
    assert count == len(blocks)
    start = base + 512
    length = int.from_bytes(data[start + 2:start + 5], "little")
    end = start + length
    assert data[start:start + 2] == bytes(2)
    assert data[start + 5:start + 8] == b"\x31\xff\xf0"
    assert data[end - 2:end] == b"\xff\x00", "END must precede graphics"
    assert len(data) == end + (count + 1) * 228, "24-bit block length / thumbnail count"
    stitches = expected["stitches"]
    bounds = (min(s[0] for s in stitches), min(s[1] for s in stitches),
              max(s[0] for s in stitches), max(s[1] for s in stitches))
    assert int.from_bytes(data[start + 8:start + 10], "little") == bounds[2] - bounds[0]
    assert int.from_bytes(data[start + 10:start + 12], "little") == bounds[3] - bounds[1]
    for i, points in enumerate([[s for block in blocks for s in block]] + blocks):
        graphic = get_blank()
        draw_scaled(bounds, points, graphic, 6, 4 if i == 0 else 5)
        assert data[end + i * 228:end + (i + 1) * 228] == bytes(graphic), f"thumbnail {i}"


def check(path):
    manifest = json.loads(path.read_text())
    expected = normalize(manifest["source"])
    assert manifest["expected"] == expected, f"{path.name}: Kotlin expectation disagrees with independent normalization"
    binary = path.with_suffix("")
    verify_layout(binary, expected)
    decoded = pe.read(str(binary))
    actual = {
        "stitches": [[int(x), int(y), COMMANDS.get(command & pe.COMMAND_MASK, str(command))]
                     for x, y, command in decoded.stitches],
        "threads": [t.color & 0xFFFFFF for t in decoded.threadlist],
    }
    assert actual == expected, f"{binary.name}: pyembroidery differs\nexpected={expected}\nactual={actual}"
    print(f"OK {binary.name}: {len(actual['stitches'])} commands, {len(actual['threads'])} colors; thumbnails exact")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?", type=Path,
                        default=Path(__file__).resolve().parents[1] / "engine/build/writer-check")
    args = parser.parse_args()
    if version("pyembroidery") != "1.5.1":
        parser.error("oracle must be pyembroidery==1.5.1")
    files = sorted(args.directory.glob("*.json"))
    if not files:
        parser.error("no Kotlin outputs found; run ./gradlew :engine:test --rerun-tasks first")
    for path in files:
        check(path)
    print(f"PASS: {len(files)} Kotlin files checked with pyembroidery 1.5.1")


if __name__ == "__main__":
    main()
