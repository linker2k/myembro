# SPDX-License-Identifier: GPL-3.0-or-later
"""Fluent Emoji Flat catalog for the app, like tools/inkstitch_catalog.py for fonts.

Usage: python3 -I tools/emoji_catalog.py <mirror> <out.json> <bundle-dir> <bundled-ids>

<mirror> holds the pinned repository files: assets/<Name>/metadata.json and
assets/<Name>/[Default/]Flat/*.svg. Every emoji gets its pinned repository path, sha256 and size, so
the app downloads the rest exactly like Ink/Stitch fonts. Files the app's SVG reader rejects
(gradients, clip-path, masks, filters, images) are left out. <bundled-ids> (comma separated)
are also copied into <bundle-dir> with the MIT license.
"""
import hashlib
import json
import os
import re
import shutil
import sys
import urllib.parse

REPO = "microsoft/fluentui-emoji"
COMMIT = "1ffb34c752ecf5d402f04cfb4b392c77f57c54bc"
UNSUPPORTED = re.compile(r"Gradient|clip-path|<mask|<filter|<image|<use\b|<text\b")


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def main(mirror, out, bundle_dir, bundled):
    bundled = [b for b in bundled.split(",") if b]
    entries, skipped = [], []
    assets = os.path.join(mirror, "assets")
    for name in sorted(os.listdir(assets)):
        folder = os.path.join(assets, name)
        flat = next((os.path.join(sub, "Flat") for sub in ("", "Default") if os.path.isdir(os.path.join(folder, sub, "Flat"))), None)
        if flat is None:
            skipped.append((name, "no Flat style"))
            continue
        svg_name = sorted(f for f in os.listdir(os.path.join(folder, flat)) if f.endswith(".svg"))[0]
        rel = "/".join(p for p in ("assets", name, flat, svg_name) if p)
        data = open(os.path.join(folder, flat, svg_name), "rb").read()
        if UNSUPPORTED.search(data.decode("utf-8", "replace")):
            skipped.append((name, "unsupported SVG feature"))
            continue
        meta = json.load(open(os.path.join(folder, "metadata.json"), encoding="utf-8"))
        entries.append({
            "id": slug(name),
            "name": meta.get("cldr", name),
            "glyph": meta.get("glyph", ""),
            "group": meta.get("group", ""),
            "keywords": meta.get("keywords", []),
            # Repository path; the app downloads rawBase + path (URL-encoded) and checks sha256.
            "path": rel,
            "sha256": hashlib.sha256(data).hexdigest(),
            "size": len(data),
            "bundled": slug(name) in bundled,
        })
    missing = set(bundled) - {e["id"] for e in entries}
    if missing:
        sys.exit(f"bundled ids not in catalog: {sorted(missing)}")
    os.makedirs(bundle_dir, exist_ok=True)
    for e in entries:
        if e["bundled"]:
            shutil.copyfile(os.path.join(mirror, e["path"]), os.path.join(bundle_dir, e["id"] + ".svg"))
    shutil.copyfile(os.path.join(mirror, "LICENSE"), os.path.join(bundle_dir, "LICENSE"))
    catalog = {"source": f"https://github.com/{REPO}", "commit": COMMIT, "style": "Flat",
               "rawBase": f"https://raw.githubusercontent.com/{REPO}/{COMMIT}/",
               "license": "MIT", "emoji": entries}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")
    size = sum(os.path.getsize(os.path.join(bundle_dir, n)) for n in os.listdir(bundle_dir))
    print(f"catalog {len(entries)} emoji, skipped {len(skipped)} "
          f"({', '.join(sorted({r for _, r in skipped}))}), bundled {len(bundled)} = {size} bytes, "
          f"catalog {os.path.getsize(out)} bytes")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
