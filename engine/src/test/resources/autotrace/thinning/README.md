# AutoTrace original thinning goldens

Source: official **AutoTrace 0.31.10**, fixed commit
`1a232b27818a46a05cd25edec09e2f77c4e9ac01`:
https://github.com/autotrace/autotrace/tree/1a232b27818a46a05cd25edec09e2f77c4e9ac01 .
`src/thin-image.c` SHA-256:
`29a976bc15799ca26462e8b06636bebb689d4a6817d58e9d1040fd20b3c4ccac`.

The original **unmodified** `thin_image` is linked with original `color.c` and
`exception.c` and the host's actual GLib/GObject. A small I/O caller constructs
`at_bitmap`, supplies white background, invokes it and emits a P1 PBM. No custom
thinning, replacement GLib, path extraction, curve fitting, Ink/Stitch execution,
Kotlin port, app change or PES output is involved. C files remain in a clean
read-only reference checkout, not copied into application source.

License: thin-image.c/.h LGPL-2.1-or-later, Copyright (C) 2001, 2002 Martin Weber;
its header credits Joseph M. Cychosz's Graphics Gems IV (1994) article.
Supporting color.c/types.h/autotrace.h carry GPL-2.0-or-later notices.
Upstream `COPYING` and `COPYING.LIB` are preserved here. These permit GPL v3
combination; no blanket claim that the whole AutoTrace project is LGPL.

## Inputs and actual original output

Every PGM is original synthetic/public data, no personal design or photo.
Top-down row order; input P2 values 0=black/255=white, output P1 1=foreground.
Each input runs as both one-plane grayscale and three-plane repeated grayscale
RGB through the corresponding original branches. There are **5 inputs, 10 cases**.

| Input | Size px | Source foreground | Original thinned foreground |
|---|---:|---:|---:|
| bar | 17×17 | 55 | 9 |
| tee | 17×17 | 76 | 15 |
| cross | 21×21 | 133 | 25 |
| loop | 21×21 | 176 | 39 |
| hangul-han | 96×96 | 1,493 | 174 |

The Hangul raster is ‘한’, app's existing Noto Sans KR variable font, **weight
700**, size64, drawn in binary mode by Pillow10.2.0. Font binary unchanged and
not copied here; existing path `app/src/main/res/font/noto_sans_kr.ttf`, SHA-256
`194018e6b2b293a7964f037b25c0249ce1418bc9ab3c971060a03aa57861e252`.
OFL-1.1 notice is preserved in `noto_sans_kr_OFL.txt`. Fixed input PGM, not a new
rasterization on each normal Kotlin test, will be the future port's input.

`manifest.json` records original source/header hashes, caller/generator hashes,
compiler command/version, runtime library hashes, glyph provenance and every
input/output SHA-256. Absolute build paths identify this local run and need not
be identical on another machine. No native binary is committed.

## Reproduce without installing packages

If GLib/GObject development headers already exist, omit `--glib-prefix` and use
pkg-config `gobject-2.0`. On this host only GLib runtime existed. The **matching
Ubuntu official dev archive** was downloaded and extracted under `/tmp` (not
installed; no system changes): `libglib2.0-dev_2.80.0-6ubuntu3.9_amd64.deb`, SHA-256
`2f34111ef1c41179d610049c902e2622d433e648869abf267e17083c13aa6030`.

```sh
# Optional local header preparation, only when matching runtime/headers are needed:
cd /tmp
apt-get download libglib2.0-dev=2.80.0-6ubuntu3.9
dpkg-deb -x libglib2.0-dev_2.80.0-6ubuntu3.9_amd64.deb /tmp/myembro-autotrace-build/glib
# From repository root; source is the fixed clean reference checkout:
PYTHONDONTWRITEBYTECODE=1 python3 tools/autotrace/generate-thinning-goldens.py \
  /tmp/myembro-autotrace-reference --glib-prefix /tmp/myembro-autotrace-build/glib --verify
```

The extracted-prefix option is specific to this x86_64 Linux host's paths. It
uses the installed `libglib-2.0.so.0` and `libgobject-2.0.so.0`, not a shim or new
runtime package. Output: `/tmp/myembro-port-out/editor/autotrace/` including
executable, PGM/PBM, enlarged PNG, manifest and upstream license files.

Executed output:

```text
COMMITTED_GOLDENS_EXACT 10/10
ORIGINAL_THINNING_GOLDENS 10/10; REPEAT 10/10; IDEMPOTENT 10/10; GRAY_RGB 5/5
```

These assertions prove exact reproduction of **this original C thinning stage**,
not full centerline topology/path fitting, Kotlin equivalence or sewout quality.
Kotlin `AutoTraceThinning` now compares all ten original masks byte-for-byte in
`AutoTraceThinningTest`, including independent background state and input preservation.
The original snapshot manifest predates that port and remains unchanged. One additional
boundary test reproduces original C on a fully black 3×3 input: grayscale
`000/111/000`, RGB `000/000/110` (1=foreground); the kernels must retain this distinction.
Next unit: original pixel-path extraction. A future PES must pass P1–P8 independently.
