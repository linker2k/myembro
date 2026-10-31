#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check reference artifacts, without claiming an Android renderer is validated.

python verify_reference.py /tmp/myembro-realistic-ref
Validates original source hashes, fixtures, filter primitives and parameters,
SVG path counts, PNG bit depth/dimensions and nonempty independent controls.
"""
import json
import math
from pathlib import Path
import struct
import sys
import xml.etree.ElementTree as ET

from generate_reference import COMMIT, SOURCES, fixtures, sha

SVG = '{http://www.w3.org/2000/svg}'


def verify(directory):
    manifest = json.loads((directory / 'manifest.json').read_text())
    assert manifest['upstream_commit'] == COMMIT
    assert set(manifest['cases']) == set(fixtures())
    assert manifest['page_mm'] == [16, 16]
    for name, digest in SOURCES.items():
        assert sha((directory / 'upstream' / name).read_bytes()) == digest, name
    count = 0
    for case, entry in manifest['cases'].items():
        svg = ET.parse(directory / 'svg' / f'{case}.svg').getroot()
        assert len(svg.findall(f'.//{SVG}path')) == entry['segments']
        filter_node = svg.find(f'.//{SVG}filter')
        assert filter_node.attrib['style'] == 'color-interpolation-filters:sRGB'
        assert [x.tag for x in filter_node] == [SVG + x for x in
                ['feGaussianBlur', 'feSpecularLighting', 'feComposite', 'feComposite']]
        blur, light, composite, arithmetic = filter_node
        assert blur.attrib['stdDeviation'] == '0.9'
        assert light.attrib['surfaceScale'] == '4.29'
        assert light.attrib['specularConstant'] == '0.65'
        assert light.attrib['specularExponent'] == '1.6'
        assert light[0].attrib == {'azimuth': '154', 'elevation': '112'}
        assert composite.attrib['operator'] == 'atop'
        assert arithmetic.attrib['k2'] == '0.8' and arithmetic.attrib['k3'] == '1.2'
        for dpi in manifest['dpi']:
            for prefix, key in [(directory, str(dpi)), (directory / 'flat-control', f'flat-{dpi}')]:
                data = (prefix / 'png' / str(dpi) / f'{case}.png').read_bytes()
                assert data[:8] == b'\x89PNG\r\n\x1a\n'
                width, height, depth, color_type = struct.unpack('>IIBB', data[16:26])
                # Inkscape rounds page pixel dimensions to nearest integer.
                assert width == height == math.floor(16 / 25.4 * dpi + .5)
                assert (depth, color_type) == (16, 6), (case, depth, color_type)
                assert sha(data) == entry['png'][key]['sha256']
                assert entry['png'][key]['nonwhite_pixels'] > 0
                count += 1
    for dpi in manifest['dpi']:
        negative = json.loads((directory / f'flat-control-{dpi}.json').read_text())['metrics']
        assert set(negative) == set(fixtures())
        assert all(x['mae_0_255'] > 0 and x['ssim'] < 1 for x in negative.values())
    print(f'PASS: {len(fixtures())} cases, {count} RGBA16 PNGs, pinned sources, '
          'original filter constants, nonblank output, filter-removal controls detected')


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
