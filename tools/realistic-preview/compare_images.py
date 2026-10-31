#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compare same-page, same-DPI PNGs, without registration or rescaling.

Requires Pillow, numpy, scikit-image in a user-local virtualenv.
SSIM: Wang et al. (2004), scikit-image gaussian_weights=True, sigma=1.5,
use_sample_covariance=False, K1=.01, K2=.03, data_range=255, RGB channels.
Transparent pixels are composited on white before metrics; no ICC transform.
Pillow's RGBA decode uses 8 bits/channel; these metrics do not test 16-bit LSBs.
No acceptance tolerance is invented: metrics are reported, not a quality gate.

python compare_images.py --reference /tmp/myembro-realistic-ref/png/300 \
  --candidate /tmp/myembro-realistic-ref/flat-control/png/300 --out /tmp/comparison.json
python compare_images.py --self-test
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image
from skimage.metrics import structural_similarity


def read(path):
    with Image.open(path) as image:
        rgba = image.convert('RGBA')
        white = Image.new('RGBA', rgba.size, 'white')
        return np.asarray(Image.alpha_composite(white, rgba).convert('RGB'))


def metrics(reference, candidate):
    if reference.shape != candidate.shape:
        raise ValueError(f'Pixel dimensions differ: {reference.shape} != {candidate.shape}')
    delta = reference.astype(np.float64) - candidate.astype(np.float64)
    absolute = np.abs(delta)
    ssim = structural_similarity(reference, candidate, channel_axis=2, data_range=255,
                                 gaussian_weights=True, sigma=1.5, use_sample_covariance=False)
    return dict(mae_0_255=float(absolute.mean()), rmse_0_255=float(np.sqrt(np.mean(delta ** 2))),
                max_channel_error=int(absolute.max()), ssim=float(ssim),
                changed_pixels=int(np.any(absolute != 0, axis=2).sum()),
                total_pixels=int(reference.shape[0] * reference.shape[1]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path)
    parser.add_argument('--candidate', type=Path)
    parser.add_argument('--out', type=Path)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        # Nonconstant synthetic RGB validates identity and negative controls.
        a = np.arange(32 * 32 * 3, dtype=np.uint8).reshape(32, 32, 3)
        same, different = metrics(a, a), metrics(a, 255 - a)
        assert same['mae_0_255'] == 0 and same['ssim'] == 1 and same['changed_pixels'] == 0
        assert different['mae_0_255'] > 0 and different['ssim'] < 1
        try:
            metrics(a, a[:20])
        except ValueError:
            pass
        else:
            raise AssertionError('Dimensions mismatch accepted')
        print('PASS: identity MAE=0 SSIM=1; changed image detected; dimensions mismatch rejected')
        return
    if not args.reference or not args.candidate or not args.out:
        parser.error('--reference, --candidate and --out required')
    result = {}
    for reference in sorted(args.reference.glob('*.png')):
        candidate = args.candidate / reference.name
        if not candidate.is_file():
            raise FileNotFoundError(candidate)
        result[reference.stem] = metrics(read(reference), read(candidate))
    if not result:
        raise ValueError('No reference PNGs')
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(dict(metrics=result, decoder='RGBA8 composited on white',
                                       ssim='scikit-image Wang Gaussian sigma=1.5 population covariance'), indent=2) + '\n')
    for name, value in result.items():
        print(f'{name:14} MAE={value["mae_0_255"]:.6f} SSIM={value["ssim"]:.8f} changed={value["changed_pixels"]}')
    print(f'COMPARED: {len(result)} images (no quality threshold applied)')


if __name__ == '__main__':
    main()
