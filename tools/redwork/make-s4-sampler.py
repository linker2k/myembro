#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Original synthetic line art -> actual S4 pipeline -> existing worker sampler."""
import math
import hashlib
import json
import os
from pathlib import Path
import subprocess
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parents[2]
out = Path('/tmp/myembro-sewtest/s4')
out.mkdir(parents=True, exist_ok=True)
for name in ['FLOWER', 'HOUSE']:
    image = Image.new('RGB', (192, 192), 'white')
    d = ImageDraw.Draw(image)
    if name == 'FLOWER':
        # Five-petal closed flower with an isolated center ring. Original parametric drawing.
        points = []
        for i in range(361):
            a = i * 2 * math.pi / 360
            r = 62 + 15 * math.cos(5*a)
            points.append((96+r*math.cos(a), 96+r*math.sin(a)))
        d.line(points, fill='black', width=10, joint='curve')
        d.ellipse((72,72,120,120), outline='black', width=10)
    else:
        # Closed house silhouette, a door and one window; no downloaded art/fonts.
        d.line([(26,90),(96,25),(166,90),(154,90),(154,166),(38,166),(38,90),(26,90)],
               fill='black', width=10, joint='curve')
        d.rectangle((58,112,82,138), outline='black', width=8)
        d.line([(106,162),(106,116),(132,116),(132,162)], fill='black', width=8)
    image.save(out/(name+'.input.png'))
    (out/(name+'.rgb')).write_bytes(image.tobytes())
env = dict(os.environ)
env.setdefault('JAVA_HOME', '/home/gosh2/.jdks/jdk-17.0.20.1+1')
subprocess.run([str(root/'gradlew'), ':engine:makeS4Samples', '--offline', '--console=plain'], cwd=root, env=env, check=True)
# Two 30mm motifs plus the worker's unchanged 5mm gap need a 65mm-wide sheet.
subprocess.run(['python3', str(root/'tools/make_sewtest_sampler.py'), str(out/'FLOWER.PES'), str(out/'HOUSE.PES'),
                '--output', str(out/'S4SAMPL'), '--max-mm', '70', '--gap-mm', '5'], cwd=root, env=env, check=True)
gate = subprocess.run(['/tmp/myembro-quality-gate/quality-gate', 'check', str(out/'S4SAMPL.PES'),
                '--text', '--style', 'running', '--hoop-bounds-mm', '-35', '-35', '35', '35',
                '--json', str(out/'S4SAMPL-gate.json')], capture_output=True, text=True)
(out/'S4SAMPL-gate.txt').write_text(gate.stdout + gate.stderr)
print(gate.stdout, end='')
if gate.stderr:
    print(gate.stderr, end='')
gate.check_returncode()
files = ['FLOWER.rgb', 'HOUSE.rgb', 'FLOWER.PES', 'HOUSE.PES', 'S4SAMPL.PES', 'S4SAMPL.png',
         'S4SAMPL.json', 'S4SAMPL-gate.json', 'inputs-report.json']
provenance = {
    'commit': subprocess.check_output(['git','rev-parse','HEAD'], cwd=root, text=True).strip(),
    'workingTreeDirty': bool(subprocess.check_output(['git','status','--porcelain'], cwd=root, text=True).strip()),
    'source': 'Original scripted flower and house; no photos, downloaded artwork or fonts',
    'pipeline': 'Wu 2 colors -> Potrace defaults -> Ink/Stitch running defaults -> S1 -> PES v1',
    'size': 'Maximum sewn dimension 30mm per motif; input scale calibrated by regeneration, never scaled finished stitches',
    'composition': 'Existing worker sampler; single Black 40wt; 5mm gap; 70mm packing limit',
    'sha256': {name:hashlib.sha256((out/name).read_bytes()).hexdigest() for name in files},
}
(out/'S4SAMPL-provenance.json').write_text(json.dumps(provenance, indent=2)+'\n')
