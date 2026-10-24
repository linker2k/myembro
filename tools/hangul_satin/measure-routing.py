#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Independent PEC jump baseline for TWO known separated glyphs; never production segmentation.
Run with the quality gate's existing Python environment (pyembroidery); does not alter the gate.
"""
import argparse,json,math,pathlib,sys
p=argparse.ArgumentParser();p.add_argument('pes',type=pathlib.Path);p.add_argument('output',type=pathlib.Path);p.add_argument('--gate-root',type=pathlib.Path,default=pathlib.Path('/tmp/myembro-quality-gate'));p.add_argument('--labels',default='정민');a=p.parse_args()
assert len(a.labels)==2
sys.path.insert(0,str(a.gate_root));import gate
events,_=gate.decode(a.pes);xs=sorted({x for x,y,c in events if c==gate.STITCH})
gaps=sorted([(b-x,x,b) for x,b in zip(xs,xs[1:])],reverse=True);assert gaps[0][0]>gaps[1][0]
split=(gaps[0][1]+gaps[0][2])/2
rows=[dict(character=ch,jump_count=0,jump_mm=0.,trim_count=0) for ch in a.labels]
previous=(0,0);order=[];cross=0
for x,y,c in events:
 if c==gate.STITCH:
  glyph=0 if x<split else 1
  if not order or order[-1]!=glyph:order.append(glyph)
 if c==gate.TRIM:rows[0 if previous[0]<split else 1]['trim_count']+=1
 if c==gate.JUMP:
  glyph=0 if x<split else 1;rows[glyph]['jump_count']+=1;rows[glyph]['jump_mm']+=math.dist(previous,(x,y))/10
  if (previous[0]<split)!=(x<split):cross+=1
 if c in (gate.STITCH,gate.JUMP):previous=(x,y)
result=dict(source=str(a.pes),sha256=gate.sha(a.pes.read_bytes()),assignment='Two known separated glyphs; midpoint of largest unoccupied needle-x interval; jump attributed to destination glyph; origin jump included; measurement only, not a general character recognizer',split_x_units=split,glyph_needle_sequence=[a.labels[x] for x in order],cross_glyph_jumps=cross,rows=rows,total_jump_mm=sum(r['jump_mm'] for r in rows))
a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False,indent=2))
