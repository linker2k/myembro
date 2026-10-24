#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Render the four S4 candidate PES files using the independent pyembroidery reader.
Usage: /path/to/pyembroidery-python tools/render_tatami_sewtest.py [/tmp/myembro-sewtest/s4]
"""
import json
from pathlib import Path
import sys

from PIL import Image, ImageDraw, ImageFont
import pyembroidery as emb


def render(directory):
    manifest = json.loads((directory / 'manifest.json').read_text())
    report = json.loads((directory / 'quality-gate.json').read_text())
    results = {Path(r['path']).name: r for r in report['results']}
    image = Image.new('RGB', (1200, 1200), '#f4f6f8')
    draw = ImageDraw.Draw(image)
    font_path = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
    title = ImageFont.truetype(font_path, 28)
    label = ImageFont.truetype(font_path, 21)
    small = ImageFont.truetype(font_path, 17)
    draw.text((30, 18), 'S4 / Default tatami + underlay', fill='#152736', font=title)
    draw.text((30, 58), 'Actual PES stitches | same scale in all panels | grid 5 mm', fill='#526573', font=small)
    for i, case in enumerate(manifest['cases']):
        x, y = 20 + (i % 2) * 600, 100 + (i // 2) * 525
        draw.rounded_rectangle((x, y, x + 560, y + 505), radius=12, fill='white', outline='#d7dfe5')
        draw.text((x + 18, y + 16), case['label'], fill='#152736', font=label)
        result = results[Path(case['path']).name]
        draw.text((x + 18, y + 47), f"{case['stitches']:,} stitches | gate {result['verdict']}", fill='#276749', font=small)
        cx, cy = x + 280, y + 275
        # Native PES coordinates are 0.1mm, so 1.2px/unit = 12px/mm.
        def pixel(point):
            return cx + point[0] * 1.2, cy + point[1] * 1.2
        for grid in range(-15, 16, 5):
            offset = grid * 12
            draw.line((cx - 210, cy + offset, cx + 210, cy + offset), fill='#edf1f4')
            draw.line((cx + offset, cy - 200, cx + offset, cy + 200), fill='#edf1f4')
        pattern = emb.read_pes(case['path'])
        previous = None
        needles = []
        for stitch in pattern.stitches:
            command = stitch[2] & 255
            if command == emb.STITCH:
                if previous is not None:
                    draw.line((pixel(previous), pixel(stitch)), fill='#245b85', width=1)
                needles.append(stitch)
            previous = stitch
        for point, color in [(needles[0], '#15803d'), (needles[-1], '#dc2626')]:
            px, py = pixel(point)
            draw.ellipse((px - 3, py - 3, px + 3, py + 3), fill=color)
        image.crop((x, y, x + 560, y + 505)).save(directory / f"{case['id']}.png")
    draw.text((30, 1160), 'Green: start / Red: end. Automated PASS; final acceptance requires physical sew-out.', fill='#526573', font=small)
    image.save(directory / 'overview.png')
    print(directory / 'overview.png')


if __name__ == '__main__':
    render(Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/myembro-sewtest/s4'))
