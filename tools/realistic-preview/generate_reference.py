#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Executes selected definitions, unmodified, from Ink/Stitch
# lib/svg/rendering.py, lib/utils/geometry.py, lib/threads/color.py and
# lib/svg/units.py@b0edd96311ece82ee48816dc93466274b12c2a9c
# (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors.
"""Generate display-only stitch references, not machine-ready embroidery.

Setup (user-local; no Ink/Stitch installation):
  python3 -m venv --system-site-packages /tmp/myembro-realistic-ref-venv
  /tmp/myembro-realistic-ref-venv/bin/pip install inkex==1.4.1 --no-deps
System packages used: numpy, Pillow, lxml and inkex's Python dependencies.

Run with an explicit Inkscape executable (AppImage extraction AppRun is OK):
  python generate_reference.py --inkscape /path/to/AppRun --out /tmp/myembro-realistic-ref
Only the fixed, SHA-256 checked source definitions named below are executed.
No upstream module imports, stitch generation or application startup is needed.
"""
import argparse
import ast
import colorsys
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import platform
import shutil
import subprocess
import time
import typing
import urllib.request

import inkex
import numpy
from PIL import Image, ImageDraw

COMMIT = "b0edd96311ece82ee48816dc93466274b12c2a9c"
BASE = f"https://raw.githubusercontent.com/inkstitch/inkstitch/{COMMIT}/"
SOURCES = {
    "lib/svg/rendering.py": "918cb59e4a78ccdae8f6989a3d1b00b5b5495b684b1adb64c79be7e5798d6bf0",
    "lib/utils/geometry.py": "59c0d77ec698d322b4b4ff1540ffe93c44093a67e99a0da3d370f251b1bdf652",
    "lib/threads/color.py": "270b05f54ac2c8cc83723f7c7fa767b9a485e45b8e3612429b7099f3143cb23b",
    "lib/svg/units.py": "4ec6d8d6f66d1eebd552ca3a314612fe62d821335c9c451c89ede913e94ba259",
    "LICENSE": "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986",
}
SELECT = {
    "lib/svg/units.py": {"PIXELS_PER_MM"},
    "lib/utils/geometry.py": {"Point"},
    "lib/threads/color.py": {"ThreadColor"},
    "lib/svg/rendering.py": {"stitch_height", "stitch_path", "generate_realistic_filter", "realistic_stitch"},
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def load_upstream(directory):
    # ThreadColor's EmbThread constructor branch is never used: fixture colors
    # are RGB tuples only. A type marker avoids importing pystitch, without
    # changing any original class/method AST or any tuple/color calculations.
    class UnusedEmbThread:
        pass
    namespace = dict(math=math, pi=math.pi, inkex=inkex, typing=typing,
                     numpy=numpy, colorsys=colorsys, Color=inkex.Color,
                     ColorError=inkex.ColorError, EmbThread=UnusedEmbThread,
                     **vars(typing))
    definitions = {}
    for name, digest in SOURCES.items():
        target = directory / name
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(urllib.request.urlopen(BASE + name, timeout=30).read())
        data = target.read_bytes()
        if sha(data) != digest:
            raise ValueError(f"Upstream hash mismatch: {name}")
        if name not in SELECT:
            continue
        selected = []
        for node in ast.parse(data, filename=name).body:
            names = ({node.name} if isinstance(node, (ast.FunctionDef, ast.ClassDef))
                     else {t.id for t in node.targets if isinstance(t, ast.Name)}
                     if isinstance(node, ast.Assign) else set())
            if names & SELECT[name]:
                selected.append(node)
        found = set()
        for node in selected:
            found |= ({node.name} if hasattr(node, 'name') else {t.id for t in node.targets})
        assert found == SELECT[name], (name, found)
        # Compile the original parsed nodes, without replacements or rewrites.
        exec(compile(ast.Module(body=selected, type_ignores=[]), name, "exec"), namespace)
        definitions[name] = sorted(found)
    return namespace, definitions


def fixtures():
    blue, red = (32, 94, 181), (190, 39, 46)
    satin = [(6 if i % 2 == 0 else 10, 4 + i * .2) for i in range(41)]
    tatami = []
    for row in range(21):
        y = 4 + row * .4
        xs = [4, 8, 12] if row % 2 == 0 else [4, 6, 10, 12]
        if row % 2:
            xs.reverse()
        tatami.append((blue, [(x, y) for x in xs]))
    return {
        "horizontal": [(blue, [(4, 8), (12, 8)])],
        "vertical": [(blue, [(8, 4), (8, 12)])],
        "diagonal": [(blue, [(4, 4), (12, 12)])],
        "satin-column": [(blue, satin)],
        "tatami-patch": tatami,
        "crossing": [(blue, [(3, 3), (8, 8), (13, 13)]),
                     (red, [(3, 13), (8, 8), (13, 3)])],
        "white-thread": [((255, 255, 255), satin)],
        "black-thread": [((0, 0, 0), satin)],
    }


def make_svg(upstream, runs, filtered=True):
    ppm = upstream['PIXELS_PER_MM']
    svg = inkex.SvgDocumentElement(width='16mm', height='16mm',
                                   viewBox=f'0 0 {16 * ppm} {16 * ppm}')
    defs = inkex.Defs()
    defs.append(upstream['generate_realistic_filter']())
    svg.append(defs)
    # Explicit white background makes comparisons unambiguous. There is no
    # font/text inside reference images. Labels are overview-only.
    svg.append(inkex.Rectangle(x='0', y='0', width=str(16 * ppm),
                              height=str(16 * ppm), style='fill:#FFFFFF'))
    segments = 0
    for rgb, points in runs:
        assert isinstance(rgb, tuple) and len(rgb) == 3
        color = upstream['ThreadColor'](rgb).visible_on_white.darker.to_hex_str()
        group = inkex.Group()
        svg.append(group)
        for start, end in zip(points, points[1:]):
            assert start != end
            d = upstream['realistic_stitch'](tuple(x * ppm for x in start),
                                             tuple(x * ppm for x in end))
            style = f'fill: {color}; stroke: none;'
            if filtered:
                style += ' filter: url(#realistic-stitch-filter);'
            group.append(inkex.PathElement(attrib={'style': style, 'd': d}))
            segments += 1
    return svg.tostring(), segments


def rasterize(inkscape, svg, png, dpi, env):
    command = [inkscape, str(svg), '--export-area-page', '--export-type=png',
               f'--export-dpi={dpi}', '--export-png-color-mode=RGBA_16',
               f'--export-filename={png}']
    start = time.perf_counter()
    result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError(f"{command}: {result.stderr}")
    seconds = time.perf_counter() - start
    with Image.open(png) as image:
        rgb = numpy.asarray(image.convert('RGB'))
        nonwhite = int(numpy.any(rgb != 255, axis=2).sum())
        assert nonwhite > 0, f'Blank PNG: {png}'
        size = image.size
    return dict(seconds=round(seconds, 6), width_px=size[0], height_px=size[1],
                nonwhite_pixels=nonwhite, sha256=sha(png.read_bytes()),
                stderr=result.stderr.strip())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inkscape', required=True)
    parser.add_argument('--out', type=Path, default=Path('/tmp/myembro-realistic-ref'))
    parser.add_argument('--dpi', nargs='+', type=int, default=[300, 600])
    args = parser.parse_args()
    if any(dpi <= 0 for dpi in args.dpi):
        parser.error('DPI must be positive')
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    upstream, definitions = load_upstream(out / 'upstream')
    env = os.environ.copy()
    # Keep the AppImage from loading ABI-incompatible host GIO plugins.
    (out / 'empty-gio-modules').mkdir(exist_ok=True)
    env['GIO_MODULE_DIR'] = str(out / 'empty-gio-modules')
    env['GTK_MODULES'] = ''
    version_result = subprocess.run([args.inkscape, '--version'], env=env,
                                    capture_output=True, text=True, check=True)
    manifest = dict(upstream_commit=COMMIT, source_sha256=SOURCES,
                    executed_definitions=definitions,
                    rasterizer=version_result.stdout.strip(),
                    rasterizer_path=args.inkscape, platform=platform.platform(),
                    versions={x: importlib.metadata.version(x) for x in ['inkex', 'numpy', 'Pillow']},
                    page_mm=[16, 16], svg_pixels_per_mm=upstream['PIXELS_PER_MM'],
                    export_color_mode='RGBA_16', dpi=args.dpi,
                    color_policy='Ink/Stitch visible_on_white.darker, unmodified',
                    cases={})
    for case, runs in fixtures().items():
        entry = dict(runs=[dict(rgb=rgb, points_mm=points) for rgb, points in runs], png={})
        for filtered in [True, False]:
            prefix = out if filtered else out / 'flat-control'
            (prefix / 'svg').mkdir(parents=True, exist_ok=True)
            svg, count = make_svg(upstream, runs, filtered)
            svg_path = prefix / 'svg' / f'{case}.svg'
            svg_path.write_bytes(svg)
            entry['segments'] = count
            if filtered:
                entry['svg_sha256'] = sha(svg)
            for dpi in args.dpi:
                directory = prefix / 'png' / str(dpi)
                directory.mkdir(parents=True, exist_ok=True)
                png = directory / f'{case}.png'
                result = rasterize(args.inkscape, svg_path, png, dpi, env)
                entry['png'][f'{dpi}' if filtered else f'flat-{dpi}'] = result
                print(f'{case:14} {dpi}dpi {"reference" if filtered else "control":9} '
                      f'{result["width_px"]}x{result["height_px"]} '
                      f'nonwhite={result["nonwhite_pixels"]} {result["seconds"]:.3f}s', flush=True)
        manifest['cases'][case] = entry
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    first_dpi = args.dpi[0]
    tile = 220
    overview = Image.new('RGB', (tile * 4, (tile + 30) * 2), 'white')
    draw = ImageDraw.Draw(overview)
    for i, case in enumerate(fixtures()):
        image = Image.open(out / 'png' / str(first_dpi) / f'{case}.png').convert('RGB')
        image.thumbnail((tile, tile))
        x, y = (i % 4) * tile, (i // 4) * (tile + 30)
        overview.paste(image, (x, y))
        draw.text((x + 6, y + tile + 4), case, fill='black')
    overview.save(out / 'overview.png')
    for name in ['generate_reference.py', 'compare_images.py', 'verify_reference.py']:
        source = Path(__file__).with_name(name)
        if source.resolve() != (out / name).resolve():
            shutil.copy2(source, out / name)
    print(f'PASS: {len(fixtures())} cases x {len(args.dpi)} DPI; filter/control PNGs; manifest.json')


if __name__ == '__main__':
    main()
