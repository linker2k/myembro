#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""20 original synthetic drawings x five raster sizes; never loads personal images.

Pillow is used only by this offline fixture generator, not the engine or app.
Bilinear sampling is explicit, not claimed pixel-identical to Android filtering.
"""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import subprocess

LEVELS = [128, 160, 192, 224, 256]
ROOT = Path(__file__).resolve().parents[2]


def intervals(levels):
    """Only join adjacent tested levels; never bridge an untested/failed hole."""
    groups = []
    for level in levels:
        if groups and LEVELS.index(level) == LEVELS.index(groups[-1][-1]) + 1:
            groups[-1].append(level)
        else:
            groups.append([level])
    return groups


def policy(results):
    assert all(r['status'] in {'pass', 'blocked', 'generation_error'} for r in results)
    expected = {(r['id'], r['category']) for r in results}
    assert len(expected) == 20 and len(results) == 100
    assert len({(r['id'], r['edgePx']) for r in results}) == 100
    assert all(sorted(r['edgePx'] for r in results if r['id'] == name) == LEVELS for name, _ in expected)
    allowed = [edge for edge in LEVELS if all(r['status'] == 'pass' for r in results if r['edgePx'] == edge)]
    return {
        'schemaVersion': 1,
        'scope': 'Synthetic corpus only; not a guarantee for arbitrary photographs',
        'appliedToApp': False,
        'testedEdgePx': LEVELS,
        'currentUiEdgePx': [128, 192, 256],
        'enabled': bool(allowed),
        'allowedEdgePx': allowed,
        'allowedContiguousTestedLevels': intervals(allowed),
        'removedEdgePx': [edge for edge in LEVELS if edge not in allowed],
        'rule': 'Intersection of successful generation, complete analysis and zero SewoutCheck blockers across ALL 20 inputs; warnings retained; no interpolation',
        'perFixture': {name: [r['edgePx'] for r in results if r['id'] == name and r['status'] == 'pass'] for name, _ in sorted(expected)},
    }


def generate(output):
    from PIL import Image, ImageDraw
    fixtures = []
    def drawing(name, category, paint):
        im = Image.new('RGB', (512, 512), 'white')
        paint(ImageDraw.Draw(im))
        fixtures.append((name, category, im))
    drawing('square', 'shape', lambda d: d.rectangle((112,112,399,399), fill='black'))
    drawing('disk', 'shape', lambda d: d.ellipse((112,112,399,399), fill='black'))
    drawing('triangle', 'shape', lambda d: d.polygon([(256,90),(420,410),(92,410)], fill='black'))
    star = [(256 + (165 if i%2 == 0 else 72)*math.cos(-math.pi/2+i*math.pi/5),
             256 + (165 if i%2 == 0 else 72)*math.sin(-math.pi/2+i*math.pi/5)) for i in range(10)]
    drawing('star', 'shape', lambda d: d.polygon(star, fill='black'))
    drawing('ring', 'linework', lambda d: d.ellipse((90,90,422,422), outline='black', width=12))
    drawing('zigzag', 'linework', lambda d: d.line([(80,350),(150,160),(220,350),(290,160),(360,350),(430,160)], fill='black', width=10))
    drawing('cross', 'linework', lambda d: (d.line((90,256,422,256), fill='black', width=12), d.line((256,90,256,422), fill='black', width=12)))
    spiral = [(256+(12+i*.65)*math.cos(i*.12),256+(12+i*.65)*math.sin(i*.12)) for i in range(240)]
    drawing('spiral', 'linework', lambda d: d.line(spiral, fill='black', width=8))
    # Original geometric stroke glyphs, no font files or third-party drawings.
    glyphs = {
        'A': [[(130,400),(256,100),(382,400)],[(185,280),(327,280)]],
        'H': [[(140,100),(140,400)],[(372,100),(372,400)],[(140,250),(372,250)]],
        'M': [[(110,400),(110,100),(256,280),(402,100),(402,400)]],
        'Z': [[(120,110),(392,110),(120,400),(392,400)]],
    }
    for letter, paths in glyphs.items():
        drawing('glyph-'+letter, 'letter', lambda d, paths=paths: [d.line(p, fill='black', width=18) for p in paths])
    for width in [1,2,3,4]:
        drawing('thin-'+str(width), 'thin_line', lambda d, width=width: d.line((100,120,412,392), fill='black', width=width))
    for radius in [1,2,3,4]:
        drawing('dot-'+str(radius), 'small_dot', lambda d, r=radius: d.ellipse((256-r,256-r,256+r,256+r), fill='black'))
    assert len(fixtures) == 20
    (output/'inputs').mkdir(parents=True, exist_ok=True)
    (output/'sources').mkdir(exist_ok=True)
    manifest = []
    for name, category, im in fixtures:
        im.save(output/'sources'/f'{name}.png')
        for edge in LEVELS:
            raw = im.resize((edge,edge), Image.Resampling.BILINEAR).tobytes()
            (output/'inputs'/f'{name}-{edge}.rgb').write_bytes(raw)
            manifest.append({'id':name, 'category':category, 'edgePx':edge})
    (output/'inputs.json').write_text(json.dumps(manifest, indent=2)+'\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('/tmp/myembro-port-out/editor/sweep'))
    args = parser.parse_args()
    output = args.output.resolve()
    generate(output)
    subprocess.run([str(ROOT/'gradlew'), ':engine:runRedworkSweep', f'-PredworkSweepOutput={output}'], cwd=ROOT, check=True)
    results = json.loads((output/'results.json').read_text())
    restriction = policy(results)
    (output/'detail-policy.json').write_text(json.dumps(restriction, indent=2)+'\n')
    from PIL import __version__ as pillow_version
    summary = {
        'pillowVersion': pillow_version,
        'commit': subprocess.check_output(['git','rev-parse','HEAD'], cwd=ROOT, text=True).strip(),
        'workingTreeDirty': bool(subprocess.check_output(['git','status','--porcelain'], cwd=ROOT, text=True).strip()),
        'cases':len(results), 'counts':dict(Counter(r['status'] for r in results)),
        'byEdgePx':{str(e):dict(Counter(r['status'] for r in results if r['edgePx']==e)) for e in LEVELS},
        'blockerCodes':dict(Counter(f['code'] for r in results for f in r.get('findings',[]) if f['severity']=='BLOCKER')),
        'warningCodes':dict(Counter(f['code'] for r in results for f in r.get('findings',[]) if f['severity']=='WARNING')),
        'generationErrors':[{k:r[k] for k in ['id','edgePx','error']} for r in results if r['status']=='generation_error'],
        'allowedEdgePx':restriction['allowedEdgePx'], 'enabled':restriction['enabled'],
        'configuration':{'hoopMm':[100,100], 'bitmapWidthMm':100, 'backgroundPixelIndex':0,
                         'machine':'SE400', 'pesVersion':1, 'hotspotPenetrations':16, 'placement':'HOOP_COORDINATES'},
        'sampling':'Pillow bilinear from original 512x512 black/white synthetic drawings',
        'resultsSha256':hashlib.sha256((output/'results.json').read_bytes()).hexdigest(),
    }
    (output/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    print(json.dumps(summary, indent=2))
    print('DETAIL_POLICY', output/'detail-policy.json')
    print('SWEEP_COMPLETE', len(results), 'cases')


if __name__ == '__main__':
    main()
