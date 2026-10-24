#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compile unchanged upstream C with real GLib; no package installs or reference-algorithm rewrites."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version

ROOT=Path(__file__).resolve().parents[2]
COMMIT='1a232b27818a46a05cd25edec09e2f77c4e9ac01'
THIN_SHA='29a976bc15799ca26462e8b06636bebb689d4a6817d58e9d1040fd20b3c4ccac'

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def pgm(image):
    return 'P2\n%d %d\n255\n'%image.size + '\n'.join(' '.join(str(v) for v in list(image.convert('L').getdata())[y*image.width:(y+1)*image.width]) for y in range(image.height))+'\n'
def read_pbm(text):
    tokens=text.split();assert tokens[0]=='P1'
    w,h=map(int,tokens[1:3]);values=list(map(int,tokens[3:]));assert len(values)==w*h and set(values)<={0,1}
    return w,h,values

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path)
    parser.add_argument('--verify',action='store_true',help='Compare original C input/output bytes against committed goldens')
    parser.add_argument('--glib-prefix',type=Path,help='Extracted official dev headers, without installing; otherwise pkg-config')
    parser.add_argument('--output',type=Path,default=Path('/tmp/myembro-port-out/editor/autotrace'))
    args=parser.parse_args();source=args.source.resolve();out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    assert subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()==COMMIT
    assert not subprocess.check_output(['git','-C',str(source),'status','--porcelain'],text=True).strip()
    assert sha(source/'src/thin-image.c')==THIN_SHA
    if args.glib_prefix:
        prefix=args.glib_prefix.resolve()
        flags=['-I'+str(prefix/'usr/include/glib-2.0'),'-I'+str(prefix/'usr/lib/x86_64-linux-gnu/glib-2.0/include'),
               '/usr/lib/x86_64-linux-gnu/libgobject-2.0.so.0','/usr/lib/x86_64-linux-gnu/libglib-2.0.so.0']
    else:
        import shlex
        flags=shlex.split(subprocess.check_output(['pkg-config','--cflags','--libs','gobject-2.0'],text=True))
    exe=out/'thinning-oracle'
    cmd=['cc','-std=c99','-O0','-ffp-contract=off','-I'+str(source/'src'),str(ROOT/'tools/autotrace/thinning_oracle.c'),
         *[str(source/'src'/name) for name in ['thin-image.c','color.c','exception.c']],*flags,'-o',str(exe)]
    subprocess.run(cmd,check=True)
    fixtures=[]
    def drawing(name,size,paint):
        image=Image.new('1',size,1);paint(ImageDraw.Draw(image));fixtures.append((name,image))
    drawing('bar',(17,17),lambda d:d.rectangle((3,6,13,10),fill=0))
    drawing('tee',(17,17),lambda d:(d.rectangle((3,3,13,6),fill=0),d.rectangle((7,3,10,14),fill=0)))
    drawing('cross',(21,21),lambda d:(d.line((3,3,17,17),fill=0,width=5),d.line((3,17,17,3),fill=0,width=5)))
    drawing('loop',(21,21),lambda d:(d.rectangle((3,3,17,17),fill=0),d.rectangle((7,7,13,13),fill=1)))
    font_path=ROOT/'app/src/main/res/font/noto_sans_kr.ttf'
    font=ImageFont.truetype(str(font_path),64)
    axes=font.get_variation_axes() if hasattr(font,'get_variation_axes') else []
    if axes:
        values=[a['default'] for a in axes]
        for i,axis in enumerate(axes):
            if axis['name'] in [b'Weight','Weight']:values[i]=700
        font.set_variation_by_axes(values)
    drawing('hangul-han',(96,96),lambda d:d.text((16,2),'한',font=font,fill=0))
    report=[]
    for name,image in fixtures:
        raw=pgm(image);(out/(name+'.pgm')).write_text(raw);image.save(out/(name+'.input.png'))
        masks=[]
        for planes in [1,3]:
            first=subprocess.check_output([str(exe),str(planes)],input=raw,text=True)
            second=subprocess.check_output([str(exe),str(planes)],input=raw,text=True)
            assert first==second, 'Non-deterministic original output'
            w,h,bits=read_pbm(first)
            source_bits=[1-int(v>0) for v in image.convert('L').getdata()]
            assert (w,h)==image.size and 0<sum(bits)<=sum(source_bits)
            assert all(not b or src for b,src in zip(bits,source_bits)), 'Original added foreground outside input'
            result_image=Image.new('1',(w,h));result_image.putdata([0 if b else 255 for b in bits])
            again=subprocess.check_output([str(exe),str(planes)],input=pgm(result_image),text=True)
            assert again==first, 'Original thinning not idempotent'
            path=out/f'{name}.p{planes}.pbm';path.write_text(first)
            if args.verify:
                committed=ROOT/'engine/src/test/resources/autotrace/thinning'
                assert (committed/(name+'.pgm')).read_text()==raw, name+' input changed'
                assert (committed/f'{name}.p{planes}.pbm').read_text()==first, name+' original output changed'
            result_image.resize((w*6,h*6),Image.Resampling.NEAREST).save(out/f'{name}.p{planes}.png')
            masks.append(first)
            report.append({'id':name,'planes':planes,'width':w,'height':h,'inputForeground':sum(source_bits),
                           'outputForeground':sum(bits),'repeatExact':True,'idempotent':True,
                           'inputSha256':sha(out/(name+'.pgm')),'outputSha256':sha(path)})
        assert masks[0]==masks[1],name+' 1/3 channel difference'
        print(name,'ORIGINAL_GRAY_RGB_EXACT','foreground',sum(read_pbm(masks[0])[2]))
    sources={str(p.relative_to(source)):sha(p) for p in [source/'src/thin-image.c',source/'src/thin-image.h',
             source/'src/color.c',source/'src/exception.c',source/'src/autotrace.h',source/'src/types.h']}
    provenance={'upstream':'AutoTrace 0.31.10','commit':COMMIT,'sourceFiles':sources,'compileCommand':cmd,
                'compiler':subprocess.check_output(['cc','--version'],text=True).splitlines()[0],
                'pillowVersion':pillow_version,'fontPath':'app/src/main/res/font/noto_sans_kr.ttf','fontSha256':sha(font_path),
                'fontWeight':700 if axes else 'font file default','fixturePurpose':'Original synthetic shapes and OFL Hangul raster; no personal design',
                'algorithm':'Unmodified upstream thin_image only; no pixel-path extraction, spline fitting, Kotlin port or PES yet',
                'callerSha256':sha(ROOT/'tools/autotrace/thinning_oracle.c'),
                'generatorSha256':sha(Path(__file__)),
                'runtimeLibraries':{name:sha(Path('/usr/lib/x86_64-linux-gnu')/name) for name in
                    ['libglib-2.0.so.0','libgobject-2.0.so.0']} if args.glib_prefix else 'pkg-config gobject-2.0',
                'cases':report}
    (out/'manifest.json').write_text(json.dumps(provenance,indent=2)+'\n')
    for name in ['COPYING','COPYING.LIB']:shutil.copyfile(source/name,out/name)
    assert sha(source/'src/thin-image.c')==THIN_SHA
    if args.verify:print('COMMITTED_GOLDENS_EXACT 10/10')
    print('ORIGINAL_THINNING_GOLDENS 10/10; REPEAT 10/10; IDEMPOTENT 10/10; GRAY_RGB 5/5')

if __name__=='__main__':main()
