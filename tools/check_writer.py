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
import io
import struct
from importlib.metadata import version
from pathlib import Path

import pyembroidery as pe
from pyembroidery.EmbThread import find_nearest_color_index
from pyembroidery.EmbThreadPec import get_thread_set
from pyembroidery.PecGraphics import draw_scaled, get_blank
from pyembroidery import PesWriter as oracle_pes

COMMANDS = {getattr(pe, name): name for name in (
    "STITCH", "JUMP", "TRIM", "STOP", "COLOR_CHANGE", "END"
)}
DEFAULT_COLORS = [0x0A55A3, 0xED171F, 0x70BC1F, 0xFEBA35, 0x913697, 0, 0x2584BB, 0xB27624]


def normalize(source, preserve_threads=False, with_thread_details=False):
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

    def thread_key(i):
        details = source.get("threadDetails", [])
        if i < len(details):
            t = details[i]
            return (color(i), t.get("description"), t.get("catalogNumber"), t.get("brand"), t.get("chart"))
        return (color(i),)

    result, used, x, y = [], [], 0, 0
    for i, segment in enumerate(segments):
        if any(s[2] == "STITCH" for s in segment):
            t = segment_thread[i]
            if not used:
                used.append(t)
            elif thread_key(used[-1]) == thread_key(t):
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
    keys = [thread_key(i) for i in used]
    # Reserve exact colors per thread identity, including same-RGB catalog differences.
    chart = get_thread_set()
    allocated = {}
    for key in dict.fromkeys(keys):
        for i, thread in enumerate(chart):
            if thread is not None and thread.color & 0xFFFFFF == key[0]:
                allocated[key] = i
                chart[i] = None
                break
    for key in dict.fromkeys(keys):
        if key not in allocated:
            index = find_nearest_color_index(key[0], chart)
            assert index is not None, "palette exhausted"
            allocated[key] = index
            chart[index] = None
    palette = get_thread_set()
    result = {"stitches": paired, "threads": colors if preserve_threads else
              [palette[allocated[key]].color & 0xFFFFFF for key in keys]}
    if with_thread_details:
        default_names = ["Blue", "Red", "Lime Green", "Orange", "Magenta", "Black", "Sky Blue", "Light Brown"]
        result["threadDetails"] = [source.get("threadDetails", [])[i]
            if i < len(source.get("threadDetails", [])) else
            {"color": color(i), "description": default_names[i % 8], "catalogNumber": None, "brand": None, "chart": None}
            for i in used]
    return result


def verify_v6_objects(data, source, pec_offset):
    """Parse the header independently and compare CEmbOne/CSewSeg to upstream routines."""
    pos = 12
    def u16():
        nonlocal pos
        value = int.from_bytes(data[pos:pos+2], "little"); pos += 2
        return value
    def string8():
        nonlocal pos
        size = data[pos]; pos += 1
        value = data[pos:pos+size].decode("utf-8"); pos += size
        return value or None
    def bounded(value):
        return value.encode("utf-8")[:255].decode("utf-8", errors="ignore") if value else None
    assert u16() == 1 and data[pos:pos+2] == b"02"
    pos += 2
    for key in ("name", "category", "author", "keywords", "comments"):
        assert string8() == bounded(source.get("metadata", {}).get(key)), key
    page = list(struct.unpack_from("<18H", data, pos)); pos += 36
    assert page == [0, 0, 100, 100, 0, 200, 200, 100, 100, 100, 7, 19, 1, 1, 0, 100, 1, 0]
    assert string8() is None
    assert struct.unpack_from("<6f", data, pos) == (1, 0, 0, 1, 0, 0); pos += 24
    assert [u16(), u16(), u16()] == [0, 0, 0]
    prepared = normalize(source, preserve_threads=True, with_thread_details=True)
    chart_details = [prepared["threadDetails"][0]]
    t = 0
    for _, _, command in prepared["stitches"]:
        if command == "COLOR_CHANGE":
            t += 1
            chart_details.append(prepared["threadDetails"][t])
        elif command == "STOP":
            chart_details.append(prepared["threadDetails"][t])
    assert u16() == len(chart_details)
    chart = []
    for expected in chart_details:
        catalog = string8()
        color = int.from_bytes(data[pos:pos+3], "big"); pos += 3
        assert data[pos:pos+5] == b"\x00\x0a\x00\x00\x00"; pos += 5
        description, brand, name = string8(), string8(), string8()
        assert color == expected["color"]
        assert (catalog, description, brand, name) == tuple(bounded(expected.get(k)) for k in ("catalogNumber", "description", "brand", "chart"))
        thread = pe.EmbThread()
        thread.color, thread.catalog_number, thread.description, thread.brand, thread.chart = color, catalog, description, brand, name
        chart.append(thread)
    has_objects = any(s[2] == "STITCH" for s in prepared["stitches"])
    assert u16() == int(has_objects)
    reference = io.BytesIO()
    if has_objects:
        reference.write(b"\xff\xff\x00\x00")
        editor = pe.EmbPattern()
        editor.threadlist = chart
        editor.stitches = [[x, y, pe.COLOR_CHANGE if command == "STOP" else getattr(pe, command)]
                           for x, y, command in prepared["stitches"]]
        min_x, min_y, max_x, max_y = editor.bounds()
        cx, cy = (min_x+max_x)/2, (min_y+max_y)/2
        log = oracle_pes.write_pes_blocks(reference, editor, chart, min_x-cx, min_y-cy, max_x-cx, max_y-cy, cx, cy)
        reference.write(bytes(8))
        for i in range(len(log)):
            reference.write(struct.pack("<II", i, 0))
    else:
        reference.write(bytes(4))
    assert data[pos:pec_offset] == reference.getvalue(), "CEmbOne/CSewSeg differ from pyembroidery 1.5.1"
    return chart


def verify_layout(path, expected, source):
    data = path.read_bytes()
    if data[:8] == b"#PEC0001":
        base = 8
    else:
        assert data[:8] in (b"#PES0001", b"#PES0060"), f"{path.name}: unsupported PES version"
        base = int.from_bytes(data[8:12], "little")
        if data[:8] == b"#PES0001":
            assert base == 22 and data[12:22] == bytes(10), "invalid minimal PES header"
        else:
            chart = verify_v6_objects(data, source, base)
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
    graphics_end = end + (count + 1) * 228
    if data[:8] == b"#PES0060":
        color_info = data[base+48:base+49+count]
        addendum = color_info + b" " * max(0, 128-len(color_info)) + bytes(0x90*len(chart))
        addendum += b"".join((t.color & 0xFFFFFF).to_bytes(3, "little") for t in chart) + bytes(2)
        assert data[graphics_end:] == addendum, "v6 addendum RGB/palette/padding"
    else:
        assert len(data) == graphics_end, "24-bit block length / thumbnail count"
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
    binary = path.with_suffix("")
    v6 = binary.read_bytes()[:8] == b"#PES0060"
    expected = normalize(manifest["source"], preserve_threads=v6)
    assert manifest["expected"] == expected, f"{path.name}: Kotlin expectation disagrees with independent normalization"
    verify_layout(binary, normalize(manifest["source"]), manifest["source"])
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
