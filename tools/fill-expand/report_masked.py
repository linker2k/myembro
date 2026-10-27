#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
import sys,csv,json,importlib.util,argparse
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('output',type=Path);parser.add_argument('--audit',type=Path,required=True);parser.add_argument('--baseline',type=Path,required=True);args=parser.parse_args()
p=args.output;sys.argv=['audit.py'];spec=importlib.util.spec_from_file_location('audit',args.audit);audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
gate=json.loads((p/'gate.json').read_text());checks={Path(x['path']).name:x for x in gate['results']}
rows=list(csv.DictReader((p/'generation.tsv').open(),delimiter='\t'));base={r['input']:r for r in rows if float(r['expand_mm'])==0}
for r in rows:
 zero=p/'pes'/base[r['input']]['file'];original=args.baseline/'pes'/base[r['input']]['file']
 r['zero_main_parity']=zero.read_bytes()==original.read_bytes()
 m=audit.audit('expand2',p/'pes'/r['file']);m.pop('_pooled');(p/(r['file']+'.audit.json')).write_text(json.dumps(m,indent=2))
 r['python_gate']=checks[r['file']]['verdict'];r['penetration']=m['later_boundary_depth_in_earlier_mm']['p50'];r['gap']=m['gap_ratio_proxy']
 b=base[r['input']];r['extent_same']=r['extent']==b['extent'];r['hole_same']=abs(int(r['hole_open_cells'])-int(b['hole_open_cells']))<=1
 r['fallback_no_increase']=int(r['excluded'])<=int(b['excluded']) and int(r['running'])<=int(b['running'])
 r['overlap_target']=r['penetration'] is not None and r['penetration']>=.45 and r['gap']<=.0332
 r['safety_pass']=r['sewout']==r['python_gate']=='PASS' and r['extent_same'] and r['hole_same'] and r['fallback_no_increase'] and r['zero_main_parity']
 r['pass']=r['safety_pass'] and r['overlap_target']
 print(r['input'],r['expand_mm'],r['pass'],r['extent'],r['penetration'],r['gap'],flush=True)
lines=['# Potrace-union closing expansion sweep','',
 'Base 2007a4f + cherry-pick ea71e89 + JTS closing clip. Expand default remains 0 mm; outlines ON.',
 'face/robot: 30 mm, 192 px bilinear, ColorCleanupSweep injected 4×4 speck; proxy: 99.8 mm, aspect-preserving 192 px, no extra noise/contrast processing. Area threshold remains 3 mm².',
 'At nonzero expansion, color top fill is clipped to union(all original Potrace color polygons).buffer(+r).buffer(-r), r=expand in native units. Zero bypasses union/buffer/intersection and follows main 2007a4f. Underlay/outlines remain original. Raw foreground pixels remain only an independent background-hole measurement reference.',
 'Extent is decoded PES bounds in integer 0.1 mm coordinates. Background-hole cells are original mask interior holes sampled at strictly interior centers on a fixed 0.1 mm grid, minus planned accepted TATAMI coverage. This is polygon coverage, not a stitch/thread-footprint raster. Hole area tolerance: 1 cell = 0.01 mm².',
 'PASS = both gates PASS, extent identical to same-input expand=0, hole area difference ≤1 cell, excluded and RUNNING counts do not increase. Existing overlap targets are evaluated separately and combined: penetration p50 ≥0.45 mm and gap ≤3.32%. Zero-byte parity is required. No amount is selected.',
 'JVM time is one sequential generation call, including optional outline checks, with raw background reference preconstructed; trace/PES encode/audit excluded. Raw reference timing is recorded separately. Union geometry is cached after baseline hole measurement; subsequent call timings include JTS closing and outline checks. Desktop timing under concurrent Gradle load; no device timing.', '',
 '| Input | expand mm | Gate Kotlin/Python | Extent native units | Same extent | Open holes mm² | Same holes | Excluded/RUNNING | No increase | Penetration p50 mm | Gap % | Needles | JVM ms | Skipped outlines | Zero PES=main | Safety PASS | Overlap target | All PASS |',
 '|---|---:|---|---|---|---:|---|---|---|---:|---:|---:|---:|---:|---|---|---|---|']
for r in rows:
 lines.append(f"| {r['input']} | {r['expand_mm']} | {r['sewout']}/{r['python_gate']} | {r['extent']} | {r['extent_same']} | {int(r['hole_open_cells'])*.01:.2f} | {r['hole_same']} | {r['excluded']}/{r['running']} | {r['fallback_no_increase']} | {r['penetration']:.3f} | {r['gap']*100:.2f} | {r['stitches']} | {float(r['generate_ms']):.1f} | {r['skipped_outlines']} | {r['zero_main_parity']} | {r['safety_pass']} | {r['overlap_target']} | {r['pass']} |")
lines += ['',f"Both gates: {sum(r['sewout']==r['python_gate']=='PASS' for r in rows)}/12 PASS. Safety predicates: {sum(r['safety_pass'] for r in rows)}/12 PASS; including existing overlap targets: {sum(r['pass'] for r in rows)}/12 PASS. No value selected or default changed.", '',
 'Commands/evidence: generation.log, generation.tsv, gate.log/gate.json, *.pes.audit.json; standalone Sweep.kt (compiled against engine.jar) and report.py. Personal artifacts remain only in /tmp.']
(p/'table-closing.md').write_text('\n'.join(lines)+'\n');(p/'results-closing.json').write_text(json.dumps(rows,indent=2))
