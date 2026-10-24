#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate T1/T2/T3 on our Kotlin engine, then independently dump PEC and run gate v5.
Requires existing JDK/Gradle cache and quality-gate; changes no production sources.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def dump(data):
    p=int.from_bytes(data[8:12],'little')
    end=p+512+int.from_bytes(data[p+514:p+517],'little')
    i=p+528;x=y=0;rows=[]
    while i<end:
        start=i;a=data[i];i+=1
        if a==255 and (i==end or data[i]==0):
            if i<end:i+=1
            rows.append((start,'END',0,0,x,y,data[start:i]));break
        b=data[i];i+=1
        if (a,b)==(254,176):i+=1;rows.append((start,'COLOR',0,0,x,y,data[start:i]));continue
        flags=0
        if a&128:
            flags|=a&48;dx=((a&15)<<8)|b;dx=dx-4096 if dx&2048 else dx;b=data[i];i+=1
        else:dx=a-128 if a&64 else a
        if b&128:
            flags|=b&48;dy=((b&15)<<8)|data[i];i+=1;dy=dy-4096 if dy&2048 else dy
        else:dy=b-128 if b&64 else b
        x+=dx;y+=dy
        rows.append((start,'TRIMJUMP' if flags&32 else 'JUMP' if flags else 'STITCH',dx,dy,x,y,data[start:i]))
    assert i==end
    return rows


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,default=Path('/tmp/myembro-sewtest/ab'))
    p.add_argument('--gate',type=Path,default=Path('/tmp/myembro-quality-gate/quality-gate'))
    a=p.parse_args();root=Path(__file__).resolve().parents[1];out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    work=Path(tempfile.mkdtemp(prefix='myembro-ab-'))
    init=work/'ab.gradle'
    init.write_text('''allprojects {
 afterEvaluate {
 if (plugins.hasPlugin('org.jetbrains.kotlin.jvm')) {
  if (path == ':engine') {
   sourceSets.oracle.java.srcDir(%s)
   tasks.register('generateAbSewtest', JavaExec) {
    dependsOn(tasks.named('oracleClasses'))
    classpath = sourceSets.oracle.runtimeClasspath
    mainClass = 'io.github.linker2k.myembro.tools.AbSewTest'
    systemProperty('java.awt.headless','true')
    args(%s)
   }
  }
 }
 }
}
''' % (json.dumps(str(root/'tools/ab_sewtest')),json.dumps(str(out))))
    env=dict(os.environ);env.setdefault('JAVA_HOME','/home/gosh2/.jdks/jdk-17.0.20.1+1')
    subprocess.run([str(root/'gradlew'),'-I',str(init),':engine:generateAbSewtest','--offline','--console=plain'],cwd=root,env=env,check=True)
    files=[out/(n+'.PES') for n in ('T1PEDS','T2OURS','T3NOZRO')]
    summaries={}
    for file in files:
        data=file.read_bytes();events=dump(data)
        text=['# Raw PEC first 16 events; coordinates/deltas are 0.1mm units',
              '# event  absolute offset  raw bytes         command   dx dy -> x y']
        for n,(offset,cmd,dx,dy,x,y,raw) in enumerate(events[:16],1):
            text.append(f'{n:2}  0x{offset:04x}  {raw.hex(" "):17} {cmd:8} {dx:4} {dy:4} -> {x:4} {y:4}')
        (out/(file.stem+'-first16.txt')).write_text('\n'.join(text)+'\n')
        zero=[r for r in events if r[1] in ('JUMP','STITCH') and r[2]==r[3]==0]
        summaries[file.name]={'sha256':hashlib.sha256(data).hexdigest(),'zero_moves':len(zero),'zero_jumps':sum(r[1]=='JUMP' for r in zero),'zero_needles':sum(r[1]=='STITCH' for r in zero)}
        if file.stem!='T2OURS':assert not zero
        else:assert summaries[file.name]['zero_jumps']>=1 and summaries[file.name]['zero_needles']>=1
    (out/'byte-summary.json').write_text(json.dumps(summaries,indent=2)+'\n')
    result=subprocess.run([str(a.gate),'check',*[str(f) for f in files],'--hoop-bounds-mm','-50','-50','50','50','--json',str(out/'gate.json')],capture_output=True,text=True)
    (out/'gate.txt').write_text(result.stdout+result.stderr);print(result.stdout+result.stderr,end='');result.check_returncode()


if __name__=='__main__':main()
