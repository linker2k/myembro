#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Public OFL Hangul input preparation only. Measurements come from existing model port."""
import argparse,hashlib,json,pathlib,time,urllib.request
from PIL import Image,ImageDraw,ImageFont
CHARS='뻘뿔뻤뼈뼛뺏빽뽐뽕뾰쀼쐐쐤쒔쫄쫓쭘쭝쯤쯧짧찮많닭값삶앉읽핥읊'
IDS=['gowun-dodum','nanum-gothic','nanum-myeongjo','gothic-a1','orbit']
p=argparse.ArgumentParser();p.add_argument('out',type=pathlib.Path);p.add_argument('--catalog',type=pathlib.Path,default=pathlib.Path('/tmp/myembro-ttf-fill-sweep-reduced/fonts.json'));p.add_argument('--prepare',action='store_true');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
assert len(CHARS)==30 and len(set(CHARS))==30
sha=lambda b:hashlib.sha256(b).hexdigest()
if not a.prepare:
 fonts={f['id']:f for f in json.loads(a.catalog.read_text())['fonts']}
 frozen=[]
 for id in IDS:
  f=fonts[id];data=pathlib.Path(f['path']).read_bytes();assert sha(data)==f['sha256']
  target=a.out/'fonts'/f'{id}.ttf';target.parent.mkdir(exist_ok=True);target.write_bytes(data)
  license_url=f['source'].rsplit('/',1)[0]+'/OFL.txt'
  license_data=urllib.request.urlopen(license_url,timeout=30).read();assert b'SIL OPEN FONT LICENSE' in license_data
  (a.out/'fonts'/f'{id}-OFL.txt').write_bytes(license_data)
  frozen.append(dict(f,path=str(target),license_url=license_url,license_sha256=sha(license_data),weight=400))
 (a.out/'fonts.json').write_text(json.dumps(frozen,indent=2)+'\n')
 (a.out/'fonts.tsv').write_text(''.join(f"{f['id']}\t{f['path']}\n" for f in frozen))
 (a.out/'characters.txt').write_text(CHARS)
 print('PUBLIC_FONTS 5 / GLYPHS 30 / weight Regular400')
else:
 start=time.perf_counter();widths={}
 for line in (a.out/'widths.tsv').read_text().splitlines()[1:]:
  font,code,width,samples=line.split('\t');widths[font,int(code)]=float(width)
 cases=[]
 for f in json.loads((a.out/'fonts.json').read_text()):
  font=ImageFont.truetype(f['path'],128)
  for ch in CHARS:
   box=font.getbbox(ch);pad=8;im=Image.new('1',(box[2]-box[0]+16,box[3]-box[1]+16),1)
   ImageDraw.Draw(im).text((pad-box[0],pad-box[1]),ch,font=font,fill=0)
   w,h=im.size;pixels=list(im.convert('L').getdata())
   pgm='P2\n%d %d\n255\n'%(w,h)+'\n'.join(' '.join(map(str,pixels[y*w:(y+1)*w])) for y in range(h))+'\n'
   for height in [10,15,20]:
    id=f"{f['id']}-U{ord(ch):04X}-{height}mm";directory=a.out/'inputs'/id;directory.mkdir(parents=True,exist_ok=True)
    (directory/'input.pgm').write_text(pgm)
    meta=dict(text=ch,font=f['id'],weight=400,height_mm=height,pixels_per_mm=(box[3]-box[1])/height,font_sha256=f['sha256'],raster_sha256=sha(pgm.encode()),width_mm=widths[f['id'],ord(ch)]*height/15,width_method='model GlyphStrokeWidths median at15mm, boundary0.1mm, scaled proportionally; per-glyph constant; not auto style policy')
    (directory/'input.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n');cases.append(dict(id=id,input=str(directory),width_mm=meta['width_mm']))
 (a.out/'cases.json').write_text(json.dumps(cases,indent=2)+'\n');(a.out/'prepare-seconds.txt').write_text(str(time.perf_counter()-start))
 print('PREPARED',len(cases))
