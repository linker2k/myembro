#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate engine test fixtures with pyembroidery (the reference implementation).

For each sample design this writes PES v1, PES v6, PEC and DST files, then reads every
file back with pyembroidery and stores the decoded stitches as JSON. The Kotlin reader
tests must produce exactly the same stitches.

    pip install pyembroidery==1.5.1
    python3 tools/make_fixtures.py engine/src/test/resources/fixtures
"""
import json
import math
import os
import sys

import pyembroidery as pe

CMD_NAMES = {
    pe.STITCH: "STITCH",
    pe.JUMP: "JUMP",
    pe.TRIM: "TRIM",
    pe.STOP: "STOP",
    pe.END: "END",
    pe.COLOR_CHANGE: "COLOR_CHANGE",
}


def satin_ring(p, cx, cy, r_in, r_out, steps):
    for i in range(steps + 1):
        a = 2 * math.pi * i / steps
        r = r_in if i % 2 == 0 else r_out
        p.add_stitch_absolute(pe.STITCH, cx + r * math.cos(a), cy + r * math.sin(a))


def fill_rect(p, x0, y0, w, h, spacing):
    y = y0
    left = True
    while y <= y0 + h:
        xs = (x0, x0 + w) if left else (x0 + w, x0)
        for x in range(int(xs[0]), int(xs[1]), 30 if left else -30):
            p.add_stitch_absolute(pe.STITCH, x, y)
        p.add_stitch_absolute(pe.STITCH, xs[1], y)
        left = not left
        y += spacing


def flower():
    p = pe.EmbPattern()
    p.add_thread({"color": 0x2E7D32, "description": "Leaf", "catalog_number": "507"})
    p.add_thread({"color": 0xE91E63, "description": "Petal", "catalog_number": "086"})
    p.add_thread({"color": 0xFFC107, "description": "Center", "catalog_number": "214"})
    p.metadata("name", "flower")
    fill_rect(p, -40, 150, 80, 300, 4)  # stem
    p.trim()
    p.color_change()
    for k in range(6):  # petals, jump between each
        a = 2 * math.pi * k / 6
        cx, cy = 180 * math.cos(a), 180 * math.sin(a)
        p.add_stitch_absolute(pe.JUMP, cx, cy)
        satin_ring(p, cx, cy, 10, 110, 120)
        p.trim()
    p.color_change()
    p.add_stitch_absolute(pe.JUMP, 0, 0)
    satin_ring(p, 0, 0, 5, 90, 200)
    p.end()
    return p


def long_moves():
    """Exercises 12-bit PEC deltas, long jumps that need splitting, and a single color."""
    p = pe.EmbPattern()
    p.add_thread({"color": 0x1565C0, "description": "Blue"})
    p.metadata("name", "long_moves")
    for i in range(20):
        p.add_stitch_absolute(pe.STITCH, i * 37, (i % 3) * 25)
    p.add_stitch_absolute(pe.JUMP, 900, 600)
    for i in range(20):
        p.add_stitch_absolute(pe.STITCH, 900 - i * 61, 600 - (i % 2) * 140)
    p.end()
    return p


def stops():
    """A STOP is written to PES/PEC as a change to the same color and must read back as STOP."""
    p = pe.EmbPattern()
    p.add_thread({"color": 0xD32F2F, "description": "Red"})
    p.add_thread({"color": 0x1976D2, "description": "Blue"})
    p.metadata("name", "stops")
    for i in range(10):
        p.add_stitch_absolute(pe.STITCH, i * 20, 0)
    p.stop()
    for i in range(10):
        p.add_stitch_absolute(pe.STITCH, 200 - i * 20, 40)
    p.color_change()
    for i in range(10):
        p.add_stitch_absolute(pe.STITCH, i * 20, 80)
    p.end()
    return p


def dump(pattern):
    return {
        "stitches": [[int(s[0]), int(s[1]), CMD_NAMES.get(s[2] & pe.COMMAND_MASK, str(s[2]))]
                     for s in pattern.stitches],
        "threads": [t.color & 0xFFFFFF for t in pattern.threadlist if t is not None],
    }


ORACLE_VERSION = "1.5.1"


def main(out_dir):
    if pe_version() != ORACLE_VERSION:
        sys.exit(f"pyembroidery {ORACLE_VERSION} required (found {pe_version()}); fixtures must not drift")
    os.makedirs(out_dir, exist_ok=True)
    designs = {"flower": flower(), "long_moves": long_moves(), "stops": stops()}
    with open(os.path.join(out_dir, "ORACLE.txt"), "w") as fp:
        fp.write(f"pyembroidery {ORACLE_VERSION}\n")
    for name, pattern in designs.items():
        for ext, settings in (("v1.pes", {"version": 1}), ("v6.pes", {"version": 6}),
                              ("pec", None), ("dst", None)):
            path = os.path.join(out_dir, f"{name}.{ext}")
            pe.write(pattern, path, settings)
            reread = pe.read(path)
            with open(path + ".json", "w") as fp:
                json.dump(dump(reread), fp)
            print(f"{path}: {len(reread.stitches)} stitches, {len(reread.threadlist)} threads")


def pe_version():
    from importlib.metadata import version
    return version("pyembroidery")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "engine/src/test/resources/fixtures")
