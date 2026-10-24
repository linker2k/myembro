#!/usr/bin/env python3
"""Prepare a public OFL Noto Sans KR 400 text raster. No embroidery algorithm or native source execution."""
import argparse,hashlib,json,pathlib,math
from PIL import Image,ImageDraw,ImageFont,__version__
p=argparse.ArgumentParser();p.add_argument('--out',type=pathlib.Path,default=pathlib.Path('/tmp/myembro-sewtest/krs'));p.add_argument('--text',default='정민');p.add_argument('--height-mm',type=float,default=15.0);a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[2];fontpath=root/'app/src/main/res/font/noto_sans_kr.ttf'
f=ImageFont.truetype(str(fontpath),128);axes=f.get_variation_axes();values=[axis['default'] for axis in axes]
for i,axis in enumerate(axes):
 if axis['name'] in (b'Weight','Weight'):values[i]=400
f.set_variation_by_axes(values)
box=f.getbbox(a.text);pad=8
im=Image.new('1',(box[2]-box[0]+pad*2,box[3]-box[1]+pad*2),1)
ImageDraw.Draw(im).text((pad-box[0],pad-box[1]),a.text,font=f,fill=0)
a.out.mkdir(parents=True,exist_ok=True);im.save(a.out/'input.png')
pixels=list(im.convert('L').getdata());w,h=im.size
pgm='P2\n%d %d\n255\n'%(w,h)+'\n'.join(' '.join(map(str,pixels[y*w:(y+1)*w])) for y in range(h))+'\n'
(a.out/'input.pgm').write_text(pgm)
meta={'text':a.text,'font':'Noto Sans KR','weight':400,'height_mm':a.height_mm,'raster_font_size_px':128,'pixels_per_mm':(box[3]-box[1])/a.height_mm,'glyph_ink_bbox_px':box,'pad_px':pad,'font_sha256':hashlib.sha256(fontpath.read_bytes()).hexdigest(),'raster_sha256':hashlib.sha256(pgm.encode()).hexdigest(),'pillow_version':__version__,'binary_raster':True,'note':'Raster resolution is sampling only; glyph ink height is normalized to requested physical mm. No inferred stroke width.'}
# Explicit font-layout ownership. Only modern Hangul trials: no ligature ownership guessing.
if all('가' <= ch <= '힣' for ch in a.text):
 bounds=[]
 for i,ch in enumerate(a.text):
  b=f.getbbox(ch);x=pad-box[0]+f.getlength(a.text[:i]);y=pad-box[1]
  bounds.append(dict(character_index=i,codepoint=ord(ch),left=math.floor(x+b[0]),top=math.floor(y+b[1]),right=math.ceil(x+b[2]),bottom=math.ceil(y+b[3])))
 meta['glyph_bounds_px']=bounds
(a.out/'input.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n');print(json.dumps(meta,ensure_ascii=False))
