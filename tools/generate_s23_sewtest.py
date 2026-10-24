#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate S2/S3 candidates on an emulator, then compose a labeled 60mm sampler.
Requires locally integrated letter 8bb03dd + model DefaultTatami + canvas catalog.
Reads embroidery-fonts data only. Never executes upstream Ink/Stitch. No personal designs.
Aventurina is the letter team's 12mm script trial, not a redistributable catalog font.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fonts',type=Path,required=True,help='Pinned embroidery-fonts src directory')
    p.add_argument('--output',type=Path,default=Path('/tmp/myembro-sewtest/s23'))
    p.add_argument('--serial',default='emulator-5554')
    a=p.parse_args()
    root=Path(__file__).resolve().parents[1]
    if not (root/'engine/src/main/kotlin/io/github/linker2k/myembro/engine/inkstitch/elements/DefaultTatami.kt').exists():
        p.error('Integrate letter/model/catalog locally first; this generator does not merge or commit branches')
    integration_commits={ref:subprocess.check_output(['git','rev-parse',ref],cwd=root,text=True).strip()
                         for ref in ('codex/letter','codex/model','claude/inkfonts-s3')}
    catalog=json.loads((root/'app/src/main/assets/fonts/inkstitch-catalog.json').read_text())
    geneva=next(f for f in catalog['fonts'] if f['id']=='inkstitch-geneva-simple')
    assert geneva['recommendedMinHeightMm'] <= 10 <= geneva['recommendedMaxHeightMm']
    assert not geneva['thinThreadRequired']
    assert hashlib.sha256((a.fonts/'geneva_simple/ltr.svg').read_bytes()).hexdigest()==geneva['sha256']
    out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    work=Path(tempfile.mkdtemp(prefix='myembro-s23-'))
    assets=work/'assets'
    provenance={}
    for folder in ('aventurina','geneva_simple'):
        for name in ('ltr.svg','font.json','LICENSE'):
            source=a.fonts/folder/name
            data=source.read_bytes()
            target=assets/'sewtest-fonts'/folder/name
            target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
            provenance[f'{folder}/{name}']=hashlib.sha256(data).hexdigest()
    package='io.github.linker2k.myembro.s23sewtest'
    activity='io.github.linker2k.myembro.ui.lettering.S23SewTestActivity'
    manifest=work/'AndroidManifest.xml'
    manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><activity android:name="{activity}" android:exported="true" /></application></manifest>')
    init=work/'preview.gradle'
    init.write_text('''allprojects {
 plugins.withId('com.android.application') {
  androidComponents.finalizeDsl { dsl ->
   dsl.defaultConfig.applicationId = %s
   dsl.sourceSets.getByName('debug').java.srcDir(%s)
   dsl.sourceSets.getByName('debug').assets.srcDir(%s)
   dsl.sourceSets.getByName('debug').manifest.srcFile(%s)
  }
 }
}
''' % tuple(json.dumps(str(x)) for x in (package,root/'tools/sewtest_s23',assets,manifest)))
    env=dict(os.environ);env.setdefault('JAVA_HOME','/home/gosh2/.jdks/jdk-17.0.20.1+1')
    subprocess.run([str(root/'gradlew'),'-I',str(init),':app:assembleDebug','--offline','--console=plain'],cwd=root,env=env,check=True)
    device=[str(Path.home()/'Android/Sdk/platform-tools/adb'),'-s',a.serial]
    subprocess.run(device+['install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')],check=True)
    try:
        subprocess.run(device+['shell','am','start','-W','-n',package+'/'+activity],check=True)
        for _ in range(240):
            def read(name):
                return subprocess.run(device+['exec-out','run-as',package,'cat','files/s23/'+name],capture_output=True)
            done=read('DONE')
            if done.returncode==0 and done.stdout.startswith(b'7 PES ready'):break
            error=read('ERROR.txt')
            if error.returncode==0 and error.stdout and not error.stdout.startswith(b'cat:'):
                raise RuntimeError(error.stdout.decode())
            time.sleep(.5)
        else:raise TimeoutError('S23 generation exceeded 120 seconds')
        stems=['SCRIPT12','JUNG12','MIN12','ABC10','SQUARE15','CIRCLE15','KOREAN15']
        for name in ['manifest.json']+[s+'.PES' for s in stems]:
            result=read(name);result.check_returncode();(out/name).write_bytes(result.stdout)
        data=json.loads((out/'manifest.json').read_text())
        data['font_sha256']=provenance
        data['worker_commit']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
        data['temporary_integration']=True
        data['integration_commits']=integration_commits
        data['catalog_sha256']=hashlib.sha256((root/'app/src/main/assets/fonts/inkstitch-catalog.json').read_bytes()).hexdigest()
        data['font_source_commit']=subprocess.check_output(['git','-C',str(a.fonts.parent),'rev-parse','HEAD'],text=True).strip()
        (out/'manifest.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    finally:subprocess.run(device+['uninstall',package],check=False)
    subprocess.run(['python3',str(root/'tools/make_sewtest_sampler.py'),*[str(out/(s+'.PES')) for s in ['JUNG12','CIRCLE15','MIN12','SQUARE15','ABC10','KOREAN15']],
                    '--output',str(out/'S23SAMPL')],cwd=root,env=env,check=True)
    print('CANDIDATE:',out/'S23SAMPL.PES','— run independent quality-gate before sewout')


if __name__=='__main__':main()
