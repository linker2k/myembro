#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Test the explicitly proposed constructor-access diff in scratch, without modifying ShapeObject.kt.
This is a draft integration check, not a substitute for the ordinary build after the model applies the diff.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = root / 'engine/src/main/kotlin/io/github/linker2k/myembro/engine/edit/ShapeObject.kt'
with tempfile.TemporaryDirectory(prefix='myembro-photo-shape-draft-') as temp:
    scratch = Path(temp)
    original = source.read_text()
    assert original.count('class ShapeObject private constructor(') == 1, 'Draft assumes the original private constructor'
    target = scratch / 'src/io/github/linker2k/myembro/engine/edit/ShapeObject.kt'
    target.parent.mkdir(parents=True)
    target.write_text(original.replace('class ShapeObject private constructor(', 'class ShapeObject internal constructor('))
    init = scratch / 'overlay.gradle'
    init.write_text('''allprojects { afterEvaluate {
 if (path == ':engine') {
  kotlin.sourceSets.main.kotlin.setSrcDirs([rootProject.file('engine/src/main/kotlin'), file(%s)])
  kotlin.sourceSets.main.kotlin.exclude { entry -> entry.file.absolutePath == %s }
 }
}}
''' % (json.dumps(str(scratch / 'src')), json.dumps(str(source))))
    env = dict(os.environ)
    env.setdefault('JAVA_HOME', '/home/gosh2/.jdks/jdk-17.0.20.1+1')
    env.setdefault('ANDROID_HOME', '/home/gosh2/Android/Sdk')
    print('DRAFT OVERLAY: constructor private -> internal; tracked source remains unchanged', flush=True)
    subprocess.run([str(root/'gradlew'), '--offline', '-I', str(init), ':engine:test', ':app:testDebugUnitTest'], cwd=root, env=env, check=True)
    assert source.read_text() == original, 'Tracked ShapeObject.kt changed during verification'
