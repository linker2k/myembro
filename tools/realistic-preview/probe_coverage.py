#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Diagnostic only: ask Inkscape for each original path's SourceAlpha.

Keep the fixed page/path/filter bounds. Replace the filter chain with the identity
feGaussianBlur(stdDeviation=0, in=SourceAlpha), remove the white page background,
and export transparent PNGs. The original reference PNGs are never changed.
These masks isolate Cairo coverage from Kotlin's numeric filters, not a new default.
"""
import argparse
from copy import deepcopy
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference',type=Path,required=True)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--inkscape',required=True)
    args=parser.parse_args()
    ns='{http://www.w3.org/2000/svg}'
    args.out.mkdir(parents=True,exist_ok=True)
    env=os.environ.copy()
    (args.out/'empty-gio').mkdir(exist_ok=True)
    env['GIO_MODULE_DIR']=str((args.out/'empty-gio').resolve())
    for case in ['horizontal','vertical','tatami-patch']:
        source=ET.parse(args.reference/'svg'/f'{case}.svg').getroot()
        count=len(source.findall('.//'+ns+'path'))
        for index in range(count):
            svg=deepcopy(source)
            for rect in list(svg.findall(ns+'rect')):svg.remove(rect)
            filter_node=svg.find('.//'+ns+'filter')
            for primitive in list(filter_node)[1:]:filter_node.remove(primitive)
            filter_node[0].set('stdDeviation','0')
            paths=svg.findall('.//'+ns+'path')
            for group in svg.findall(ns+'g'):
                for path in list(group):
                    if path is not paths[index]:group.remove(path)
            svg_path=args.out/case/f'{index:03}.svg'
            svg_path.parent.mkdir(parents=True,exist_ok=True)
            ET.ElementTree(svg).write(svg_path)
            for dpi in [300,600]:
                png=args.out/case/str(dpi)/f'{index:03}.png';png.parent.mkdir(exist_ok=True)
                subprocess.run([args.inkscape,str(svg_path),'--export-area-page',f'--export-dpi={dpi}',
                    '--export-background-opacity=0',f'--export-filename={png}'],
                    env=env,check=True,capture_output=True,timeout=30)
        print(f'SOURCE_ALPHA {case}: {count} paths x 2 DPI',flush=True)


if __name__=='__main__':main()
