#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Freeze existing model classes and call GlyphStrokeWidths. Does not build/modify model."""
import argparse,hashlib,json,pathlib,shutil,subprocess,time
p=argparse.ArgumentParser();p.add_argument('out',type=pathlib.Path);p.add_argument('--model-root',type=pathlib.Path,required=True);p.add_argument('--model-classpath',type=pathlib.Path,required=True);p.add_argument('--jdk',type=pathlib.Path,default=pathlib.Path('/home/gosh2/.jdks/jdk-17.0.20.1+1'));a=p.parse_args()
out=a.out.resolve();src=a.model_root.resolve();root=pathlib.Path(__file__).resolve().parents[2]
assert not (out/'model-classes').exists(), 'Use a new output directory; preserve prior frozen measurement classes'
shutil.copytree(src/'engine/build/classes/kotlin/main',out/'model-classes')
cp=[str(out/'model-classes')]+[x for x in a.model_classpath.read_text().strip().split(':') if not x.startswith(str(src))]
(out/'measure-classpath.txt').write_text(':'.join(cp))
subprocess.run([str(a.jdk/'bin/javac'),'-cp',':'.join(cp),'-d',str(out),str(root/'tools/hangul_satin/MeasureSweepWidths.java')],check=True)
t=time.perf_counter();subprocess.run([str(a.jdk/'bin/java'),'-Djava.awt.headless=true','-cp',str(out)+':'+':'.join(cp),'MeasureSweepWidths',str(out/'fonts.tsv'),(out/'characters.txt').read_text(),str(out/'widths.tsv')],check=True)
seconds=time.perf_counter()-t;(out/'width-seconds.txt').write_text(str(seconds))
files=['engine/src/main/kotlin/io/github/linker2k/myembro/engine/lettering/GlyphStrokeWidths.kt','engine/src/main/kotlin/io/github/linker2k/myembro/engine/geometry/StrokeWidthMeasurement.kt']
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
(out/'width-provenance.json').write_text(json.dumps(dict(model_commit=subprocess.check_output(['git','-C',str(src),'rev-parse','HEAD'],text=True).strip(),sources={x:sha(src/x) for x in files},classes_sha256={str(x.relative_to(out/'model-classes')):sha(x) for x in (out/'model-classes').rglob('*.class')},measurement_seconds=seconds),indent=2)+'\n')
