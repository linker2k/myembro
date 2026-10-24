# AutoTrace pixel centerline oracle

AutoTrace 0.31.10 @ 1a232b27818a46a05cd25edec09e2f77c4e9ac01.
Unmodified `src/thin-image.c` then `src/pxl-outline.c::find_centerline_pixels`.
Project GPL-2.0-or-later (COPYING in ../thinning/); thinning LGPL-2.1-or-later.
Inputs: ../thinning/{bar,tee,cross,loop,hangul-han}.pgm (synthetic + OFL Noto Sans KR).

Format: path count, then for each path `open clockwise r g b pointCount`,
followed by ordered `x y` integer Cartesian pixel coordinates (y upwards).
No sorting, resampling or normalization. These are pixel paths, not stitch coordinates.

Reproduce from repository root:
```
python3 tools/autotrace/generate-centerline-goldens.py /tmp/myembro-autotrace-reference --glib-prefix /tmp/myembro-autotrace-build/glib --verify
./gradlew :engine:test --tests '*AutoTraceCenterlineTest'
```
Output: /tmp/myembro-port-out/editor/autotrace/centerline/.
manifest.json records source, tool and fixture hashes plus the original C compile command.
No Ink/Stitch execution, native dependency installation or personal designs.
