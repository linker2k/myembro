#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate five S1 sewout samples through the real Android app/engine path.

Requires an already-running emulator, the Android SDK and the existing JDK/Gradle cache.
Uses an isolated application ID; does not replace the user's/main QA application.
No personal designs, original Ink/Stitch execution, downloads or full test matrices.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

PACKAGE = 'io.github.linker2k.myembro.s1sewtest'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('/tmp/myembro-sewtest/s1'))
    parser.add_argument('--serial', default='emulator-5554')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    adb = Path.home() / 'Android/Sdk/platform-tools/adb'
    device = [str(adb), '-s', args.serial]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='myembro-s1-sewtest-'))
    manifest = work / 'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>
<activity android:name="io.github.linker2k.myembro.ui.lettering.S1SewTestActivity" android:exported="true" />
</application></manifest>''')
    init = work / 'preview.gradle'
    init.write_text('''allprojects {
    plugins.withId('com.android.application') {
        androidComponents.finalizeDsl { dsl ->
            dsl.defaultConfig.applicationId = %s
            dsl.sourceSets.getByName('debug').java.srcDir(%s)
            dsl.sourceSets.getByName('debug').manifest.srcFile(%s)
        }
    }
}
''' % (json.dumps(PACKAGE), json.dumps(str(root / 'tools/sewtest')), json.dumps(str(manifest))))
    environment = dict(os.environ)
    environment.setdefault('JAVA_HOME', '/home/gosh2/.jdks/jdk-17.0.20.1+1')
    subprocess.run([str(root/'gradlew'), '-I', str(init), ':app:assembleDebug', '--offline', '--console=plain'],
                   cwd=root, env=environment, check=True)
    subprocess.run(device + ['install', '-r', str(root/'app/build/outputs/apk/debug/app-debug.apk')], check=True)
    try:
        subprocess.run(device + ['shell', 'am', 'start', '-W', '-n', PACKAGE + '/io.github.linker2k.myembro.ui.lettering.S1SewTestActivity'], check=True)
        for _ in range(120):
            done = subprocess.run(device + ['exec-out', 'run-as', PACKAGE, 'cat', 'files/s1/DONE'], capture_output=True)
            if done.stdout.startswith(b'5 PES + 5 previews + overview'):
                break
            error = subprocess.run(device + ['exec-out', 'run-as', PACKAGE, 'cat', 'files/s1/ERROR.txt'], capture_output=True)
            if error.stdout and not error.stdout.startswith(b'cat:'):
                raise RuntimeError(error.stdout.decode())
            time.sleep(0.5)
        else:
            raise TimeoutError('Android sewout generation did not finish in 60 seconds')
        for name in ['manifest.json', 'overview.png'] + [stem + ext for stem in ['TXT05', 'TXT08', 'TXT12', 'CIRCLE', 'SQUARE'] for ext in ['.PES', '.png']]:
            content = subprocess.check_output(device + ['exec-out', 'run-as', PACKAGE, 'cat', 'files/s1/'+name])
            signature = b'#PES' if name.endswith('.PES') else b'\x89PNG\r\n\x1a\n' if name.endswith('.png') else b'{'
            if not content.startswith(signature):
                raise RuntimeError('Missing/invalid Android artifact: '+name)
            (output/name).write_bytes(content)
        data = json.loads((output/'manifest.json').read_text())
        data['commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
        data['working_tree_dirty'] = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=root, text=True).strip())
        (output/'manifest.json').write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n')
        (output/'README.md').write_text('''# S1 자투리 천 시험 세트

- TXT05.PES / TXT08.PES / TXT12.PES: "ABC 가나다", 5/8/12mm, 번들 Noto Sans KR, 러닝 1회.
- CIRCLE.PES / SQUARE.PES: 지름/한 변 10mm 러닝 윤곽.
- 모두 PES v1, 8.3 파일명, 100×100mm 후프 안 중앙 배치. 개인 도안 없음.
- PNG는 저장한 PES를 다시 읽은 실제 바늘 경로. 점은 바늘 위치이며 점프선은 숨김.
- overview.png는 전체 미리보기. 실제 봉제 통과는 아직 확인하지 않음.
- 재현: python3 tools/generate_s1_sewtest.py
- 실제 Android renderLettering → TextObject → Project.flatten → 앱 기본 PesWriter 경로 사용.
  윤곽은 기존 RunningStitch.generate → StitchObject → Project.flatten 경로.
''')
        print('READY:', output, '— 5 PES, 6 PNG')
    finally:
        subprocess.run(device + ['uninstall', PACKAGE], check=False)


if __name__ == '__main__':
    main()
