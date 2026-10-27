# DrawingBotV3 default sketch-line oracle

Generated 2026-10-27 with explicit user authorization to execute the original line algorithm.
Source: DrawingBotV3 Free tag `v1.6.25-stable-free`, commit `0e7c900638def19b9a8d16445099f3f1129aee52` (GPL v3).
All inputs are mathematical synthetic rasters defined by `SketchOracleTest`; no upstream image, user image, original Java source, class, or JAR is stored here.

## Reference execution boundary

Original downloaded sources, adapted compile units, harness and bytecode remain only in `/tmp/myembro-dbv3-oracle/`.
The harness compiles **31 Java source units** with the installed JDK 17, no network dependency and no JavaFX or application launch.
It runs the original complete `PFMSketchLinesBasic`, `AbstractSketchPFM`, `SimpleLineSampler`, luminance scorers, ARGBY/ARGB/RawData and darkest-area cache.
Observation-only insertions log selected pixels, candidate endpoints, valid prefix sample counts/scores, raw tile averages and erase amount.
A subclass logs begin/end/progress and pixel writes. Original RNG wrapper bodies are used.
Bresenham line/circle methods, luminance/color helpers and the supported PFMRenderPipe backend/nested sample class are selected verbatim from source.
Desktop PlottingTools/PFMTask/base lifecycle, geometry wrappers and canvas are minimal glue: seed 0, pen width 1F, no task callback, no clipping, no Colour Match, no transforms and no cancellation.
Unsupported image-copy helpers/additive backend throw if invoked. This validates **post-preprocessing raster line generation**, not DrawingBot image loading, presets, pen optimization, arbitrary pen widths, clipping geometry, or its Premium features.

Settings are the registered defaults recorded in `docs/research/thread-texture-port.md`: radius 1, pen width 1F, ARGBY/Bresenham, seed 0, density .75, lengths 2/40, 20 angle tests, default drawing/shading/squiggle/erase values, unlimited line count.

## Event format

- `INPUT width height sha256`: hash of UTF-8 `width height\n` + row-major signed ARGB decimal values separated by single spaces + `\n`.
- `PROGRESS lineBits luminanceBits actualBits`: signed decimal IEEE-754 double bit patterns.
- `TILE x y pixelCount meanBits`: exact running double average, before integer-cast tile comparison.
- `SELECT x y`, `SQUIGGLE_BEGIN index`, `SQUIGGLE_END index`.
- `RNG_F floatBits`, `RNG_I integer`: order is angle then maximum length.
- `CANDIDATE x y`, `SAMPLE x y prefixPixelCount meanFloatBits`, `WIN x y meanFloatBits`.
- `ERASE amount strokeWidthFloatBits`, `PIXEL x y previousArgb nextArgb` (includes writes that leave RGB unchanged).
- `LINE x1 y1 x2 y2 sampledOriginalArgb`, `END reason lineCount`.

All comparisons use exact ordered text including floating-point bits. The test reports the first different event (0-based), not a tolerance or visual judgement.
No mismatch was found, so no line-generation algorithm correction was needed. The production changes in this unit are optional observation hooks.

## Commands and measured output

Re-run the existing authorized local harness (requires its retained `/tmp` files; does not fetch dependencies):

```sh
python3 /tmp/myembro-dbv3-oracle/build.py
/home/gosh2/.jdks/jdk-17.0.20.1+1/bin/javac -d /tmp/myembro-dbv3-oracle/classes $(find /tmp/myembro-dbv3-oracle/src -name '*.java')
/home/gosh2/.jdks/jdk-17.0.20.1+1/bin/java -Djava.awt.headless=true -cp /tmp/myembro-dbv3-oracle/classes oracle.Main /tmp/myembro-dbv3-oracle/inputs /tmp/myembro-dbv3-oracle/out
```

Normal regression execution uses only this repository and frozen text, never upstream code:

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test --tests '*SketchOracleTest' --tests '*SketchLinesTest'
python3 tools/check_thread_texture_oracle.py /tmp/myembro-dbv3-oracle/out engine/build/texture-oracle-port
```

The first command passed 29 tests (10 oracle cases + 19 local contract tests), 0 failures/errors, `BUILD SUCCESSFUL in 2s`.
The comparator output is reproduced below. The full report is `/tmp/myembro-dbv3-oracle/comparison.tsv`.

```text
case	events	lines	first_difference_0based	sha256	result
black	10873	80	none	e575c53d63d22f9d09210ddd2a49eddb0c0fb35ccb28604afd418850e187dc7d	PASS
diagonal	1239	9	none	fe4a7d2c26e0dedd41d3657b0fcef208796427ce9779ade3c3bbb91b64043d89	PASS
dot	258	3	none	5ab5a5575b86476e34bf0f64f576e369f1f5e3383a3662cc4d1ef67ef2090d35	PASS
equal-tiles	10112	68	none	d123cc4e207f9f49d0dd7f8f6fd5977eb5a9969f36b3cbf696ee2326c0e62e2f	PASS
horizontal	1204	9	none	ada1116ffeb263c4c35313a1b64e480515331d29933283813afe50ab1261329d	PASS
ramp	6484	45	none	159c997939f30e48260d6f892ae938078e3344b80815a6a9dc937c742fef0a47	PASS
remainder	12796	75	none	f51411c7b115ddc50ed832bac6fa55949d8216c23cb06bdb7a94a47c017dbf7a	PASS
small	1870	20	none	e3057a0c7a0fb66eea1f49bc3dac4fa6cd8d98684aa4388413a3eed2afac3693	PASS
vertical	1204	9	none	d51d16a7ee6f5c671c8f48d487adc1f787cc81d915247e5a75d8ddf5fa215e0b	PASS
white	3	0	none	c9b41390aafea7d0f1cc163d1f3c43f320e2101ba733caeb2e85d5d4a6e21d3b	PASS
PASS: 10 cases; 46043 events; 318 lines
```

## SHA-256 of original source files read by the harness builder

Pinned raw URL prefix: `https://raw.githubusercontent.com/SonarSonic/DrawingBotV3/0e7c900638def19b9a8d16445099f3f1129aee52/src/main/java/drawingbot/`.
These hashes identify source provenance; no source bodies are included.

| Source | SHA-256 |
|---|---|
| `api/IPixelData.java` | `90de5ee68bd4e01929b158b3b6b0f6b46f741ccb393ef0c0e7d92d4a4da49b53` |
| `api/IPixelDataHSB.java` | `54029d6d8ae951f013b3cb650fb7f7f84468ec0f559ce78a94c2279c995fa721` |
| `api/IPlottingTools.java` | `c7a7b5156ed7976b22c11f37991b27dd1af5e94709728207e15a3f95d2eb798f` |
| `geom/easing/EasingUtils.java` | `ca9f561ad25580b91e9ed476e0466a48d9cbb6bf942e404ef2719570aa51aa66` |
| `image/IPixelListenable.java` | `fa45e974858b16f15d28831b16bad8c66495d14c835fcdd0fcc42c00418184bb` |
| `image/IPixelListener.java` | `83a4d8e4156459b724a69423e2b7d0ee759aec1a0532ac4498b8891bf11e5a86` |
| `image/ImageTools.java` | `ae72d444e4b4d190dd69749008c648a66389a0910f0f2d2d2ae06d437634c752` |
| `image/PixelDataARGB.java` | `a6be43948a24de49e1ab22593908b7dfd0316dc266aa043b5b7859ac97173bb2` |
| `image/PixelDataARGBY.java` | `e12e82fe3994e98236c92d2025e159a8397668865f7197ef0790f1e07e59c298` |
| `image/PixelDataAbstract.java` | `a46d55467bb18ae6626495775bd42544416873c92bfad10d1fcb9c299ba0cca8` |
| `image/PixelTargetCache.java` | `f722d56ceb74575465f9942a669a9f869466ac284b41210a1c96e385a4a24309` |
| `image/PixelTargetDarkestArea.java` | `5b258f46b9fecc1ddc524ca7ba5fe16e677cb8b5be9386c25e28798e3d4d93c2` |
| `image/RawData.java` | `8c427f38fa0d0a647c5bea58664b9635add110e44b80353129fd4bab80c72b6c` |
| `pfm/AbstractSketchPFM.java` | `33cf71903ec47cd642d74928465445b74a096623e9ff1cbb452b8cdc19731ef9` |
| `pfm/PFMSketchLinesBasic.java` | `e2dcc8152e4891334cb16ce89d8fa84f5bf28f5da8625757d2b662b5283d088a` |
| `pfm/helpers/BresenhamHelper.java` | `30b85ea8048175162e9f335d7600d0fe8aef9ce6936eef2bb0ac93756040b24e` |
| `pfm/helpers/ColourSampleTest.java` | `9b6e34a746e6ef5148536106ca1e149db2b351d8ddf96225a4256269544fc371` |
| `pfm/helpers/LuminanceTest.java` | `7a9251ecbac61f72d248dcb87558e791711768b6cc590553193122fe5acfb0a4` |
| `pfm/helpers/LuminanceTestLine.java` | `00d05073f4659324bb32f1f2af9bf7b18a6c84e0e14dbe2477c8f5c92e20ef97` |
| `pfm/helpers/PFMRenderPipe.java` | `216025835a13f9f5ead2b98238347a56e02e03d061b544089a0bc6f924c3f774` |
| `pfm/helpers/PixelTest.java` | `b2f62df2dd9377e53cf3b511d043d2e97e2fc28ba7d92188a0c70d8638d78dec` |
| `pfm/helpers/SimpleLineSampler.java` | `da4b5634dd328383b5358c5b365282911c2e4655c873320457debfd88b73a546` |

Full regression: `./gradlew :engine:test` — 976 tests, 0 failures/errors, `BUILD SUCCESSFUL in 1m 26s` (`/tmp/myembro-dbv3-oracle/engine-test.log`).
