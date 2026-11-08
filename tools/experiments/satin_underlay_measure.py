#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Summarize the default-satin-underlay before/after run (SatinUnderlayMeasure).

usage: python3 tools/experiments/satin_underlay_measure.py --gate <quality_gate.py> \
    [--measure /tmp/myembro-satin-underlay/measure] [--archive docs/experiments/satin-underlay] [--sheet id,...]
Before = main's own output (bundled sample PES, Android-captured lettering PES); after = this branch.
Writes results.json (all cases) and a comparison sheet (before/after columns, 1/4 scale) to --archive.
"""
import argparse, json, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--gate", type=Path, required=True)
    ap.add_argument("--measure", type=Path, default=Path("/tmp/myembro-satin-underlay/measure"))
    ap.add_argument("--archive", type=Path, default=Path("docs/experiments/satin-underlay"))
    ap.add_argument("--sheet", default="")
    args = ap.parse_args()
    sys.path.insert(0, str(args.gate.resolve().parent))
    import quality_gate as gate
    rows = []
    for meta in sorted(args.measure.glob("*.json")):
        row = json.loads(meta.read_text())
        for side in ("before", "after"):
            events = gate.parse_pes((args.measure / f"{row['id']}-{side}.pes").read_bytes())[2]
            row[f"{side}_stitches"] = sum(1 for *_, c in events if c == gate.STITCH)
            row[f"{side}_peak_05mm"] = gate.density_peak(events, 5)
        rows.append(row)
    args.archive.mkdir(parents=True, exist_ok=True)
    (args.archive / "results.json").write_text(json.dumps(rows, indent=1, ensure_ascii=False) + "\n")
    samples = [r for r in rows if r["kind"] == "sample"]
    changed = [r for r in samples if r["changed"]]
    print(f"samples {len(samples)}, changed {len(changed)}, with satin parts {sum(r['satin_parts'] > 0 for r in samples)}, "
          f"blockers {sum(r['blockers'] != 0 for r in rows)}")
    if changed:
        d = sorted(r["after_stitches"] / r["before_stitches"] - 1 for r in changed)
        dp = [r["after_peak_05mm"] - r["before_peak_05mm"] for r in changed]
        print(f"changed samples: stitches {d[0]:+.2%}..{d[-1]:+.2%} (median {d[len(d)//2]:+.2%}); "
              f"peak change min {min(dp)} max {max(dp)}; peak after max {max(r['after_peak_05mm'] for r in changed)}")
    for r in rows:
        if r["kind"] == "lettering" or r["blockers"] != 0:
            print(r["id"], r["before_stitches"], r["after_stitches"], r["before_peak_05mm"], r["after_peak_05mm"], r["blockers"], r.get("blocking_codes"))
    ids = [i for i in args.sheet.split(",") if i]
    if ids:
        tile, title = 432, 60
        sheet = Image.new("RGB", (tile * 2, (tile + title) * len(ids)), "#f1f1f1")
        draw = ImageDraw.Draw(sheet)
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 16)
        by = {r["id"]: r for r in rows}
        for i, key in enumerate(ids):
            for j, side in enumerate(("before", "after")):
                image = Image.open(args.measure / f"{key}-{side}.png").convert("RGB").resize((tile, tile), Image.LANCZOS)
                sheet.paste(image, (j * tile, i * (tile + title) + title))
                r = by[key]
                draw.text((j * tile + 8, i * (tile + title) + 8), f"{key} / {side}", fill="black", font=font)
                draw.text((j * tile + 8, i * (tile + title) + 30), f"{r[side + '_stitches']} stitches; peak {r[side + '_peak_05mm']}", fill="black", font=font)
        sheet.save(args.archive / "comparison.png", optimize=True)


if __name__ == "__main__":
    main()
