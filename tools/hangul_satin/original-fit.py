#!/usr/bin/env python3
"""Local original AutoTrace fit fixture generation; never executes Ink/Stitch or installs dependencies."""
import hashlib,json,pathlib,subprocess,argparse
p=argparse.ArgumentParser();p.add_argument('--input',type=pathlib.Path,default=pathlib.Path('/tmp/myembro-sewtest/krs'));p.add_argument('--source',type=pathlib.Path,default=pathlib.Path('/tmp/myembro-autotrace-reference'));p.add_argument('--glib',type=pathlib.Path,default=pathlib.Path('/tmp/myembro-autotrace-build/glib'));a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[2];src=a.source
commit='1a232b27818a46a05cd25edec09e2f77c4e9ac01'
assert subprocess.check_output(['git','-C',str(src),'rev-parse','HEAD'],text=True).strip()==commit
assert not subprocess.check_output(['git','-C',str(src),'status','--porcelain'],text=True).strip()
names=['thin-image.c','pxl-outline.c','autotrace.c','color.c','exception.c','fit.c','curve.c','spline.c','vector.c','epsilon-equal.c']
caller=root/'tools/autotrace/fit_oracle.c';exe=a.input/'original-fit'
cmd=['cc','-std=c99','-D_DEFAULT_SOURCE','-O0','-ffp-contract=off','-ffunction-sections','-fdata-sections','-Wl,--gc-sections','-DAUTOTRACE_VERSION="0.31.10"','-DAUTOTRACE_WEB="https://github.com/autotrace/autotrace"','-I'+str(src/'src'),str(caller),*[str(src/'src'/n) for n in names],'-I'+str(a.glib/'usr/include/glib-2.0'),'-I'+str(a.glib/'usr/lib/x86_64-linux-gnu/glib-2.0/include'),'/usr/lib/x86_64-linux-gnu/libgobject-2.0.so.0','/usr/lib/x86_64-linux-gnu/libglib-2.0.so.0','-lm','-o',str(exe)]
subprocess.run(cmd,check=True)
raw=(a.input/'input.pgm').read_bytes()
first=subprocess.check_output([str(exe),'1'],input=raw,timeout=30);second=subprocess.check_output([str(exe),'1'],input=raw,timeout=30)
assert first==second
result=json.loads(first);assert result['paths'];(a.input/'fit.json').write_bytes(first)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest={'upstream':'AutoTrace 0.31.10','commit':commit,'options':'new_fitting_opts; centerline=TRUE; preserve_width=FALSE (explicit constant width supplied to satin converter)','compile_command':cmd,'source_sha256':{n:sha(src/'src'/n) for n in names},'caller_sha256':sha(caller),'executable_sha256':sha(exe),'input_sha256':hashlib.sha256(raw).hexdigest(),'output_sha256':hashlib.sha256(first).hexdigest(),'repeat_exact':True,'paths':len(result['paths']),'segments':sum(len(p['segments']) for p in result['paths']),'cubics':sum(s['degree']==3 for p in result['paths'] for s in p['segments']),'native_reference_only':True}
(a.input/'fit-provenance.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps(manifest))
