#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate the KRSAMP7 Bold fill-only physical trial; public bundled font only."""
import argparse,json,os,subprocess
from pathlib import Path
from make_kr_sewtest import REPO,ORACLE_PY

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output',type=Path,default=Path('/tmp/myembro-sewtest/kr7'))
p.add_argument('--summarize-existing',action='store_true')
a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
env=dict(os.environ,JAVA_HOME='/home/gosh2/.jdks/jdk-17.0.20.1+1')
if not a.summarize_existing:
    if (out/'KRSAMP7.PES').exists():raise SystemExit('Never overwrite an existing physical trial name.')
    from fontTools.ttLib import TTFont
    from fontTools.varLib.instancer import instantiateVariableFont
    for weight in [400,700]:
        font=TTFont(REPO/'app/src/main/res/font/noto_sans_kr.ttf')
        instantiateVariableFont(font,{'wght':weight},inplace=True);font.save(out/f'NotoSansKR-{weight}.ttf')
    subprocess.run(['./gradlew',':engine:koreanTtfSweepClasspath',f'-PkoreanTtfSweepClasspath={out}/classpath.txt','--console=plain'],cwd=REPO,env=env,check=True)
    subprocess.run([env['JAVA_HOME']+'/bin/java','-Xmx3g','-Djava.awt.headless=true','-cp',(out/'classpath.txt').read_text().strip(),
        'io.github.linker2k.myembro.engine.lettering.KoreanTatamiTrial','small',str(out/'NotoSansKR-700.ttf'),str(out/'NotoSansKR-400.ttf'),str(out)],check=True)
with (out/'gate.log').open('w') as log:
    subprocess.run(['/tmp/myembro-quality-gate/quality-gate','check','--style','fill','--hoop-bounds-mm','0','0','60','60','--json',str(out/'gate.json'),str(out/'KRSAMP7.PES')],stdout=log,stderr=subprocess.STDOUT,check=True)
rows=json.loads((out/'widths.json').read_text())
lines=['# KRSAMP7 — 정민, Noto Sans KR Bold700, fill only','',
       'One black40wt thread; top/middle/bottom=8/10/12mm; no running outlines or stitched labels.',
       'Rows begin at y=0/11/24mm. Physical size is preserved.','',
       '| Font | height mm | 정 min mm | 민 min mm | 정민 min mm | 정 P10 mm | 민 P10 mm |',
       '|---|---:|---:|---:|---:|---:|---:|']
for r in rows:
    lines.append(f"| {'Bold700' if r['weight']==700 else 'Regular400 reference (not stitched)'} | {r['height_mm']:.0f} | {r['정']['minimum_sampled_mm']:.6f} | {r['민']['minimum_sampled_mm']:.6f} | {r['정민']['minimum_sampled_mm']:.6f} | {r['정']['p10_mm']:.6f} | {r['민']['p10_mm']:.6f} |")
lines+=['','Minimum=sampled interior Voronoi diameter; boundary sampling0.1mm, same method as KRSAMP1.',
        'Corners/tips affect minima; these values do not certify a minimum stroke width. W unknown.',
        'Regular400 at15mm is a controlled reference for S23. The exact S23 effective font variation',
        'was not recorded, so identity with that physical file is unconfirmed. No private S23 file used.',
        '',(out/'checks.txt').read_text().strip(),(out/'gate.log').read_text().strip(),'']
(out/'README.md').write_text('\n'.join(lines))
# Preview annotations are outside the PES; all stitch coordinates retain their physical scale.
from PIL import Image,ImageDraw,ImageFont
code="import pyembroidery as p,json,sys;print(json.dumps(p.read_pes(sys.argv[1]).stitches))"
events=json.loads(subprocess.check_output([str(ORACLE_PY),'-c',code,str(out/'KRSAMP7.PES')],text=True))
image=Image.new('RGB',(1000,850),'white');draw=ImageDraw.Draw(image)
font=ImageFont.truetype(str(out/'NotoSansKR-700.ttf'),22)
draw.text((30,15),'KRSAMP7 | Bold700 | 8 / 10 / 12mm | black40wt',font=font,fill='black')
x=y=0
for nx,ny,command in events:
    if command&255 in (0,1):draw.line([(30+x*1.8,80+y*1.8),(30+nx*1.8,80+ny*1.8)],fill='black' if command&255==0 else '#dd6666',width=1)
    x,y=nx,ny
for r,offset in zip(rows[:3],[0,110,240]):
    draw.text((480,80+offset*1.8),f"{r['height_mm']:.0f}mm | min 정 {r['정']['minimum_sampled_mm']:.4f}",font=font,fill='black')
    draw.text((480,112+offset*1.8),f"min 민 {r['민']['minimum_sampled_mm']:.4f}mm",font=font,fill='black')
draw.text((30,790),'Black=stitch; red=jump. Labels are not stitched. Sampled minima; W unknown.',font=font,fill='black')
image.save(out/'overview.png')
print((out/'README.md').read_text());print('ARTIFACTS',out)
