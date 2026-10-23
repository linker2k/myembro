#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Replay frozen, materialized oracle SVG inputs with OUR Kotlin engine; never run Ink/Stitch.

Root/integrator owns Gradle scheduling. Output manifest is accepted by the independent
oracle compare_matrix.py --ports contract; GENERATED means a PES exists, not equivalence.
Unsupported geometry/paint is explicitly X; official PES/coordinates are never read.
"""
import argparse
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--matrix', type=Path, default=Path('/tmp/myembro-quality-gate/matrix-inkstitch.json'))
    parser.add_argument('--oracle-root', type=Path, default=Path('/tmp/myembro-inkstitch-oracle'))
    parser.add_argument('--output', type=Path, default=Path('/tmp/myembro-port-out/worker'))
    parser.add_argument('--features', default='stitch_plan,ties,stroke')
    parser.add_argument('--only-id', default='', help='Debug one frozen ID; use a separate output directory')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    environment = dict(os.environ)
    environment.setdefault('JAVA_HOME', '/home/gosh2/.jdks/jdk-17.0.20.1+1')
    command = [str(root / 'gradlew'), ':engine:runInkstitchWorkerMatrix', '--console=plain',
               '-PworkerMatrix=' + str(args.matrix.resolve()),
               '-PworkerOracleRoot=' + str(args.oracle_root.resolve()),
               '-PworkerOutput=' + str(args.output.resolve()),
               '-PworkerFeatures=' + args.features,
               '-PworkerOnlyId=' + args.only_id]
    return subprocess.run(command, cwd=root, env=environment, check=False).returncode


if __name__ == '__main__':
    raise SystemExit(main())
