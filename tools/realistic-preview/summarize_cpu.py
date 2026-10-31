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
    parser.add_argument('--before', type=Path)
    parser.add_argument('--benchmark', type=Path, nargs='*', default=[])
    parser.add_argument('--coverage', type=Path)
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

**Not pixel-equivalent yet.** The highest-error case remains tatami. Compare coverage separately;
do not fit new sigma/brightness constants. Exact object bounds and integer viewport ratios are
now represented in the renderer. See docs/research/realistic-preview-cpu.md for source commits.
'''
    if args.before:
        report += '\n## Before/after (same Inkscape reference)\n\n| Scene | DPI | MAE before → after | SSIM before → after |\n|---|---:|---:|---:|\n'
        for dpi in [300,600]:
            before=json.loads((args.before/f'comparison-{dpi}.json').read_text())['metrics']
            after=json.loads((args.out/f'comparison-{dpi}.json').read_text())['metrics']
            for name,value in after.items():
                prior=before[name]
                report+=f"| {name} | {dpi} | {prior['mae_0_255']:.6f} → {value['mae_0_255']:.6f} | {prior['ssim']:.8f} → {value['ssim']:.8f} |\n"
    if args.benchmark:
        report += '\n## Large synthetic workloads (600dpi, 2250×2000px)\n\n'
        report += 'Separate cold JVMs. Render excludes fixture creation/hash/PNG encoding. Heap peak is sampled every 1ms; process RSS is Linux VmHWM, including JVM/native memory. Cache budget covers pixel payload only (one 18,000,000-byte output frame), not key/object overhead.\n\n'
        report += '| Budget/run | Workload | Stitches | ms | ms/stitch | Peak sampled heap MiB | Peak process RSS MiB | Hits/misses |\n|---|---|---:|---:|---:|---:|---:|---:|\n'
        for directory in args.benchmark:
            for path in sorted(directory.glob('*.json')):
                d=json.loads(path.read_text())
                if 'segments' not in d:continue
                label=f"{d.get('jvmMaxHeapBytes',512*1024**2)/1024**2:.0f}MiB JVM cap"
                report+=f"| {label} | {d['workload']} | {d['segments']} | {d['renderMs']:.3f} | {d['msPerStitch']:.6f} | {d['peakSampledHeapBytes']/1024**2:.2f} | {d['processPeakRssKiB']/1024:.2f} | {d['cacheHits']}/{d['cacheMisses']} |\n"
        report += '\nRepeated = a grid of 2500 placements repeated (best case). Varied = unique phase/delta per stitch (zero hits, stress case). These display-only synthetic paths are repository-owned, not machine-ready PES files. The heap cap experiments are benchmark configuration, not application memory policy.\n'
    if args.coverage:
        report+='\n## Coverage substitution diagnostic\n\nSame Kotlin filters, only Java2D coverage replaced by per-path Inkscape SourceAlpha. Reference PNGs unchanged. This is an explicit diagnostic, not the production rasterizer.\n\n| Scene | DPI | Java2D MAE / SSIM | Cairo-alpha MAE / SSIM |\n|---|---:|---:|---:|\n'
        coverage={}
        for dpi in [300,600]:
            regular=json.loads((args.out/f'comparison-{dpi}.json').read_text())['metrics']
            for path in sorted((args.coverage/'png'/str(dpi)).glob('*.png')):
                value=metrics(read(args.reference/'png'/str(dpi)/path.name),read(path))
                coverage[f'{path.stem}-{dpi}']=value
                prior=regular[path.stem]
                report+=f"| {path.stem} | {dpi} | {prior['mae_0_255']:.6f} / {prior['ssim']:.8f} | {value['mae_0_255']:.6f} / {value['ssim']:.8f} |\n"
        (args.out/'coverage-comparison.json').write_text(json.dumps(coverage,indent=2)+'\n')
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
