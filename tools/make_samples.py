#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline sample pack builder. Geometry generated here is dedicated to CC0-1.0.

Requires pyembroidery==1.5.1, Pillow and fontTools. No network access or remote code.
Run with the project's existing pyembroidery venv (system Pillow/fontTools can be
exposed with PYTHONPATH=/usr/lib/python3/dist-packages). Coordinates are 0.1 mm.
Font-derived lettering retains the notices in assets/samples/LICENSES.md.
"""
import hashlib
import json
import math
from pathlib import Path
from importlib.metadata import version

import pyembroidery as pe
from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont
from fontTools.pens.basePen import BasePen

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/main/assets/samples"
SOURCE = ROOT / "tools/sample_sources"
COLORS = [0xDB4767, 0x329986, 0xE7AD34, 0x5278B7]
SHA = "6e65f1ae8599b1e04d3e082aae1b86abed52ec9e"
PACK = []


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def circle(radius, cx=0, cy=0, count=240):
    return [(cx + radius * math.cos(i * math.tau / count), cy + radius * math.sin(i * math.tau / count)) for i in range(count + 1)]


def polar(fn, count=360):
    return [(fn(t) * math.cos(t), fn(t) * math.sin(t)) for t in [i * math.tau / count for i in range(count + 1)]]


def heart(size=1):
    return [(size * 16 * math.sin(t)**3, -size * (13 * math.cos(t) - 5 * math.cos(2*t) - 2 * math.cos(3*t) - math.cos(4*t)))
            for t in [i * math.tau / 360 for i in range(361)]]


def star(radius=340, inner=145, arms=5):
    pts = [(r * math.cos(-math.pi/2 + i*math.pi/arms), r * math.sin(-math.pi/2 + i*math.pi/arms))
           for i in range(2*arms) for r in [radius if i % 2 == 0 else inner]]
    return pts + pts[:1]


def resample(points, step=25):
    """Uniform arc-length samples; joins/corners and very short paths may have shorter ends."""
    if len(points) < 2:
        return []
    lengths = [math.dist(a, b) for a, b in zip(points, points[1:])]
    total = sum(lengths)
    if total == 0:
        return []
    count = max(1, math.ceil(total / step))
    result, segment, passed = [], 0, 0.0
    for i in range(count + 1):
        at = total * i / count
        while segment < len(lengths)-1 and passed + lengths[segment] < at:
            passed += lengths[segment]
            segment += 1
        t = 0 if lengths[segment] == 0 else min(1, (at-passed)/lengths[segment])
        a, b = points[segment], points[segment+1]
        result.append((a[0] + (b[0]-a[0])*t, a[1] + (b[1]-a[1])*t))
    return result


def fill(polygon):
    """0.4 mm scanlines, 3 mm stitches, 1.5 mm alternating phase, serpentine connectors."""
    rows = []
    low, high = min(p[1] for p in polygon), max(p[1] for p in polygon)
    for row in range(math.ceil((high-low)/4)):
        y = low + 2 + row*4
        hits = sorted(a[0] + (y-a[1])*(b[0]-a[0])/(b[1]-a[1]) for a, b in zip(polygon, polygon[1:])
                      if (a[1] <= y < b[1]) or (b[1] <= y < a[1]))
        spans = []
        for left, right in zip(hits[::2], hits[1::2]):
            xs = [left]
            phase = (row % 2)*15
            x = math.ceil((left-phase)/30)*30 + phase
            while x < right:
                if x > left + 0.5:
                    xs.append(x)
                x += 30
            xs.append(right)
            spans.append([(x, y) for x in xs])
        if row % 2:
            spans = [list(reversed(s)) for s in reversed(spans)]
        # Separate spans of a concave shape must not sew through empty fabric.
        if len(spans) == 1 and rows:
            rows[-1].extend(spans[0])
        else:
            rows.extend(spans)
    return rows


def hershey(text, height=300):
    records = (ROOT / 'engine/src/main/resources/lettering/futural.jhf').read_text().splitlines()
    scale, cursor, paths = height/21, 0, []
    for char in text:
        record = records[ord(char)-32]
        left, right = ord(record[8])-82, ord(record[9])-82
        stroke = []
        for i in range(10, len(record), 2):
            pair = record[i:i+2]
            if pair == ' R':
                if stroke:
                    paths.append(stroke)
                stroke = []
            else:
                stroke.append((cursor+(ord(pair[0])-82-left)*scale, (ord(pair[1])-82)*scale))
        if stroke:
            paths.append(stroke)
        cursor += (right-left)*scale + 25
    return paths


class OutlinePen(BasePen):
    def __init__(self, glyphset):
        super().__init__(glyphset)
        self.paths, self.current = [], []
    def _moveTo(self, point):
        self.current = [point]
    def _lineTo(self, point):
        self.current.append(point)
    def _curveToOne(self, a, b, c):
        start = self.current[-1]
        for i in range(1, 25):
            t, u = i/24, 1-i/24
            self.current.append(tuple(u**3*start[k] + 3*u*u*t*a[k] + 3*u*t*t*b[k] + t**3*c[k] for k in (0, 1)))
    def _qCurveToOne(self, a, b):
        start = self.current[-1]
        for i in range(1, 17):
            t, u = i/16, 1-i/16
            self.current.append(tuple(u*u*start[k] + 2*u*t*a[k] + t*t*b[k] for k in (0, 1)))
    def _closePath(self):
        self.current.append(self.current[0])
        self.paths.append(self.current)
        self.current = []
    def _endPath(self):
        if self.current:
            self.paths.append(self.current)
        self.current = []


def korean(text):
    path = ROOT / 'app/src/main/res/font/jua_regular.ttf'
    assert digest(path) == '769677aef240bfc3b9965f2b50748075bff885e6c6992fc591a3fb268279f898'
    font = TTFont(path)
    glyphs, cmap, cursor, paths = font.getGlyphSet(), font.getBestCmap(), 0, []
    for char in text:
        name = cmap[ord(char)]
        pen = OutlinePen(glyphs)
        glyphs[name].draw(pen)
        paths.extend([[(x+cursor, -y) for x, y in p] for p in pen.paths])
        cursor += glyphs[name].width + 80
    points = [p for path in paths for p in path]
    factor = 350/(max(p[1] for p in points)-min(p[1] for p in points))
    return [[(x*factor, y*factor) for x, y in p] for p in paths]


def design(name, title, groups, *, triple=False, filled=False, license='CC0-1.0', source=None, source_sha=None):
    """groups = [(RGB, [polylines])]; every disconnected motion is trimmed before jumping."""
    points = [p for _, paths in groups for path in paths for p in path]
    minx, maxx = min(p[0] for p in points), max(p[0] for p in points)
    miny, maxy = min(p[1] for p in points), max(p[1] for p in points)
    factor = min(1, 860/max(1, maxx-minx), 860/max(1, maxy-miny))
    cx, cy = (minx+maxx)/2, (miny+maxy)/2
    pattern = pe.EmbPattern()
    pattern.metadata('name', name)
    sewn, last = False, (0, 0)
    for color_index, (color, paths) in enumerate(groups):
        pattern.add_thread({'color':color})
        if color_index:
            pattern.add_stitch_absolute(pe.TRIM, *last)
            pattern.add_stitch_absolute(pe.COLOR_CHANGE, *last)
            sewn = False
        for path in paths:
            pts = [((x-cx)*factor, (y-cy)*factor) for x, y in path]
            # Fill rows already have deliberately phased stitch points; split only long connectors.
            sampled = [pts[0]] if filled else resample(pts)
            if filled:
                for a, b in zip(pts, pts[1:]):
                    n = max(1, math.ceil(math.dist(a,b)/30))
                    sampled.extend((a[0]+(b[0]-a[0])*i/n, a[1]+(b[1]-a[1])*i/n) for i in range(1,n+1))
            if len(sampled) < 2:
                continue
            sampled = [(round(x), round(y)) for x, y in sampled]
            if sewn:
                pattern.add_stitch_absolute(pe.TRIM, *last)
            pattern.add_stitch_absolute(pe.JUMP, *sampled[0])
            pattern.add_stitch_absolute(pe.STITCH, *sampled[0])
            last = sampled[0]
            for p in sampled[1:]:
                if p == last:
                    continue
                pattern.add_stitch_absolute(pe.STITCH, *p)
                if triple:
                    pattern.add_stitch_absolute(pe.STITCH, *last)
                    pattern.add_stitch_absolute(pe.STITCH, *p)
                last = p
            sewn = True
    pattern.add_stitch_absolute(pe.END, *last)
    path = OUT / f'sample_{name}.pes'
    # Disable automatic ties/trim decisions; our explicit travel/trim commands are authoritative.
    pe.write_pes(pattern, str(path), {'version':6, 'tie_on':False, 'tie_off':False, 'max_stitch':40})
    back = pe.read(str(path))
    bounds = back.bounds()
    assert max(bounds[2]-bounds[0], bounds[3]-bounds[1]) <= 900, (name,bounds)
    entry = {'file':path.name, 'title':title, 'license':license, 'sha256':digest(path),
             'stitches':sum((s[2]&pe.COMMAND_MASK)==pe.STITCH for s in back.stitches),
             'colors':len(list(back.get_as_colorblocks())), 'widthMm':round((bounds[2]-bounds[0])/10,1),
             'heightMm':round((bounds[3]-bounds[1])/10,1),
             'technique':'scanline 0.4mm / 3mm / staggered 1.5mm' if filled else 'triple running' if triple else 'running 2.5mm',
             'source':source or 'tools/make_samples.py (original myembro sample geometry)',
             'attribution':'EmbroidePy, Copyright (c) 2019' if source_sha else 'myembro contributors'}
    if source_sha:
        entry['sourceSha256'] = source_sha
    PACK.append(entry)
    print(path.name, entry['stitches'], entry['colors'], bounds)


def upstream(name, title, expected):
    path = SOURCE / f'EmbroidePy-{name}.pes'
    assert digest(path) == expected
    pattern = pe.read(str(path))
    groups, paths, stroke = [], [], []
    for stitches, thread in pattern.get_as_colorblocks():
        paths, stroke = [], []
        for x, y, command in stitches:
            if command & pe.COMMAND_MASK == pe.STITCH:
                stroke.append((x,y))
            elif stroke:
                paths.append(stroke); stroke=[]
        if stroke:
            paths.append(stroke)
        if any(len(p)>1 for p in paths):
            groups.append((thread.color & 0xFFFFFF, [p for p in paths if len(p)>1]))
    design('community_'+name, title, groups, license='MIT',
           source=f'https://github.com/EmbroidePy/samples/blob/{SHA}/{name}.pes', source_sha=expected)


def sheet():
    cols, cell = 5, 280
    image = Image.new('RGB', (cols*cell, math.ceil(len(PACK)/cols)*cell+80), '#f7f3ea')
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(str(ROOT/'app/src/main/res/font/jua_regular.ttf'), 19)
    small = ImageFont.truetype(str(ROOT/'app/src/main/res/font/jua_regular.ttf'), 14)
    draw.text((24,15), f'myembro | {len(PACK)} samples | 100x100 mm', fill='#263d3b', font=font)
    draw.text((24,43), 'CC0 originals + MIT community designs | running / triple / fill / lettering', fill='#536563', font=small)
    for index, entry in enumerate(PACK):
        left, top = (index%cols)*cell+10, (index//cols)*cell+80
        draw.rounded_rectangle((left,top,left+260,top+270),radius=16,fill='#fffefa',outline='#ddd5c5')
        pattern = pe.read(str(OUT/entry['file']))
        bounds = pattern.bounds()
        scale = 210/max(1,bounds[2]-bounds[0],bounds[3]-bounds[1])
        cx,cy=(bounds[0]+bounds[2])/2,(bounds[1]+bounds[3])/2
        color, last = 0, None
        for x,y,cmd in pattern.stitches:
            command = cmd & pe.COMMAND_MASK
            point = (left+130+(x-cx)*scale,top+116+(y-cy)*scale)
            if command == pe.COLOR_CHANGE:
                color+=1
            if command == pe.STITCH and last is not None:
                thread=pattern.get_thread_or_filler(color)
                draw.line((last,point),fill=f'#{thread.color&0xFFFFFF:06x}',width=2)
            last = point if command in (pe.STITCH,pe.JUMP) else None
        draw.text((left+12,top+229),entry['title'].replace(' · ', ' / '),font=font,fill='#263d3b')
        draw.text((left+12,top+250),f"{entry['widthMm']}x{entry['heightMm']} mm | {entry['stitches']} stitches",font=small,fill='#536563')
    image.save(ROOT/'docs/samples.png')


def main():
    assert version('pyembroidery') == '1.5.1', 'Pin pyembroidery==1.5.1 for reproducibility'
    OUT.mkdir(parents=True,exist_ok=True)
    h=heart(21)
    design('heart_running','하트 · 러닝',[(COLORS[0],[h])])
    design('heart_triple','하트 · 트리플',[(COLORS[0],[h])],triple=True)
    design('star_running','별 · 러닝',[(COLORS[2],[star()])])
    design('star_triple','별 · 트리플',[(COLORS[2],[star()])],triple=True)
    snow=[]
    for i in range(6):
        a=i*math.tau/6
        def rot(x,y):return (x*math.cos(a)-y*math.sin(a),x*math.sin(a)+y*math.cos(a))
        snow.append([rot(0,0),rot(350,0)])
        for x in (150,240):
            snow.extend([[rot(x-65,-65),rot(x,0),rot(x-65,65)]])
    design('snowflake','눈꽃',[(COLORS[3],snow)])
    design('mandala','만다라',[(COLORS[3],[polar(lambda t:220+110*math.cos(8*t)),circle(140),circle(70)])],triple=True)
    leaf=[(-320+640*i/120, -150*math.sin(math.pi*i/120)) for i in range(121)]
    leaf += [(320-640*i/120,150*math.sin(math.pi*i/120)) for i in range(121)]
    veins=[[(0,0),(-130,-100)],[(-100,0),(-220,65)],[(100,0),(220,-65)],[(0,0),(130,100)]]
    design('leaf','잎사귀',[(COLORS[1],[leaf,[(-320,0),(320,0)]]+veins)])
    sprig=[[(-200,300),(200,-300)]]
    for i in range(5):
        x,y=-140+i*70,210-i*105
        sprig.extend([[(x,y),(x-130,y-110),(x-30,y-130),(x,y)],[(x,y),(x+130,y+30),(x+100,y-50),(x,y)]])
    design('leaf_sprig','잎사귀 가지',[(COLORS[1],sprig)])
    cloud=[]
    for cx,cy,r,start,end in [(-260,20,80,math.pi/2,math.pi*1.5),(-160,-60,100,math.pi,math.tau),(100,-60,160,math.pi,math.tau),(260,20,80,-math.pi/2,math.pi/2)]:
        cloud.extend((cx+r*math.cos(start+(end-start)*i/60),cy+r*math.sin(start+(end-start)*i/60)) for i in range(61))
    cloud += [cloud[0]]
    design('cloud','구름',[(COLORS[3],[cloud])],triple=True)
    design('petal_ring','꽃잎 원형 패턴',[(COLORS[0],[polar(lambda t:240+90*math.cos(6*t)),circle(85)])],triple=True)
    square=[(-380,-380),(380,-380),(380,380),(-380,380),(-380,-380)]
    design('frame_square','사각 프레임',[(COLORS[1],[square])],triple=True)
    design('frame_circle','원형 프레임',[(COLORS[3],[circle(385),circle(350)])])
    design('frame_scallop','물결 프레임',[(COLORS[0],[polar(lambda t:350+30*math.cos(18*t))])],triple=True)
    for name,title,polygon,color in [('heart','하트 채움',heart(18),COLORS[0]),('star','별 채움',star(300,135),COLORS[2]),('circle','원 채움',circle(285),COLORS[3]),('diamond','마름모 채움',[(0,-300),(260,0),(0,300),(-260,0),(0,-300)],COLORS[1])]:
        design('fill_'+name,title,[(color,fill(polygon))],filled=True)
    for name in ['A','M','AB']:
        design('monogram_'+name.lower(),'모노그램 '+name,[(COLORS[3],hershey(name,350))],triple=True,license='CC0-1.0 AND LicenseRef-Hershey')
    for name,text in [('embroidery','자수'),('love','사랑')]:
        design('korean_'+name,text+' · 외곽선',[(COLORS[0],korean(text))])
    design('rainbow_cloud','구름과 무지개 · 3색',[(COLORS[3],[cloud]),(COLORS[0],[[(r*math.cos(t),r*math.sin(t)+50) for t in [math.pi+i*math.pi/150 for i in range(151)]] for r in [230]]),(COLORS[2],[[(180*math.cos(t),180*math.sin(t)+50) for t in [math.pi+i*math.pi/150 for i in range(151)]]])],triple=True)
    design('flower_garden','꽃 정원 · 4색',[(COLORS[1],[[(-180,300),(-180,0)],[(180,300),(180,-100)]]),(COLORS[0],[[(x-180,y) for x,y in polar(lambda t:110+35*math.cos(5*t))]]),(COLORS[3],[[(x+180,y-100) for x,y in polar(lambda t:110+35*math.cos(6*t))]]),(COLORS[2],[circle(40,-180,0),circle(40,180,-100)])],triple=True)
    design('geometry_four_colors','기하학 · 4색',[(COLORS[0],[[(x-180,y-180) for x,y in heart(8)]]),(COLORS[1],[circle(135,180,-180)]),(COLORS[2],[[(x-180,y+180) for x,y in star(140,65)]]),(COLORS[3],[[(x/3+180,y/3+180) for x,y in square]])],triple=True)
    upstream('spiralbox','커뮤니티 · 나선 사각형','4e6ca07e33132787c2211eb676443e8c9fc6629993e37be7b886bde21df3066c')
    assert len(PACK) >= 20
    (OUT/'manifest.json').write_text(json.dumps({'version':1,'hoopMm':[100,100],'samples':PACK},ensure_ascii=False,indent=2)+'\n')
    sheet()
    print('Generated',len(PACK),'PES samples and docs/samples.png')


if __name__ == '__main__':
    main()
