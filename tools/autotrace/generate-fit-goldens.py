#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Run unchanged AutoTrace thinning + centerline + fitted_splines; no installed dependencies."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import shlex

ROOT = Path(__file__).resolve().parents[2]
COMMIT = '1a232b27818a46a05cd25edec09e2f77c4e9ac01'

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--glib-prefix', type=Path)
    parser.add_argument('--output', type=Path, default=Path('/tmp/myembro-port-out/letter/autotrace/fit'))
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    source = args.source.resolve(); out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    assert subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip() == COMMIT
    assert not subprocess.check_output(['git','-C',str(source),'status','--porcelain'],text=True).strip()
    assert sha(source/'src/thin-image.c') == '29a976bc15799ca26462e8b06636bebb689d4a6817d58e9d1040fd20b3c4ccac'
    assert sha(source/'src/pxl-outline.c') == '541a11ed865dab7e0459ef35d0b1f066f10b9b4d54f1e5e661a1f92cadae8534'
    if args.glib_prefix:
        prefix = args.glib_prefix.resolve()
        flags = ['-I'+str(prefix/'usr/include/glib-2.0'),'-I'+str(prefix/'usr/lib/x86_64-linux-gnu/glib-2.0/include'),
                 '/usr/lib/x86_64-linux-gnu/libgobject-2.0.so.0','/usr/lib/x86_64-linux-gnu/libglib-2.0.so.0']
    else:
        flags = shlex.split(subprocess.check_output(['pkg-config','--cflags','--libs','gobject-2.0'],text=True))
    names = ['thin-image.c','pxl-outline.c','autotrace.c','color.c','exception.c','fit.c','curve.c','spline.c','vector.c','epsilon-equal.c']
    caller = ROOT/'tools/autotrace/fit_oracle.c'
    exe = out/'fit-oracle'
    cmd = ['cc','-std=c99','-D_DEFAULT_SOURCE','-O0','-ffp-contract=off','-ffunction-sections','-fdata-sections','-Wl,--gc-sections','-DAUTOTRACE_VERSION="0.31.10"','-DAUTOTRACE_WEB="https://github.com/autotrace/autotrace"',
           '-I'+str(source/'src'),str(caller),*[str(source/'src'/n) for n in names],*flags,'-lm','-o',str(exe)]
    subprocess.run(cmd,check=True)
    cases = []
    for name in ['bar','tee','cross','loop','hangul-han']:
        input_path = ROOT/f'engine/src/test/resources/autotrace/thinning/{name}.pgm'
        for planes in [1,3]:
            raw = input_path.read_bytes()
            result = subprocess.check_output([str(exe),str(planes)],input=raw,timeout=10)
            assert result == subprocess.check_output([str(exe),str(planes)],input=raw,timeout=10)
            filename = f'{name}.p{planes}.json'
            (out/filename).write_bytes(result)
            if args.verify:
                assert (ROOT/'engine/src/test/resources/autotrace/fit'/filename).read_bytes() == result
            cases.append({'name':name,'planes':planes,'inputSha256':sha(input_path),
                          'outputSha256':sha(out/filename),'pathCount':len(json.loads(result)['paths'])})
            print(filename,'paths='+str(cases[-1]['pathCount']))
    manifest = {'upstream':'AutoTrace 0.31.10','commit':COMMIT,'sourceFiles':{n:sha(source/'src'/n) for n in names},
                'callerSha256':sha(caller),'generatorSha256':sha(Path(__file__)),'compileCommand':cmd,
                'compiler':subprocess.check_output(['cc','--version'],text=True).splitlines()[0],
                'runtimeLibraries':{n:sha(Path('/usr/lib/x86_64-linux-gnu')/n) for n in ['libglib-2.0.so.0','libgobject-2.0.so.0']} if args.glib_prefix else 'pkg-config gobject-2.0',
                'cases':cases,'coordinates':'Cartesian float pixels, y upwards; ordered paths, degree and four xyz control points'}
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('ORIGINAL_FIT_GOLDENS 10/10; REPEAT 10/10' + ('; COMMITTED_EXACT 10/10' if args.verify else ''))

if __name__ == '__main__': main()
