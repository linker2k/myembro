#!/usr/bin/env python3
"""Draw numbered circles on manual screenshots from uiautomator bounds.

usage: annotate_shots.py SPEC.json SHOT_DIR LANG OUT_DIR

SHOT_DIR holds NAME.png + NAME.xml (uiautomator dump taken with the shot).
For each shot in SPEC, every item [pattern, index, name_ko, name_en] is found
by regex on a node's text or content-desc (index = n-th match; [ko, en] when
the languages differ). Numbers go
top->bottom, then left->right, so the same element gets the same number in
every language even when text width differs. Writes OUT_DIR/NAME.png and
OUT_DIR/legend.md (number -> element name in LANG).
"""
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

from PIL import Image, ImageDraw, ImageFont

R = 30          # circle radius, px on a 1080-wide shot
FILL = (192, 57, 43)
FONT_PATHS = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
]


def nodes(xml_path):
    for n in ElementTree.parse(xml_path).iter("node"):
        b = [int(v) for v in re.findall(r"\d+", n.get("bounds", ""))]
        if len(b) == 4:
            yield n.get("text", ""), n.get("content-desc", ""), b


def find(all_nodes, pattern, index):
    rx = re.compile(pattern)
    hits = [b for t, d, b in all_nodes if (t and rx.search(t)) or (d and rx.search(d))]
    return hits[index] if len(hits) > index else None


def reading_order(found):
    """Rows = elements whose vertical ranges overlap; rows top->bottom, left->right."""
    rows = []
    for e in sorted(found, key=lambda e: e[0][1]):
        if rows and e[0][1] < min(x[0][3] for x in rows[-1]):
            rows[-1].append(e)
        else:
            rows.append([e])
    return [e for row in rows for e in sorted(row, key=lambda e: e[0][0])]


def place(b, w, taken):
    """Circle left of the element; above its left end when the element starts
    at the screen edge; inside its top-left corner as a last resort."""
    l, t, r, btm = b
    if l >= 2 * R + 6:
        cx, cy, step = l - R - 6, (t + btm) // 2, (0, 2 * R + 4)
    elif t >= 2 * R + 4:
        cx, cy, step = l + R, t - R - 2, (2 * R + 4, 0)
    else:
        cx, cy, step = l + R, t + R, (2 * R + 4, 0)
    while any(abs(cx - x) < 2 * R and abs(cy - y) < 2 * R for x, y in taken):
        cx, cy = cx + step[0], cy + step[1]
    taken.append((cx, cy))
    return cx, cy


def font(size):
    for p in FONT_PATHS:
        if Path(p).exists():
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def annotate(png, xml, items, lang, out_png):
    all_nodes = list(nodes(xml))
    found, missing = [], []
    for pattern, index, name_ko, name_en in items:
        if isinstance(index, list):
            index = index[0] if lang == "ko" else index[1]
        b = find(all_nodes, pattern, index)
        name = name_ko if lang == "ko" else name_en
        (found if b else missing).append((b, name) if b else name)
    found = reading_order(found)
    im = Image.open(png).convert("RGB")
    d = ImageDraw.Draw(im)
    f = font(int(R * 1.1))
    taken = []
    for i, (b, _) in enumerate(found, 1):
        cx, cy = place(b, im.width, taken)
        d.ellipse([cx - R, cy - R, cx + R, cy + R], fill=FILL, outline="white", width=4)
        d.text((cx, cy), str(i), fill="white", font=f, anchor="mm")
    im.save(out_png)
    return [name for _, name in found], missing


def main():
    spec_path, shot_dir, lang, out_dir = sys.argv[1:5]
    spec = json.loads(Path(spec_path).read_text(encoding="utf-8"))
    shot_dir, out_dir = Path(shot_dir), Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    legend, bad = [], 0
    for shot, items in spec.items():
        png, xml = shot_dir / f"{shot}.png", shot_dir / f"{shot}.xml"
        if not png.exists() or not xml.exists():
            print(f"SKIP {shot}: no shot"); bad += 1
            continue
        names, missing = annotate(png, xml, items, lang, out_dir / f"{shot}.png")
        legend.append(f"## {shot}\n" + "".join(f"{i}. {n}\n" for i, n in enumerate(names, 1)))
        if missing:
            bad += 1
            print(f"MISSING {shot}: {missing}")
    (out_dir / "legend.md").write_text("\n".join(legend), encoding="utf-8")
    print(f"{len(legend)} annotated, {bad} with problems")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
