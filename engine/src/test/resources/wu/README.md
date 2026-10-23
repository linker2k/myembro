# Wu v2 original C comparison fixtures

Source: Xiaolin Wu, `gemsii/quantizer.c`, Graphics Gems II (1991 algorithm, v2 C code).
Fixed mirror commit: `ca4898517a93b6b45444a9cda5fd50132694b864`.
URL: https://github.com/erich666/GraphicsGems/blob/ca4898517a93b6b45444a9cda5fd50132694b864/gemsii/quantizer.c
Original C SHA-256: `38b0963722bc3346b18ba58695c87bb7beeb375278f1abd0af05e46933d2a8f6`.
License/author notice: `engine/src/main/resources/licenses/graphics-gems/`.
Synthetic RGB fixtures are original myembro test inputs; no personal designs/photos.

Reproduce without downloads or new dependencies:

```sh
python3 tools/wu/generate-goldens.py /path/to/GraphicsGems/gemsii/quantizer.c
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*ImageQuantizerOracleTest'
```

The script checks the source hash, fills **only the existing main input/output placeholders**,
and compiles with `cc -std=gnu89 -O0 -ffp-contract=off ... -lm` on LP64 Linux.
Original histogram, moments, variance, cuts, partition loop, LUT and index mapping are unchanged.
Goldens capture the actual original `lut_r/g/b` and `Qadd`, not reconstructed means or Kotlin output.
Outputs: `/tmp/myembro-port-out/editor/wu/`; normal Gradle tests read committed goldens and
need no C compiler/process. RGB text format: pixel count, RGB24 hex pixels, requested color count.

Three cases: primaries (12 pixels, request 6, original stops at 3); deterministic 8×8 gradient
(request 4); 192 RGB values at 5-bit bucket boundaries (request 6, float moment rounding).
Assertions compare the ordered palette and **every pixel index exactly**, with no tolerance,
plus deterministic repeats, source preservation and defensive output copies.
This produces colors only, no stitches or PES, and makes no sew-out gate claim.

## SHA-256

- `primaries.rgb`: `fcf6c0bffb008230ce8a612be325b35f364b0ccbf7d85302bd4d84260486b45a`
- `primaries.json`: `4dff05138c71f8c5c99359a8a481dafe9ce6f8935332e54e97520ac3a62f8330`
- `gradient.rgb`: `bb2563aaa64037c59d2e0bb6d6f8eb6e181a9b46dd0967725e2bea169f03af47`
- `gradient.json`: `b7e9e5d310b7971b9bfa878ecad0a72ae043c4307ade7aab2ec1b910fa09b57c`
- `bucket-boundaries.rgb`: `030494bdad4e6a9162fd9e3f2bcf1b01ad175c1ac95cc3635331143a2b54ee4f`
- `bucket-boundaries.json`: `3bb0097d8ae189cd2fd42979c7a66c77591f9cf113c74bbb9099e6ae5f103769`
