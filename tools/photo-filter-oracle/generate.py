#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Run pinned originals, never the Kotlin port. No network or installation here."""
import argparse
import hashlib
import importlib.metadata
import inspect
import json
from pathlib import Path
import platform
import sys
from unittest.mock import patch

import numpy as np
from PIL import Image, ImageOps
import skimage.filters.thresholding as thresholding

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DEFAULT_OUTPUT = ROOT / 'engine/src/test/resources/photo-filters'
SIZES = (128, 192, 256)
PINS = {
    'scikit-image': dict(version='0.25.2', commit='e8a42ba85aaf5fd9322ef9ca51bc21063b22fcae',
        source='skimage/filters/thresholding.py', sha256='d0db935f85506c0daf44337c56a01d20de07953901f88b61059d5db12a1a65fd', license='BSD-2-Clause'),
    'Pillow': dict(version='11.3.0', commit='89f1f4626a2aaf5f3d5ca6437f41def2998fbe09',
        source='src/PIL/ImageOps.py', sha256='03af6a8edfa6c4323df77f3bcffe1c1c8fb0b47f3948b2ff10d4c8f4478e4062', license='MIT-CMU'),
    'scipy': dict(version='1.15.2', commit='0f1fd4a7268b813fa2b844ca6038e4dfdf90084a'),
    'numpy': dict(version='2.2.4', commit='3b377854e8b1a55f15bda6f1166fe9954828231b'),
}

def sha(data):
    return hashlib.sha256(data).hexdigest()

def dump(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + '\n')

def synthetic(n):
    y, x = np.indices((n, n), dtype=np.int32)
    # Seven L and five RGB patterns. All generation arithmetic is explicitly integer.
    yield 'gray-black', np.zeros((n, n), dtype=np.uint8)
    yield 'gray-constant', np.full((n, n), 127, dtype=np.uint8)
    yield 'gray-white', np.full((n, n), 255, dtype=np.uint8)
    yield 'gray-bimodal-tie', np.where(x < n // 2, 32, 224).astype(np.uint8)
    yield 'gray-ramp', ((x + y) * 255 // (2 * n - 2)).astype(np.uint8)
    yield 'gray-narrow-range', (128 + (x + 3*y) % 8).astype(np.uint8)
    impulse = np.full((n, n), 100, dtype=np.uint8)
    impulse[0, :] = 4
    impulse[:, -1] = 244
    impulse[n//2, n//2] = 255
    impulse[n//2, n//2-1] = 0
    yield 'gray-impulse-border', impulse
    yield 'rgb-channel-ranges', np.stack((3+x*238//(n-1), 64+y*16//(n-1), np.full_like(x, 113)), axis=-1).astype(np.uint8)
    yield 'rgb-narrow-range', np.stack((32+(x+y)%7, 77+(2*x+y)%7, 120+(x+y)%2), axis=-1).astype(np.uint8)
    colors = np.array(((0, 0, 0), (255, 255, 255), (255, 0, 0), (0, 255, 0), (0, 0, 255), (128, 128, 128)), dtype=np.uint8)
    yield 'rgb-checker-primaries', colors[((x//8) + (y//8)) % len(colors)]
    # Explicit PCG64 and per-size seed avoid dependence on default_rng's default generator.
    yield 'rgb-noise', np.random.Generator(np.random.PCG64(20261025+n)).integers(0, 256, (n, n, 3), dtype=np.uint8)
    # Long edge exactly n, short edge 3. Odd, narrow raster with different channel behavior.
    yy, xx = np.indices((n, 3), dtype=np.int32)
    yield 'rgb-narrow-odd', np.stack((yy*255//(n-1), (xx*127+yy)%256, np.full_like(yy, 47)), axis=-1).astype(np.uint8)

def image_array(image):
    # Convert RGB to the app's existing integer BT.709 luma, not a hidden skimage color default.
    pixels = np.array(image, dtype=np.uint8)
    if image.mode == 'L':
        return pixels.copy()
    rgb = pixels.astype(np.uint32)
    return ((2126*rgb[..., 0] + 7152*rgb[..., 1] + 722*rgb[..., 2] + 5000)//10000).astype(np.uint8)

def otsu(luma):
    # Observe original locals on return instead of reproducing cumulative arithmetic in the oracle.
    intermediates = {}
    def observer(frame, event, arg):
        if frame.f_code is thresholding.threshold_otsu.__code__ and event == 'return':
            for name in ('counts', 'bin_centers', 'weight1', 'weight2', 'mean1', 'mean2', 'variance12'):
                value = frame.f_locals.get(name)
                if isinstance(value, np.ndarray):
                    intermediates[name] = dict(dtype=str(value.dtype), values=value.tolist())
    previous = sys.getprofile()
    try:
        sys.setprofile(observer)
        threshold = thresholding.threshold_otsu(luma)  # nbins=256, hist=None: original defaults
    finally:
        sys.setprofile(previous)
    assert threshold == thresholding.threshold_otsu(luma)
    mask = np.where(luma <= threshold, 0, 255).astype(np.uint8)
    return int(threshold), mask, intermediates

def autocontrast(image):
    captured = []
    original = ImageOps._lut
    def observe(im, lut):
        captured.append(list(lut))
        return original(im, lut)
    with patch.object(ImageOps, '_lut', observe):
        output = ImageOps.autocontrast(image)  # all five upstream defaults
    assert len(captured) == 1
    assert output.tobytes() == ImageOps.autocontrast(image).tobytes()
    return output, captured[0]

def check_environment():
    wheels = json.loads((HERE/'wheels.json').read_text())
    for dependency in wheels:
        actual = importlib.metadata.version(dependency['name'])
        if actual != dependency['version']:
            raise RuntimeError(f"Wrong version: {dependency['name']} {actual} != {dependency['version']}")
    for name, module in (('scikit-image', thresholding), ('Pillow', ImageOps)):
        actual = sha(Path(inspect.getsourcefile(module)).read_bytes())
        if actual != PINS[name]['sha256']:
            raise RuntimeError(f'Original source hash mismatch for {name}: {actual}')
    assert str(inspect.signature(thresholding.threshold_otsu)) == '(image=None, nbins=256, *, hist=None)'
    return wheels

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    wheels = check_environment()
    out = args.output.resolve()
    # Never replace an existing fixture set silently; use a fresh dir for regeneration comparisons.
    if out.exists():
        parser.error(f'Output already exists: {out}; generate into a fresh --output directory')
    out.mkdir(parents=True)
    artwork_path = ROOT/'docs/images/launcher-icon-artwork.png'
    artwork_bytes = artwork_path.read_bytes()
    source = Image.open(artwork_path).convert('RGB')
    cases = []
    for size in SIZES:
        inputs = [(name, Image.fromarray(array)) for name, array in synthetic(size)]
        inputs.append(('launcher-artwork', source.resize((size, size), Image.Resampling.LANCZOS)))
        for pattern, image in inputs:
            case_id = f'{pattern}-{size}'
            folder = out/case_id
            folder.mkdir()
            image.save(folder/'input.png', optimize=False, compress_level=9)
            # Freeze the exact bytes upstream reads; resizing and RGB-to-L conversion are not filter parity claims.
            luma = image_array(image)
            Image.fromarray(luma).save(folder/'otsu-input.png', optimize=False, compress_level=9)
            threshold, mask, intermediates = otsu(luma)
            Image.fromarray(mask).save(folder/'otsu-mask.png', optimize=False, compress_level=9)
            contrast, lut = autocontrast(image)
            contrast.save(folder/'autocontrast.png', optimize=False, compress_level=9)
            expected = dict(id=case_id, pattern=pattern, longEdgePx=size, width=image.width, height=image.height,
                mode=image.mode, dtype='uint8', inputPixelSha256=sha(image.tobytes()),
                otsu=dict(threshold=threshold, thresholdDtype=str(np.asarray(thresholding.threshold_otsu(luma)).dtype),
                    inputPixelSha256=sha(luma.tobytes()), maskPixelSha256=sha(mask.tobytes()),
                    inkPixels=int(np.count_nonzero(mask == 0)), polarity='input <= threshold is ink (0); background is 255',
                    intermediates=intermediates),
                autocontrast=dict(pixelSha256=sha(contrast.tobytes()), lut=lut, histogram=image.histogram()))
            dump(folder/'expected.json', expected)
            cases.append(case_id)
    provenance = dict(formatVersion=1, researchDocument=dict(branch='docs/photo-filters', commit='582f447', path='docs/photo-filters.md'),
        repositoryBaseline='ae576e188cc6d6d1e62b71a06de97fc4e53e2125',
        upstream=PINS, wheels=wheels, python=platform.python_version(), platform=platform.system()+'-'+platform.machine(),
        generatorSha256=sha(Path(__file__).read_bytes()), requirementsSha256=sha((HERE/'requirements.lock').read_bytes()),
        artwork=dict(path='docs/images/launcher-icon-artwork.png', sha256=sha(artwork_bytes), license='GPL-3.0-or-later', resize='Pillow 11.3.0 RGB LANCZOS to the recorded exact input PNG'),
        defaults=dict(otsu=dict(image='2D uint8', nbins=256, hist=None), autocontrast=dict(cutoff=0, ignore=None, mask=None, preserve_tone=False)),
        inputPreparation=dict(colorOtsu='existing app BT.709: (2126*r+7152*g+722*b+5000)//10000; recorded as otsu-input.png',
            alpha='none: input PNGs use L/RGB only', sizes=list(SIZES), syntheticPatterns=12, cases=39),
        byteOrder='uint8, row-major, L or interleaved R/G/B; PNG masks are L with 0 ink / 255 background')
    dump(out/'provenance.json', provenance)
    # The manifest hashes every generated file except itself. No machine-local absolute paths or timestamps.
    files = {str(p.relative_to(out)): dict(sha256=sha(p.read_bytes()), bytes=p.stat().st_size) for p in sorted(out.rglob('*')) if p.is_file()}
    dump(out/'manifest.json', dict(formatVersion=1, cases=cases, files=files))
    print(f'ORIGINAL_GOLDENS PASS cases={len(cases)} files={len(files)+1} bytes={sum(p.stat().st_size for p in out.rglob("*") if p.is_file())}')

if __name__ == '__main__':
    main()
