#!/usr/bin/env python3
"""Opt-in JVM timing probes in temporary source copies; tracked algorithms are not edited.
Usage: profile-circular.py [--record DIR] [--jfr FILE] [--baseline-ref COMMIT]
"""
import argparse, pathlib, subprocess, tempfile, shutil
p=argparse.ArgumentParser();p.add_argument('--record');p.add_argument('--jfr');p.add_argument('--baseline-ref');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[2]
base='engine/src/main/kotlin/io/github/linker2k/myembro/engine/'
probes={
'inkstitch/stitches/FillTrace.kt':[
 ('sink.get()?.invoke(stage,payload())', 'if(stage.startsWith("timing:")) sink.get()?.invoke(stage,payload())')],
'edit/ShapeBlockRenderer.kt':[
 ('                for (poly in FillGeometry.polygons', '                FillTrace.emit("timing:geometry") { "" }\n                for (poly in FillGeometry.polygons'),
 ('                    for(layer in FillUnderlay.generate', '                    FillTrace.emit("timing:underlay") { "" }\n                    for(layer in FillUnderlay.generate'),
 ('                    val points = when(p.fill_method)', '                    FillTrace.emit("timing:top") { "" }\n                    val points = when(p.fill_method)'),
 ('                    add(points)', '                    add(points)\n                    FillTrace.emit("timing:geometry") { "" }')],
'inkstitch/stitches/CircularFill.kt':[
 ('        val stitches = FillRouting.routeSegments', '        FillTrace.emit("timing:routing") { "" }\n        val stitches = FillRouting.routeSegments'),
 ('        return applyBeanStitchAndRepeats(stitches,', '        FillTrace.emit("timing:top") { "" }\n        return applyBeanStitchAndRepeats(stitches,')],
'inkstitch/svg/VectorArt.kt':[
 ('            if (resolved.excluded)', '            io.github.linker2k.myembro.engine.inkstitch.stitches.FillTrace.emit("timing:geometry") { "" }\n            if (resolved.excluded)'),
 ('        val pattern = if (groups.isEmpty())', '        io.github.linker2k.myembro.engine.inkstitch.stitches.FillTrace.emit("timing:finish") { "" }\n        val pattern = if (groups.isEmpty())')]
}
with tempfile.TemporaryDirectory(prefix='myembro-circular-profile-') as tmp:
 tmp=pathlib.Path(tmp);source=tmp/'src'
 shutil.copytree(root/base,source)
 if a.baseline_ref:
  old=subprocess.check_output(['git','show',a.baseline_ref+':'+base+'inkstitch/stitches/FillRouting.kt'],cwd=root)
  (source/'inkstitch/stitches/FillRouting.kt').write_bytes(old)
 for relative,replacements in probes.items():
  text=(root/base/relative).read_text()
  for old,new in replacements:
   assert text.count(old)==1,(relative,old,text.count(old))
   text=text.replace(old,new)
  target=source/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text)
 init=tmp/'profile.gradle'
 extra=''
 if a.record: extra+='systemProperty "circular.record", '+repr(str(pathlib.Path(a.record).resolve()))+'\n'
 if a.jfr: extra+='jvmArgs "-XX:StartFlightRecording=filename='+str(pathlib.Path(a.jfr).resolve())+',settings=profile,dumponexit=true"\n'
 init.write_text("""gradle.projectsEvaluated {
 def e=gradle.rootProject.project(':engine')
 e.kotlin.sourceSets.main.kotlin.setSrcDirs(['"""+str(source)+"""'])
 e.tasks.withType(Test).configureEach { maxHeapSize='1g'; """+extra+""" }
}
""")
 subprocess.run(['./gradlew','--offline','--max-workers=2','-I',str(init),':engine:test','--tests','*CircularBlockProfileTest','--rerun-tasks'],cwd=root,check=True)
