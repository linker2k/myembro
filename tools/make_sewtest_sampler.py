#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compose public sew-test PES files through our Kotlin engine; usable for S1/S2/S3."""
import argparse
import os
from pathlib import Path
import subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('inputs',nargs='+',type=Path)
p.add_argument('--output',type=Path,required=True,help='Output stem, e.g. /tmp/myembro-sewtest/s1/S1SAMPL')
p.add_argument('--max-mm',type=float,default=60)
p.add_argument('--gap-mm',type=float,default=5)
a=p.parse_args()
root=Path(__file__).resolve().parents[1]
env=dict(os.environ);env.setdefault('JAVA_HOME','/home/gosh2/.jdks/jdk-17.0.20.1+1')
subprocess.run([str(root/'gradlew'),':engine:makeSampler','--offline','--console=plain',
    '-PsamplerOutput='+str(a.output.resolve()),'-PsamplerMaxMm='+str(a.max_mm),'-PsamplerGapMm='+str(a.gap_mm),
    '-PsamplerInputs='+'|'.join(str(f.resolve()) for f in a.inputs)],cwd=root,env=env,check=True)
