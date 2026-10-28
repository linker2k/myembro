# Integer centering for photo block editing

Base: main `8671efe`. The centered hoop accepts indices
`[-floor(W/2), W-1-floor(W/2)]` (`SewoutLimits.placedFits`); H100 is -500..499.
A 999-unit-wide generated photo could instead have needle bounds -499..500 because
SVG-to-native conversion rounds the +499.5 endpoint upward. Its exported PES fits
0..999, while the editor's placed bounds previously failed `HOOP_SAFETY_MARGIN`.

`integerHoopCenter` computes each integer midpoint as
`Math.floorDiv(min.toLong() + max + 1, 2L)`. Subtracting it gives the required negative
half-unit bias for odd extents, without changing even extents or adding a margin. The divisor is explicitly Long so
the bytecode calls Android API 24 `floorDiv(long,long)`, not API 31 `floorDiv(long,int)`
(the app minimum API is 26):

| Input | Center to subtract | Result |
|---|---:|---|
| -499..500 | 1 | -500..499 |
| -500..499 | 0 | -500..499 |
| -425..425 | 0 | -425..425 |

`StitchObject.recenter` now uses this common midpoint and the existing
`GeneratedPatternProcessor.mapCoordinates` (including lock/source-coordinate metadata).
Imported source patterns, original source bytes, commands and relative needle vectors
are retained. The odd midpoint tie deliberately follows the hoop convention rather
than truncation toward zero. Normalized output PES remains identical for the imported
redistributable regression sample.

`ShapeObjectFactory.fromPhoto` sets an integer pose from the same local needle bounds.
Source geometry, cached source stitches, per-region fragments, threads and sewing order
remain unchanged; the object is not regenerated. `centeredForImport` sets that pose
absolutely, so calling it from both the factory and MainActivity's picture insertion/rename
branch does not apply the translation twice. Agent-prepared photo/emoji insertion uses
the same helper rather than overriding it with the old midpoint calculation.
Stored project decoding and normal transforms continue to preserve the saved user pose.

## Verification

`IntegerHoopCenterTest` covers the three examples above, idempotence, 30 combinations
of 998/999/1000 extents and positive/negative odd/even offsets, odd/even hoop dimensions,
imported PES byte/output preservation, and photo rename/codec/fragment preservation.
1000 units remain blocked by the existing H100 rule; no safety condition is relaxed.

`PhotoHoopCenterTest` crops the repository-owned `launcher-icon-artwork.png` to its
central 640px square, resamples to 192px, applies median 5/contrast 1.0, requests four
colors and 99.9mm, then calls the real app `BlockApply.check`. Local JVM tests access
JDK desktop image APIs reflectively because Android's compilation bootclasspath omits
them. This is a JVM crop reproduction, not a claim of Android pixel-for-pixel parity.

```sh
HOOP_CENTER_REPRO_OUT=/tmp/myembro-hoop-center \
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 \
ANDROID_HOME=/home/gosh2/Android/Sdk \
./gradlew :engine:test :app:testDebugUnitTest
```

Measured output:

```text
INTEGER_CENTER_BOUNDARIES 30/30 PASS: 998/999 fit; 1000 blocked
HOOP_CENTER_REPRO crop=640 width=99.9
source=(-499,-499)..(500,500)
placed=(-500,-500)..(499,499) transform=(-1,-1)
stitches=17505/17505 result_PES=PASS editor_PES=PASS BlockApply=PASS
result/editor PES SHA256=01a63eb58b86cf8d5f08b2116d6b231511e23968d1e900319204d27027522020
```

When the output variable is supplied, the app unit test writes `result.pes` and
`editor.pes` to that directory. The repository test artwork is redistributable;
no personal design is used or committed.

## Existing regression fixture adjustments

The tool 999-boundary test and JSON scenario previously added a -1-unit X pose to
compensate for the old 0..999 -> -499..500 import center. They now use the common
midpoint / zero X pose, preserving their intended placed -500..499 bounds and the
same boundary PES hash. The 1000-unit rejection remains unchanged.

The rotated logo scenario's V6 hash changes from
`12ae343b1b68d9c7f037d44542f47428a65fccff2fea1d6c7c78b768566c0efc` to
`b4105ac6ad4ac9ecc4daacc4b8c7e431a4b28b671ae690fe6f15f9d572642ea7`.
A separate before/after JVM run plus pyembroidery comparison found 2815 commands,
2810 needles and zero command differences in both files. Of those command positions,
7 X coordinates and 5 Y coordinates change by -1 native unit (0.1mm); the other
2803 positions are identical. The changed integer midpoint interacts with the existing
floating-point 90-degree rotation/rounding. This is an explicitly recorded rotated-output
difference, not a claim that every transformed import remains byte-identical. No rotation
algorithm or output gate was changed in this fix. The unrotated import regression asserts
identical normalized output bytes and preserved source bytes. Local decomposition:
`/tmp/myembro-hoop-center/logo-parity.json` and `logo-before/`, `logo-after/`.

The recorded chatbot prompt simulation keeps its historical physical logo pose with a
1-unit Y offset in the test setup; prompts/replies are not regenerated or recollected.
This isolates the prompt corpus from the intentionally changed import midpoint.
