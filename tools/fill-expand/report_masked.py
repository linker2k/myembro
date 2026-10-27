#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
import sys,csv,json,importlib.util,argparse
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('output',type=Path);parser.add_argument('--audit',type=Path,required=True);args=parser.parse_args()
p=args.output;sys.argv=['audit.py'];spec=importlib.util.spec_from_file_location('audit',args.audit);audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
gate=json.loads((p/'gate.json').read_text());checks={Path(x['path']).name:x for x in gate['results']}
rows=list(csv.DictReader((p/'generation.tsv').open(),delimiter='\t'));base={r['input']:r for r in rows if float(r['expand_mm'])==0}
for r in rows:
 m=audit.audit('expand2',p/'pes'/r['file']);m.pop('_pooled');(p/(r['file']+'.audit.json')).write_text(json.dumps(m,indent=2))
 r['python_gate']=checks[r['file']]['verdict'];r['penetration']=m['later_boundary_depth_in_earlier_mm']['p50'];r['gap']=m['gap_ratio_proxy']
 b=base[r['input']];r['extent_same']=r['extent']==b['extent'];r['hole_same']=abs(int(r['hole_open_cells'])-int(b['hole_open_cells']))<=1
 r['fallback_no_increase']=int(r['excluded'])<=int(b['excluded']) and int(r['running'])<=int(b['running'])
 r['pass']=r['sewout']==r['python_gate']=='PASS' and r['extent_same'] and r['hole_same'] and r['fallback_no_increase']
 print(r['input'],r['expand_mm'],r['pass'],r['extent'],r['penetration'],r['gap'],flush=True)
lines=['# Mask-clipped expansion sweep','',
 'Base 2007a4f + cherry-pick ea71e89 + raw non-background pixel-mask clip. Expand default remains 0 mm; outlines ON.',
 'face/robot: 30 mm, 192 px bilinear, ColorCleanupSweep injected 4×4 speck; proxy: 99.8 mm, aspect-preserving 192 px, no extra noise/contrast processing. Area threshold remains 3 mm².',
 'All color top fills, including expand=0 baseline, are intersected with the ORIGINAL background-selection foreground pixel cells (before mergeSmall/color Potrace). Color outlines are NOT unioned to construct this mask. Underlay/outlines remain original.',
 'Extent is decoded PES bounds in integer 0.1 mm coordinates. Background-hole cells are original mask interior holes sampled at strictly interior centers on a fixed 0.1 mm grid, minus planned accepted TATAMI coverage. This is polygon coverage, not a stitch/thread-footprint raster. Hole area tolerance: 1 cell = 0.01 mm².',
 'PASS = both gates PASS, extent identical to same-input expand=0, hole area difference ≤1 cell, excluded and RUNNING counts do not increase. Overlap metrics are reported without adding selection thresholds.',
 'JVM time is one sequential generation call, including optional outline checks, with foreground mask preconstructed; trace/PES encode/audit excluded. Mask construction times are recorded separately in generation.log. Desktop timing under concurrent Gradle load; no device timing.', '',
 '| Input | expand mm | Gate Kotlin/Python | Extent native units | Same extent | Open holes mm² | Same holes | Excluded/RUNNING | No increase | Penetration p50 mm | Gap % | Needles | JVM ms | Skipped outlines | PASS |',
 '|---|---:|---|---|---|---:|---|---|---|---:|---:|---:|---:|---:|---|']
for r in rows:
 lines.append(f"| {r['input']} | {r['expand_mm']} | {r['sewout']}/{r['python_gate']} | {r['extent']} | {r['extent_same']} | {int(r['hole_open_cells'])*.01:.2f} | {r['hole_same']} | {r['excluded']}/{r['running']} | {r['fallback_no_increase']} | {r['penetration']:.3f} | {r['gap']*100:.2f} | {r['stitches']} | {float(r['generate_ms']):.1f} | {r['skipped_outlines']} | {r['pass']} |")
lines += ['',f"Both gates: {sum(r['sewout']==r['python_gate']=='PASS' for r in rows)}/12 PASS. Combined predicates: {sum(r['pass'] for r in rows)}/12 PASS. No value selected or default changed.", '',
 'Commands/evidence: generation.log, generation.tsv, gate.log/gate.json, *.pes.audit.json; standalone Sweep.kt (compiled against engine.jar) and report.py. Personal artifacts remain only in /tmp.']
(p/'table.md').write_text('\n'.join(lines)+'\n');(p/'results.json').write_text(json.dumps(rows,indent=2))
