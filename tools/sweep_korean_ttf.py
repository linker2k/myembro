#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Strict Hangul stroke tables ported from juunini/count-korean-stroke-js
# @5f595640db4a059caf0d535fc72a4751a706e077 (MIT), Copyright (c) 2025 Juunini.
# Full notice: tools/licenses/count-korean-stroke-MIT.txt. Original JS was read only, never executed.
"""Reduced Korean TTF sweep: 30 heavy-stroke syllables x 5 anchors, then failing-font boundaries."""
import argparse
import collections
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import time

REPO = Path(__file__).resolve().parents[1]
ANCHORS = [5.0, 26.25, 47.5, 68.75, 90.0]
L = [1,2,1,2,4,3,3,4,8,2,4,1,2,4,3,2,3,4,3]
V = [2,3,3,4,2,3,3,4,2,4,5,3,3,2,4,5,3,3,1,2,1]
T = [0,1,2,3,1,3,4,2,3,4,6,7,5,6,7,6,3,4,6,2,4,1,2,3,2,3,4,3]


def strokes(cp):
    assert 0xAC00 <= cp <= 0xD7A3
    n = cp - 0xAC00
    return L[n // 588] + V[n % 588 // 28] + T[n % 28]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def runtime_sha(path):
    path=Path(path)
    if path.is_file(): return sha(path)
    digest=hashlib.sha256()
    for f in sorted(path.rglob('*')):
        if f.is_file():
            digest.update(str(f.relative_to(path)).encode());digest.update(f.read_bytes())
    return digest.hexdigest()


def validate_runtime(root, identity):
    actual=(root/'frozen-classpath.txt').read_text().strip().split(os.pathsep)
    assert actual==[e['path'] for e in identity['runtime']], 'Frozen classpath changed'
    for entry in identity['runtime']:
        assert runtime_sha(entry['path'])==entry['sha256'], f"Runtime changed: {entry['path']}"


def write(path, data):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    temporary.replace(path)


def command(root):
    java = Path(os.environ.get('JAVA_HOME', '/home/gosh2/.jdks/jdk-17.0.20.1+1')) / 'bin/java'
    return [str(java), '-Xmx4g', '-Djava.awt.headless=true', '-cp', (root / 'frozen-classpath.txt').read_text().strip(),
            'io.github.linker2k.myembro.engine.lettering.KoreanTtfSweep']


def prepare(root):
    root.mkdir(parents=True, exist_ok=True)
    if any((root/'cases').glob('*.csv')):
        raise RuntimeError('Existing result checkpoints: use run to resume the frozen runtime, or a new --root to prepare new code.')
    fonts = json.loads((root / 'fonts.json').read_text())
    runtime = root / 'runtime'
    runtime.mkdir(exist_ok=True)
    frozen_fonts = runtime / 'fonts'
    frozen_fonts.mkdir(exist_ok=True)
    for font in fonts['fonts']:
        original = Path(font['path'])
        assert sha(original) == font['sha256'], font['id']
        target = frozen_fonts / (font['id'] + original.suffix)
        shutil.copyfile(original, target)
        font['path'] = str(target)
    write(root / 'frozen-fonts.json', fonts)
    subprocess.run(['./gradlew', ':engine:koreanTtfSweepClasspath', f'-PkoreanTtfSweepClasspath={root / "classpath.txt"}', '--console=plain'], cwd=REPO, check=True)
    classpath = []
    cp_manifest = []
    for i, entry in enumerate((root / 'classpath.txt').read_text().strip().split(os.pathsep)):
        source = Path(entry)
        if not source.exists():
            continue
        if str(source).startswith(str(REPO / 'engine/build')):
            target = runtime / f'cp-{i}'
            if source.is_dir():
                if target.exists(): shutil.rmtree(target)
                shutil.copytree(source, target)
                digest = hashlib.sha256()
                for f in sorted(target.rglob('*')):
                    if f.is_file():
                        digest.update(str(f.relative_to(target)).encode()); digest.update(f.read_bytes())
                checksum = digest.hexdigest()
            else:
                shutil.copyfile(source, target); checksum = sha(target)
            classpath.append(str(target)); cp_manifest.append(dict(path=str(target), sha256=checksum))
        else:
            assert source.is_file(), source
            classpath.append(str(source)); cp_manifest.append(dict(path=str(source), sha256=sha(source)))
    (root / 'frozen-classpath.txt').write_text(os.pathsep.join(classpath) + '\n')
    subprocess.run(command(root) + ['coverage', str(root / 'frozen-fonts.json'), str(root / 'coverage.json')], check=True)
    common = json.loads((root / 'coverage.json').read_text())
    chosen = sorted(common, key=lambda cp: (-strokes(cp), cp))[:30]
    assert len(chosen) == 30
    write(root / 'characters.json', dict(selection='Top 30 strict-writing stroke sums among Hangul supported by every pinned font; ties codepoint ascending',
        source='https://github.com/juunini/count-korean-stroke-js/blob/5f595640db4a059caf0d535fc72a4751a706e077/index.ts',
        source_license='MIT; strict=true tables; no claim of a unique handwriting convention',
        common_supported_syllables=len(common), tables=dict(leading=L, vowel=V, trailing=T),
        characters=[dict(codepoint=cp, text=chr(cp), strokes=strokes(cp)) for cp in chosen]))
    identity = dict(commit=subprocess.check_output(['git','rev-parse','HEAD'], cwd=REPO, text=True).strip(),
                    fonts_sha256=sha(root/'frozen-fonts.json'), selection_sha256=sha(root/'characters.json'),
                    runtime=cp_manifest, workers_default=8, font_count=len(fonts['fonts']), characters=30,
                    min_mm=5, max_mm=90, coarse_heights_mm=ANCHORS, expected_coarse_cases=len(fonts['fonts'])*30*5,
                    boundary='JVM AWT extracts the same pinned TTF/OTF files at 1000px, flattens at0.2px; cap reference=자 as app; Android extraction is not executed',
                    pipeline='LetteringEngine.fromContours FILL -> PES v6 -> SewoutCheck.checkPes(APP_GENERATED), default SE400/100mm/frozen POLICY',
                    source_files_sha256={str(p.relative_to(REPO)):sha(p) for p in [REPO/'app/src/main/assets/fonts/catalog.json',Path(__file__).resolve(), REPO/'engine/src/test/kotlin/io/github/linker2k/myembro/engine/lettering/KoreanTtfSweep.kt', REPO/'engine/src/main/kotlin/io/github/linker2k/myembro/engine/sewout/SewoutCheck.kt', REPO/'engine/src/main/kotlin/io/github/linker2k/myembro/engine/sewout/SewoutModel.kt']},
                    identity_created_at=time.time())
    write(root / 'identity.json', identity)
    print('PREPARED', len(fonts['fonts']), 'fonts;', len(chosen), 'characters;', identity['expected_coarse_cases'], 'cases')

def initialize(root, cache):
    root.mkdir(parents=True, exist_ok=True)
    fonts=[]
    for id, name in [('gowun-batang','gowun_batang.ttf'),('jua','jua_regular.ttf'),('noto-sans-kr','noto_sans_kr.ttf')]:
        path=REPO/'app/src/main/res/font'/name
        fonts.append(dict(id=id,path=str(path),sha256=sha(path),source='bundled app font; OFL-1.1 in THIRD_PARTY_NOTICES.md'))
    catalog=json.loads((REPO/'app/src/main/assets/fonts/catalog.json').read_text())
    for font in catalog['fonts']:
        if font['kind']!='outline-ttf' or 'ko' not in font['scripts']: continue
        path=cache/(font['id']+'.ttf')
        assert sha(path)==font['sha256'], f"Missing or mismatched pinned font: {path}"
        fonts.append(dict(id=font['id'],path=str(path),sha256=font['sha256'],source=font['url'],license=font['license']))
    write(root/'fonts.json',dict(fonts=fonts))


def rows_at(root, font_id, height):
    path=root/'cases'/f'{font_id}--{float(height)}mm.csv'
    selected={c['codepoint'] for c in json.loads((root/'characters.json').read_text())['characters']}
    rows={}
    if path.exists():
        with path.open() as stream:
            for row in csv.DictReader(stream):
                try:
                    cp=int(row['codepoint'])
                    if cp in selected and row['verdict'] in ('PASS','BLOCK') and row.get('reasons') is not None:
                        rows[cp]=row
                except (ValueError,KeyError,TypeError): pass
    return rows


def refinement_heights(anchor_results):
    """Interior failures trigger 1mm refinement of PASS/BLOCK transition intervals only."""
    if not any(anchor_results.get(h)=='BLOCK' for h in ANCHORS[1:-1]): return []
    values=set()
    for left,right in zip(ANCHORS,ANCHORS[1:]):
        if {anchor_results.get(left),anchor_results.get(right)}=={'PASS','BLOCK'}:
            values.update(range(math.ceil(left),math.floor(right)+1))
    return sorted(float(v) for v in values if float(v) not in ANCHORS)


def sampled_run(states):
    """Widest PASS sample interval; BLOCK/PENDING split; ties prefer lower start."""
    best=[];current=[]
    for height, state in sorted(states.items()):
        current=current+[height] if state=='PASS' else []
        if current and (not best or current[-1]-current[0]>best[-1]-best[0]): best=current[:]
    return dict(min_mm=best[0],max_mm=best[-1],sampled_heights=len(best)) if best else None


def summarize(root, started=None, final=False):
    identity=json.loads((root/'identity.json').read_text())
    fonts=json.loads((root/'frozen-fonts.json').read_text())['fonts']
    count=len(json.loads((root/'characters.json').read_text())['characters'])
    reasons=collections.Counter();failures=[];profiles=[];done=blocked=expected=coarse_done=coarse_blocked=omitted=0
    for font in fonts:
        anchors={h:rows_at(root,font['id'],h) for h in ANCHORS}
        def state(rows, fail_fast=False):
            if fail_fast and any(r['verdict']=='BLOCK' for r in rows.values()): return 'BLOCK'
            if len(rows)!=count: return 'PENDING'
            return 'BLOCK' if any(r['verdict']=='BLOCK' for r in rows.values()) else 'PASS'
        anchor_states={h:state(rows) for h,rows in anchors.items()}
        refine=refinement_heights(anchor_states)
        heights=sorted(set(ANCHORS+refine));states={};font_done=font_blocked=font_omitted=0;font_reasons=collections.Counter()
        expected+=len(heights)*count
        for h in heights:
            rows=anchors.get(h) if h in anchors else rows_at(root,font['id'],h)
            states[h]=state(rows,fail_fast=h not in ANCHORS);done+=len(rows);font_done+=len(rows)
            if h not in ANCHORS and states[h]=='BLOCK':
                omitted+=count-len(rows);font_omitted+=count-len(rows)
            if h in ANCHORS: coarse_done+=len(rows)
            for cp,row in rows.items():
                if row['verdict']!='BLOCK': continue
                blocked+=1;font_blocked+=1
                if h in ANCHORS: coarse_blocked+=1
                codes=sorted(set(filter(None,row['reasons'].split(';'))))
                reasons.update(codes);font_reasons.update(codes)
                failures.append(dict(font_id=font['id'],codepoint=cp,text=chr(cp),height_mm=h,reasons=codes,stitches=int(row['stitches'])))
        complete=all(v!='PENDING' for v in states.values())
        profiles.append(dict(font_id=font['id'],font_sha256=font['sha256'],complete=complete,
            completed_cases=font_done,blocked_cases=font_blocked,omitted_after_first_blocker=font_omitted,failed_cases_by_reason=dict(font_reasons),
            anchor_states={str(h):s for h,s in anchor_states.items()},refinement_heights_mm=refine,
            passing_sampled_heights_mm=[h for h,s in states.items() if s=='PASS'],
            height_states={str(h):s for h,s in states.items()},
            blocked_sampled_heights_mm=[h for h,s in states.items() if s=='BLOCK'],
            pending_heights_mm=[h for h,s in states.items() if s=='PENDING'],
            longest_sampled_passing_interval=sampled_run(states) if complete else None,
            interpolation_verified=False))
    complete=all(p['complete'] for p in profiles)
    elapsed=time.time()-started if started else (json.loads((root/'timing.json').read_text())['wall_seconds'] if (root/'timing.json').exists() else None)
    report=dict(status='COMPLETE' if complete else 'INCOMPLETE',expected_cases=expected,completed_cases=done,
        passed_cases=done-blocked,blocked_cases=blocked,pending_cases=expected-done-omitted,omitted_after_first_blocker=omitted,
        coarse_completed_cases=coarse_done,coarse_passed_cases=coarse_done-coarse_blocked,coarse_blocked_cases=coarse_blocked,
        refinement_completed_cases=done-coarse_done,refinement_passed_cases=done-coarse_done-blocked+coarse_blocked,refinement_blocked_cases=blocked-coarse_blocked,
        height_checks_expected=sum(len(p['height_states']) for p in profiles),height_checks_complete=sum(s!='PENDING' for p in profiles for s in p['height_states'].values()),
        elapsed_wall_seconds=elapsed,reason_counts_can_overlap=True,failed_cases_by_reason=dict(reasons),profiles=profiles,identity=identity)
    write(root/'summary.json',report)
    write(root/'allowed-height-ranges.json',dict(schema_version=2,complete=complete,deployment_enabled=False,
        criterion='all30 selected characters pass a sampled height; longest successive PASS samples; ties lower start',
        refinement_fail_fast=True,scope='Coarse 5-point sampling + 1mm refinement of PASS/BLOCK boundaries only in fonts with interior failing anchors. Intermediate untested heights and arbitrary text are not certified.',
        default_decision='DENY untested inputs; ranges are sampled candidates, not continuous certification',profiles=profiles))
    if final:
        with (root/'failures.jsonl').open('w') as stream:
            for item in failures: stream.write(json.dumps(item,ensure_ascii=False)+'\n')
    lines=['# Korean TTF fill — reduced sweep','',f"{report['status']}: {report['height_checks_complete']}/{report['height_checks_expected']} height decisions; {done} executed + {omitted} omitted = {expected} potential cases; PASS {done-blocked}; BLOCK {blocked}; wall {elapsed} s.",'',
        '| Font | PASS/checked | Sampled candidate mm | 1mm extra heights |','|---|---:|---|---:|']
    for p in profiles:
        r=p['longest_sampled_passing_interval'];label=f"{r['min_mm']}–{r['max_mm']}" if r else 'none/pending'
        lines.append(f"| {p['font_id']} | {p['completed_cases']-p['blocked_cases']}/{p['completed_cases']} | {label} | {len(p['refinement_heights_mm'])} |")
    lines+=['',f'Coarse: {coarse_done} checked, {coarse_blocked} BLOCK; refinement: {done-coarse_done} checked, {blocked-coarse_blocked} BLOCK.', '', 'Reason counts (can overlap): '+json.dumps(dict(reasons)), '',
        f'Omitted character checks after a witnessed blocker: {omitted}. Every PASS height still requires all {count} characters.', '', 'Sampled candidate intervals only: untested intermediate heights, Android path extraction, multiple-letter strings and physical sew-out remain outside this check. No app range activation.']
    (root/'summary.md').write_text('\n'.join(lines)+'\n')
    return report


def run_phase(root,workers,fonts,heights,label,started):
    manifest=root/f'{label}-fonts.json';write(manifest,dict(fonts=fonts))
    selection=root/'characters.json'
    if label!='coarse':
        chars=json.loads(selection.read_text());font=fonts[0];fail_counts=collections.Counter()
        for h in ANCHORS[1:]:
            fail_counts.update(cp for cp,row in rows_at(root,font['id'],h).items() if row['verdict']=='BLOCK')
        chars['characters'].sort(key=lambda c:(-fail_counts[c['codepoint']],c['codepoint']))
        selection=root/f'{label}-characters.json';write(selection,chars)
    args=command(root)+['run',str(manifest),str(selection),str(root/'cases'),str(workers),','.join(map(str,heights)),'30']
    if label!='coarse': args+=['fail-fast']
    began=time.time()
    with (root/'run.log').open('a') as log:
        child=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT)
        write(root/'run.json',dict(driver_pid=os.getpid(),java_pid=child.pid,workers=workers,started=started,phase=label))
        while child.poll() is None:
            try: child.wait(timeout=30)
            except subprocess.TimeoutExpired:
                r=summarize(root,started)
                print(f"PROGRESS {label}: {r['completed_cases']}/{r['expected_cases']} BLOCK={r['blocked_cases']} wall={time.time()-started:.1f}s",flush=True)
    if child.returncode: raise RuntimeError(f'{label}: JVM exit {child.returncode}')
    return time.time()-began


def run(root,workers):
    assert 1<=workers<=8
    identity=json.loads((root/'identity.json').read_text())
    assert sha(root/'frozen-fonts.json')==identity['fonts_sha256']
    assert sha(root/'characters.json')==identity['selection_sha256']
    validate_runtime(root,identity)
    fonts=json.loads((root/'frozen-fonts.json').read_text())['fonts']
    resumed=(root/'run.json').exists()
    started=json.loads((root/'run.json').read_text())['started'] if resumed else time.time()
    timings={}
    timings['coarse_seconds']=run_phase(root,workers,fonts,ANCHORS,'coarse',started)
    r=summarize(root,started)
    for font,profile in zip(fonts,r['profiles']):
        if profile['refinement_heights_mm']:
            label='refine-'+font['id']
            timings[label]=run_phase(root,workers,[font],profile['refinement_heights_mm'],label,started)
    result=summarize(root,started,final=True)
    write(root/'timing.json',dict(workers=workers,wall_seconds=time.time()-started,resumed_checkpoint=resumed,phases_this_invocation=timings))
    print('FINAL',result['status'],result['completed_cases'],'PASS',result['passed_cases'],'BLOCK',result['blocked_cases'],
          'wall_seconds',round(time.time()-started,3),flush=True)
    return 0 if result['status']=='COMPLETE' else 1


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode',choices=['prepare','run','reduce'])
    p.add_argument('--root',type=Path,default=Path('/tmp/myembro-ttf-fill-sweep-reduced'))
    p.add_argument('--font-cache',type=Path,default=Path('/tmp/myembro-fontq-independent/fonts'))
    p.add_argument('--workers',type=int,default=8)
    a=p.parse_args();root=a.root.resolve()
    if a.mode=='prepare':initialize(root,a.font_cache);prepare(root)
    elif a.mode=='reduce':summarize(root,final=True)
    else:raise SystemExit(run(root,a.workers))
