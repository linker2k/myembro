#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Local public-font trial; no remote code, font download, or application-policy override."""
import argparse,hashlib,json,os,shutil,subprocess
from pathlib import Path

REPO=Path(__file__).resolve().parents[1]
ORACLE_PY=Path('/tmp/claude-1000/-home-gosh2-workspace-hoonyjung-myembro/8141f70b-3d10-4aef-87f7-55b03ccb7225/scratchpad/venv/bin/python')

def render(out,oracle_python):
    from PIL import Image,ImageDraw,ImageFont
    files=sorted((out/'parts').glob('KRSAMP*.PES'))+[out/f'KROPT{h}.PES' for h in [15,20,25]]
    decode="""import pyembroidery as p,json,sys
paths=json.load(sys.stdin)
print(json.dumps({f:[[s[0],s[1],s[2]&255] for s in p.read_pes(f).stitches] for f in paths}))
"""
    events=json.loads(subprocess.check_output([str(oracle_python),'-c',decode],input=json.dumps(list(map(str,files))),text=True))
    rows=json.loads((out/'measurements.json').read_text())
    image=Image.new('RGB',(2100,1700),'#faf8f2');draw=ImageDraw.Draw(image)
    font=ImageFont.truetype(str(out/'NotoSansKR-Bold.ttf'),23);small=ImageFont.truetype(str(out/'NotoSansKR-Bold.ttf'),18)
    draw.text((25,15),'정민 | Noto Sans KR Bold700 | one black 40wt thread per file',font=font,fill='black')
    draw.text((25,55),'Same physical scale. Black: sewing; red: jump thread. Labels are NOT stitched. W unknown.',font=small,fill='black')
    for r in rows:
        if r['mode']=='source-underpath-off':continue
        row={'baseline':0,'running':1,'source-underpath-on':2}[r['mode']];col=[15,20,25].index(int(r['height_mm']))
        name=r['file'] if row<2 else f"KROPT{int(r['height_mm'])}.PES"
        x=col*700+20;y=row*520+120
        draw.text((x,y),f"{name} | {r['height_mm']:.0f}mm | {r['mode']}",font=font,fill='black')
        draw.text((x,y+36),f"width min(sampled) {r['minimum_sampled_width_mm']:.3f} / P10 {r['p10_sampled_width_mm']:.3f}mm",font=small,fill='black')
        draw.text((x,y+65),f"{r['stitches']} stitches / {r['jumps']} jumps / {r['jump_length_mm']:.1f}mm travel",font=small,fill='black')
        prior=(0,0)
        for a,b,c in events[str((out/'parts'/name) if row<2 else (out/name))]:
            q=(x+a,y+110+b);p=(x+prior[0],y+110+prior[1])
            if c in [0,1]:draw.line([p,q],fill='black' if c==0 else '#d45c55',width=1)
            prior=(a,b)
    image.save(out/'overview.png')


def summarize(out):
    rows=json.loads((out/'measurements.json').read_text())
    lines=['# Korean bold trial — Noto Sans KR Bold700','',
        'KRSAMP1.PES is one89.9x89.9mm sampler: exact sizes, fill left, running right. W unknown. parts/ holds generation inputs.',
        'Six exact-size nonoverlapping rectangles total4643.24mm² >3600mm²: no six-item rectangle sampler fits60x60.',
        'Voronoi minimum is sampled and tip/corner sensitive, not a certified minimum stroke width. P10 is separate.',
        '', '| File | height | route | sampled min / P10 mm | jumps | travel mm | trims |', '|---|---:|---|---:|---:|---:|---:|']
    for r in rows:
        lines.append(f"| {r['file']} | {r['height_mm']} | {r['mode']} | {r['minimum_sampled_width_mm']:.4f} / {r['p10_sampled_width_mm']:.4f} | {r['jumps']} | {r['jump_length_mm']:.2f} | {r['trims']} |")
    lines+=['','Source routing: Ink/Stitch v3.3.0 fill_stitch.py to_stitch_groups: carry previous endpoint, sort from initial previous endpoint, target nearest point of next polygon. Underpath remains true by default.',
        'No production route/default/threshold has been changed. KROPT15/20/25 are aliases of the upstream-routing comparison, for optional physical comparison.',
        'Bundled variable font wght axis default=100; trial is a local fontTools static instance wght=700. Android effective variation was not measured.',
        'P1/P2/P3/P4/P5/P8 asserted after PES read-back; half_stitch comes from shared source locks; independent gate reports density/functional locks.']
    (out/'README.md').write_text('\n'.join(lines)+'\n')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=Path('/tmp/myembro-sewtest/kr'))
    parser.add_argument('--oracle-python',type=Path,default=ORACLE_PY)
    parser.add_argument('--render-existing',action='store_true')
    a=parser.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    if not a.render_existing:
        if any(out.glob('KRSAMP*.PES')):raise SystemExit('Existing trial names: use a fresh output and NEW physical trial names, never overwrite a sewn trial.')
        from fontTools.ttLib import TTFont
        from fontTools.varLib.instancer import instantiateVariableFont
        source=REPO/'app/src/main/res/font/noto_sans_kr.ttf'
        font=TTFont(source);instantiateVariableFont(font,{'wght':700},inplace=True);font.save(out/'NotoSansKR-Bold.ttf')
        env=dict(os.environ,JAVA_HOME='/home/gosh2/.jdks/jdk-17.0.20.1+1')
        subprocess.run(['./gradlew',':engine:koreanTtfSweepClasspath',f'-PkoreanTtfSweepClasspath={out}/classpath.txt'],cwd=REPO,env=env,check=True)
        subprocess.run([env['JAVA_HOME']+'/bin/java','-Xmx3g','-Djava.awt.headless=true','-cp',(out/'classpath.txt').read_text().strip(),
            'io.github.linker2k.myembro.engine.lettering.KoreanTatamiTrial',str(out/'NotoSansKR-Bold.ttf'),str(out)],cwd=REPO,check=True)
    if not (out/'parts').exists():
        (out/'parts').mkdir()
        for f in out.glob('KRSAMP*.PES'):shutil.copyfile(f,out/'parts'/f.name)
    env=dict(os.environ,JAVA_HOME='/home/gosh2/.jdks/jdk-17.0.20.1+1')
    subprocess.run([env['JAVA_HOME']+'/bin/java','-cp',(out/'classpath.txt').read_text().strip(),'io.github.linker2k.myembro.engine.lettering.KoreanTatamiTrial','compose',str(out)],check=True)
    for h in [15,20,25]:shutil.copyfile(out/f'COMPARE-{h}-source-underpath-on.PES',out/f'KROPT{h}.PES')
    summarize(out);render(out,a.oracle_python)
    files=sorted((out/'parts').glob('KRSAMP*.PES'))+[out/f'KROPT{h}.PES' for h in [15,20,25]]
    with (out/'gate.log').open('w') as log:
        subprocess.run(['/tmp/myembro-quality-gate/quality-gate','check','--style','fill','--hoop-bounds-mm','0','0','60','60','--json',str(out/'gate.json'),*map(str,files)],stdout=log,stderr=subprocess.STDOUT,check=True)
    with (out/'sampler-gate.log').open('w') as log:
        subprocess.run(['/tmp/myembro-quality-gate/quality-gate','check','--style','fill','--hoop-bounds-mm','0','0','100','100','--json',str(out/'sampler-gate.json'),str(out/'KRSAMP1.PES')],stdout=log,stderr=subprocess.STDOUT,check=True)
    files.append(out/'KRSAMP1.PES')
    source=REPO/'app/src/main/res/font/noto_sans_kr.ttf'
    provenance=dict(font_source='bundled OFL-1.1 Noto Sans KR; existing THIRD_PARTY_NOTICES.md',
        source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),weight=700,threshold_W_mm=None,
        measurement='JTS1.20 boundary densification0.1mm; interior Voronoi vertices; diameter=2*distance to continuous ink boundary; sampled minimum includes tips',
        engine_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),
        files={str(f.relative_to(out)):hashlib.sha256(f.read_bytes()).hexdigest() for f in files})
    (out/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    print((out/'gate.log').read_text());print('ARTIFACTS',out)
