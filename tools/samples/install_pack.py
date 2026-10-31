#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Install the starter design pack into app/src/main/assets/samples.

Re-run after regenerating the pack (engine changes): every sample_*.pes is replaced and
manifest.json is rewritten from tools/samples/catalog.json (names, fit size, hoop) plus the
pack's results.json (stitches, colors, measured size). Designs missing from the pack are
skipped and reported, never kept from an older pack.

    python3 tools/samples/install_pack.py [--pack /tmp/myembro-sample-pack]
    python3 tools/samples/install_pack.py --provenance /tmp/myembro-cute-art   # also rewrite docs/samples/provenance.json
"""
import argparse
import csv
import datetime
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "tools/samples/catalog.json"
ASSETS = ROOT / "app/src/main/assets/samples"
PROVENANCE = ROOT / "docs/samples/provenance.json"
MODEL = "gpt image via Codex"
# Manager recolors after generation (user decision, 2026-10-31).
RECOLORED = {"21-skull-rose", "25-winged-heart", "31-rainbow-cloud"}
# Left out of the pack by user decision (2026-11-01): never installed, named or attributed.
EXCLUDED = {"c11-minhwa-peony"}
SMITHSONIAN = "Smithsonian American Art Museum, Tattoo Flash 1998.84.50A–F (CC0), https://americanart.si.edu/"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pes_stats(data):
    """Stitch count, color blocks and size in 0.1 mm from the PEC stitch stream (pyembroidery read_pec layout)."""
    pec = int.from_bytes(data[8:12], "little")
    colors = data[pec + 48] + 1
    i = pec + 48 + 1 + colors + (0x1D0 - (colors - 1)) + 3 + 0x0B
    x = y = 0
    stitches, xs, ys = 0, [], []
    while i + 1 < len(data):
        a, b = data[i], data[i + 1]
        if a == 0xFF and b == 0x00:
            break
        if a == 0xFE and b == 0xB0:
            i += 3
            continue
        flags = 0
        def coordinate(at):
            nonlocal flags
            v = data[at]
            if v & 0x80:
                flags |= v & 0x30
                value = ((v & 0x0F) << 8) | data[at + 1]
                return (value - 0x1000 if value & 0x800 else value), at + 2
            return (v - 0x80 if v > 0x3F else v), at + 1
        dx, i = coordinate(i)
        dy, i = coordinate(i)
        x += dx
        y += dy
        if not flags:
            stitches += 1
        xs.append(x)
        ys.append(y)
    return stitches, colors, max(xs) - min(xs), max(ys) - min(ys)


def install(pack):
    catalog = [d for d in json.loads(CATALOG.read_text(encoding="utf-8"))["designs"] if d["id"] not in EXCLUDED]
    results = {row["id"]: row for row in json.loads((pack / "results.json").read_text(encoding="utf-8"))}
    for old in ASSETS.glob("sample_*.pes"):
        old.unlink()
    samples, missing = [], []
    for design in catalog:
        source = pack / "pes" / f"{design['id']}.pes"
        result = results.get(design["id"])
        if not source.is_file() or not result or not result.get("pes_written"):
            missing.append(design["id"])
            continue
        target = ASSETS / design["file"]
        shutil.copyfile(source, target)
        stitches, colors, width, height = pes_stats(target.read_bytes())
        # The pack may adopt a larger fit than planned when that recovers missing regions (size-comparison.md).
        fit = [round(float(v)) for v in result["fit"].split("x")]
        samples.append({
            "file": design["file"],
            "title": design["title"],
            "titleEn": design["titleEn"],
            "license": "CC0-1.0",
            "sha256": sha256(target),
            "stitches": stitches,
            "colors": colors,
            "widthMm": width / 10,
            "heightMm": height / 10,
            "fitMm": fit,
            "plannedFitMm": design["fitMm"],
            "hoopMm": design["hoopMm"],
            "technique": design["kind"],
            "source": f"AI-generated image ({MODEL}), converted by myembro; docs/samples/provenance.json id {design['id']}",
            "attribution": "myembro contributors",
        })
    manifest = {"version": 2, "hoopMm": [100, 100], "samples": samples}
    (ASSETS / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"installed {len(samples)}/{len(catalog)} designs into {ASSETS.relative_to(ROOT)}")
    for name in missing:
        print(f"missing from pack: {name}")


def _date(record, fallback):
    for key in ("start_kst", "start"):
        value = record.get(key)
        if value:
            return re.match(r"\d{4}-\d{2}-\d{2}", value).group(0)
    return fallback


def _generation_records(art):
    """id -> (prompt, date) from every generation*.json and the batch prompt.txt files."""
    records = {}
    for path in sorted(art.glob("*/generation*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        for image in data.get("images", []):
            name = image["name"]
            key = f"{image['n']:02d}-{name}" if "n" in image else name
            if image.get("prompt"):
                records.setdefault(path.parent.name, {})[key] = (image["prompt"], _date(image, _date(data, None)))
    for path in sorted(art.glob("*/prompt.txt")):
        lines = path.read_text(encoding="utf-8").splitlines()
        rule = re.search(r"Every image: (.*?)(?: Do not write SVG.*)?$", lines[0]).group(1)
        day = datetime.date.fromtimestamp(path.stat().st_mtime).isoformat()
        for line in lines[1:]:
            match = re.match(r"(\S+)\.png: (.*)", line.strip())
            if match:
                records.setdefault(path.parent.name, {})[match.group(1)] = (f"{match.group(2)}. {rule}", day)
    return records


def provenance(art):
    catalog = [d for d in json.loads(CATALOG.read_text(encoding="utf-8"))["designs"] if d["id"] not in EXCLUDED]
    listed = {item["id"]: item for item in json.loads((art / "final-list.json").read_text(encoding="utf-8"))}
    records = _generation_records(art)
    plan = {int(m.group(1)): f"{m.group(2).strip()} ({m.group(3).strip()})"
            for m in re.finditer(r"^\| (\d+) \| ([^|]+) \| ([^|]+) \|$", (art / "plan.md").read_text(encoding="utf-8"), re.M)}
    flash = {}
    with open(art / "flash/sources.csv", newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            flash.setdefault(row["output_file"][:-4], []).append(row)
    rows = []
    for design in catalog:
        folder, file_name = listed[design["id"]]["src"].split("/")
        stem = file_name[:-4]
        found = records.get(folder, {}).get(stem) or records.get(folder, {}).get(design["id"])
        if not found:  # recolor and final folders keep the original batch prompt
            found = next((r[design["id"]] for r in records.values() if design["id"] in r), None)
        prompt_note = None
        if not found:  # generation record saved without its prompt: the plan row it was made from
            number = int(design["id"][:2]) if design["id"][:2].isdigit() else None
            planned = plan.get(number)
            if not planned:
                sys.exit(f"no prompt for {design['id']}")
            found = (planned, "2026-10-31")
            prompt_note = "exact prompt not saved; subject and format from plan.md"
        prompt, day = found
        edits = []
        if design["id"] in RECOLORED:
            edits.append("recolored after generation")
        if folder in ("fix", "fix2"):
            edits.append("image edit of an earlier generation (fix pass)")
        if folder == "redo":
            edits.append("regenerated (redo pass)")
        row = {"id": design["id"], "file": design["file"], "title": design["title"], "titleEn": design["titleEn"],
               "prompt": prompt, "date": day or "2026-10-31", "model": MODEL, "edits": edits, "license": "CC0-1.0"}
        if prompt_note:
            row["promptNote"] = prompt_note
        if design["id"].startswith("F"):
            sources = flash.get(stem, [])
            row["source"] = SMITHSONIAN
            row["sourceSheets"] = sorted({s["source_title"] for s in sources})
            row["edits"] = edits + ["redrawn from the source tattoo flash: " + "; ".join(sorted({s["motif"] for s in sources}))]
        rows.append(row)
    PROVENANCE.parent.mkdir(parents=True, exist_ok=True)
    PROVENANCE.write_text(json.dumps({"version": 1, "designs": rows}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"provenance for {len(rows)} designs in {PROVENANCE.relative_to(ROOT)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pack", type=Path, default=Path("/tmp/myembro-sample-pack"))
    parser.add_argument("--provenance", type=Path, metavar="ART_DIR")
    args = parser.parse_args()
    install(args.pack)
    if args.provenance:
        provenance(args.provenance)


if __name__ == "__main__":
    main()
