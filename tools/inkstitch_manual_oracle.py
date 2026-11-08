#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Oracle for Ink/Stitch v3.3.0 manual stitch node positions, using upstream inkex (1.4.1).

Stroke.paths (lib/elements/stroke.py@v3.3.0) sews a manual_stitch path at its nodes:
  [LineString(self.strip_control_points(subpath)).coords for subpath in path if len(subpath) > 1]
where path = CubicSuperPath of the element path with its composed transform, and the document
viewBox transform (svg/units.py get_viewbox_transform) on top. This writes those node lists per
glyph layer, in document order, for InkStitchManualOracleTest to compare with the Kotlin parser.

usage: python -I tools/inkstitch_manual_oracle.py <font dir with ltr.svg> <out.json>
(needs inkex 1.4.1 in a throwaway venv; the font data is only read)
"""
import json, sys, unicodedata, html
import inkex
from inkex.paths import CubicSuperPath
from inkex.units import convert_unit

INKSTITCH = "{http://inkstitch.org/namespace}"
LABEL = "{http://www.inkscape.org/namespaces/inkscape}label"


def viewbox_transform(svg):
    vb = [float(v) for v in (svg.get("viewBox") or "").replace(",", " ").split()]
    if len(vb) != 4:
        return inkex.Transform()
    sx = convert_unit(svg.get("width", f"{vb[2]}"), "px") / vb[2]
    sy = convert_unit(svg.get("height", f"{vb[3]}"), "px") / vb[3]
    return inkex.Transform(scale=(sx, sy)) @ inkex.Transform(translate=(-vb[0], -vb[1]))


def hidden(node, layer):
    while node is not None and node is not layer:
        if node.style.get("display") == "none":
            return True
        node = node.getparent()
    return False


def main():
    font_dir, out = sys.argv[1:3]
    svg = inkex.load_svg(f"{font_dir}/ltr.svg").getroot()
    root = viewbox_transform(svg)
    glyphs = {}
    for layer in svg.iter("{http://www.w3.org/2000/svg}g"):
        label = layer.get(LABEL) or ""
        if not label.startswith("GlyphLayer-"):
            continue
        name = unicodedata.normalize("NFC", html.unescape(label[len("GlyphLayer-"):]))
        paths = []
        for node in layer.iter("{http://www.w3.org/2000/svg}path"):
            manual = node.get(INKSTITCH + "stroke_method") == "manual_stitch" or \
                (node.get(INKSTITCH + "manual_stitch") or "").lower() in ("true", "1", "yes")
            if not manual or hidden(node, layer):
                continue
            path = node.path.to_absolute().transform(root @ node.composed_transform())
            csp = CubicSuperPath(path)
            paths += [[list(p) for _, p, _ in sub] for sub in csp if len(sub) > 1]
        if paths:
            glyphs[name] = paths
    with open(out, "w") as f:
        json.dump(glyphs, f)
    print(len(glyphs), "glyphs,", sum(len(v) for v in glyphs.values()), "manual subpaths")


if __name__ == "__main__":
    main()
