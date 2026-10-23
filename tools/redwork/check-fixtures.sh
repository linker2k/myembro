#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo_dir"
export JAVA_HOME=${JAVA_HOME:-/home/gosh2/.jdks/jdk-17.0.20.1+1}
gate=${1:-/tmp/myembro-quality-gate/quality-gate}
out=/tmp/myembro-port-out/editor/redwork
./gradlew :engine:test --tests '*RedworkPipelineTest' --rerun-tasks
python3 - <<'PY'
import hashlib,json,pathlib,subprocess
out=pathlib.Path('/tmp/myembro-port-out/editor/redwork')
cases=[]
for name in ['rectangle','round']:
 source=pathlib.Path('engine/src/test/resources/redwork')/(name+'.pbm')
 pes=out/(name+'.pes')
 cases.append({'fixture':name,'style':'running','width_mm':60,'path':str(pes),
  'input_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
  'pes_sha256':hashlib.sha256(pes.read_bytes()).hexdigest()})
(out/'matrix.json').write_text(json.dumps({'branch':subprocess.check_output(['git','branch','--show-current'],text=True).strip(),
 'commit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
 'axes':{'fixtures':['rectangle','round'],'styles':['running'],'widths_mm':[60]},'cases':cases},indent=2)+'\n')
PY
"$gate" check --matrix "$out/matrix.json" --json "$out/quality-gate.json" | tee "$out/quality-gate.txt"
