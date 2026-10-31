#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Recompute CPU-vs-Inkscape comparison tables; never changes reference images.

After :engine:test --tests '*RealisticThreadRendererTest*':
  python summarize_cpu.py --candidate engine/build/realistic-preview-cpu \
    --reference /tmp/myembro-realistic-ref --out /tmp/myembro-realistic-cpu
Uses the same Pillow/numpy/scikit-image environment as compare_images.py.
"""
import argparse
import json
from pathlib import Path
import shutil

import numpy as np
from PIL import Image, ImageDraw
from compare_images import metrics, read


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if args.out.resolve() != args.candidate.resolve():
        for name in ['png', 'flat-control', 'debug']:
            path = args.candidate / name
            if path.exists():
                shutil.copytree(path, args.out / name, dirs_exist_ok=True)
        for path in args.candidate.glob('*.json'):
            shutil.copy2(path, args.out / path.name)
    rows = []
    summary = []
    for dpi in [300, 600]:
        compared, controls = {}, {}
        for path in sorted((args.reference / 'png' / str(dpi)).glob('*.png')):
            case = path.stem
            candidate = args.out / 'png' / str(dpi) / path.name
            reference_pixels, candidate_pixels = read(path), read(candidate)
            values = metrics(reference_pixels, candidate_pixels)
            # Foreground union ROI MAE avoids diluting thin-stitch errors with white space.
            # No padding/registration/threshold or additional SSIM window convention.
            foreground = np.any(reference_pixels != 255, axis=2) | np.any(candidate_pixels != 255, axis=2)
            values['foreground_union_mae_0_255'] = float(np.abs(
                reference_pixels.astype(float) - candidate_pixels.astype(float))[foreground].mean())
            compared[case] = values
            controls[case] = metrics(read(args.reference / 'flat-control' / 'png' / str(dpi) / path.name),
                                     read(args.out / 'flat-control' / 'png' / str(dpi) / path.name))
            timing = json.loads((args.out / f'{case}-{dpi}.json').read_text())
            rows.append(f'| {case} | {dpi} | {timing["segments"]} | {values["mae_0_255"]:.6f} '
                        f'| {values["ssim"]:.8f} | {values["foreground_union_mae_0_255"]:.3f} '
                        f'| {controls[case]["mae_0_255"]:.6f} | {timing["renderMs"]:.3f} |')
            summary.append(dict(case=case, dpi=dpi, renderMs=timing['renderMs'], **values))
        if len(compared) != 8:
            raise ValueError(f'Expected eight reference scenes at {dpi}, found {len(compared)}')
        for label, data in [('comparison', compared), ('shape', controls)]:
            (args.out / f'{label}-{dpi}.json').write_text(json.dumps(dict(metrics=data), indent=2) + '\n')
    maes = [x['mae_0_255'] for x in summary]
    ssims = [x['ssim'] for x in summary]
    times = [x['renderMs'] for x in summary]
    limits = dict(images=len(summary), maeMin=min(maes), maeMax=max(maes),
                  ssimMin=min(ssims), ssimMax=max(ssims), renderMsMin=min(times),
                  renderMsMax=max(times), renderMsSum=sum(times))
    (args.out / 'summary.json').write_text(json.dumps(limits, indent=2) + '\n')
    report = '''# Kotlin CPU preview vs Inkscape 1.4.2

Experimental, no Android backend/app integration. Eight scenes at identical 300/600dpi
(189×189 / 378×378px). Original reference unchanged. Whole-page RGB8 MAE/SSIM on white;
foreground-union MAE excludes unchanged white pixels. Shape-only control uses the same path/color,
without filters, to separate Java2D/Cairo AA from lighting. No image registration or resizing.

Timing: one JVM invocation per scene/DPI, includes path/coverage/filter/composition, excludes PNG
encoding; JIT/GC and first Java2D use may affect results. Not a 50k/100k or Android benchmark.

| Scene | DPI | Segments | MAE 0–255 | SSIM | Foreground MAE | Shape-only MAE | JVM render ms |
|---|---:|---:|---:|---:|---:|---:|---:|
''' + '\n'.join(rows) + f'''

16 images: MAE {min(maes):.6f}…{max(maes):.6f}, SSIM {min(ssims):.8f}…{max(ssims):.8f}.
JVM render {min(times):.3f}…{max(times):.3f} ms, total {sum(times):.3f} ms.

**Not pixel-equivalent yet.** The highest-error case is tatami. A whole-page filter-region
diagnostic reduces its error substantially: next step is the original Inkscape FilterSlot/FilterUnits
bounds/coordinate mapping, then coverage AA. Do not fit new sigma/brightness constants.
See docs/research/realistic-preview-cpu.md for fixed source commits, constants and limitations.
'''
    (args.out / 'report.md').write_text(report)
    # Original/candidate pairs, same-size pixels; contact-sheet labels are not compared.
    tile = 378
    sheet = Image.new('RGB', (tile * 4, (tile + 20) * 4), 'white')
    draw = ImageDraw.Draw(sheet)
    names = ['horizontal','vertical','diagonal','satin-column','tatami-patch','crossing','white-thread','black-thread']
    for i, name in enumerate(names):
        col, row = (i % 2) * 2, i // 2
        for offset, root in [(0,args.reference),(1,args.out)]:
            image = Image.open(root/'png'/'600'/f'{name}.png').convert('RGB')
            x, y = (col+offset)*tile,row*(tile+20)
            sheet.paste(image,(x,y+20))
            draw.text((x+4,y+3),name+(' Inkscape' if offset==0 else ' Kotlin'),fill='black')
    sheet.save(args.out/'overview.png')
    print('MEASURED:', json.dumps(limits))
    print(f'Table: {args.out / "report.md"}; no quality PASS threshold applied')


if __name__ == '__main__':
    main()
