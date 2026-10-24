# Hangul satin public-font sweep

| Font Regular400 | Cases | Gate PASS | Generation rejected(10/15/20mm) | Engine time(seconds) |
|---|---:|---:|---|---:|
| gowun-dodum | 90 | 90 | 0 / 0 / 0 | 2.364 |
| nanum-gothic | 90 | 90 | 0 / 0 / 0 | 1.340 |
| nanum-myeongjo | 90 | 72 | 8 / 6 / 4 | 1.241 |
| gothic-a1 | 90 | 90 | 0 / 0 / 0 | 0.969 |
| orbit | 90 | 90 | 0 / 0 / 0 | 0.901 |

Total450cases: generated432 / generation rejected18 / external gate BLOCK 0.

Failure reason:
- java.lang.IllegalStateException: No satin section for fitted path: 18cases

All rejected18cases have a minimum path <1 SVG px (0.317607–0.952822px). No section under upstream convert-to-satin's endpoint>=1px condition; if a column cannot be generated, rejects the whole glyph rather than discarding a path to turn it into success.

Check coverage: generated432cases pass the external gate, 0-length needle/JUMP=0, origin min(0,0)/header/hoop checks PASS. Rejected18cases have no PES, so gate/PES 0-length/origin checks were not run.

Maximum needle count per 0.5mm cell 10; engine loop 9.957381596seconds; width measurement 9.743949678959325seconds; raster preparation 0.48675092007033527seconds. Table times sum per-glyph engine calls(including first JVM/JIT·JSON/file writing, excluding Gradle build/width measurement/raster/external gate execution). Gate wall time was not measured separately.

Font/OFL/SHA/width-measurement provenance: fonts.json / width-provenance.json. Public characters30: BBEOL, BBUL, BBEOSS, BBYEO, BBYEOS, BBAES, BBAEG, BBOM, BBONG, BBYO, BBYU, SSWAE, SSWAESS, SSWEOSS, JJOL, JJOC, JJUM, JJUNG, JJEUM, JJEUS, JJALB, CANH, MANH, DALG, GABS, SALM, ANJ, ILG, HALT, EULP

Width is the existing model GlyphStrokeWidths per-glyph15mm median×height/15, with proportional sampling(15mm reference0.1mm). Normalizes each glyph's actual ink height. This calculation is not an app automatic-width rule, recommended font height, or machine-safe range. Existing upstream defaults are unchanged.

Upstream fit→convert-to-satin→SatinColumn→S1→default PES writer path. No app integration. User sewout approval is separate.

## Reproduction and scope

Based on engine `95ce672`. Only computation tools were added; satin/postprocessing algorithms and gates
were unchanged. All inputs/fonts/outputs are in `/tmp/myembro-port-out/letter/hangul-sweep-v1/`.
No app integration; existing KRSAT1 is unchanged. No private files were read.
External gate v7, threshold SHA256
`18bf3e7cc70e872680368efdb3319e8d1794587337eefdb4e9c7cc97a116b95d`.

```sh
# 새 디렉터리를 사용한다. 기존 PES 이름은 덮어쓰지 않는다.
python3 tools/hangul_satin/prepare-sweep.py /tmp/myembro-port-out/letter/hangul-sweep-NEW
# 기존 model GlyphStrokeWidths 컴파일 결과를 /tmp로 복사해 고정·실측한다.
python3 tools/hangul_satin/measure-sweep-widths.py /tmp/myembro-port-out/letter/hangul-sweep-NEW --model-root /home/gosh2/workspace/hoonyjung/myembro-model --model-classpath /tmp/myembro-ttf-fill-sweep-reduced/classpath.txt
python3 tools/hangul_satin/prepare-sweep.py /tmp/myembro-port-out/letter/hangul-sweep-NEW --prepare
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:hangulSatinSweep -PhangulSweepRoot=/tmp/myembro-port-out/letter/hangul-sweep-NEW
/tmp/myembro-quality-gate/quality-gate check --text --style satin --json /tmp/myembro-port-out/letter/hangul-sweep-NEW/gate.json /tmp/myembro-port-out/letter/hangul-sweep-NEW/outputs
python3 tools/hangul_satin/summarize-sweep.py /tmp/myembro-port-out/letter/hangul-sweep-NEW
```

Retrieved and checked OFL text from pinned public Google Fonts commits; no remote code was executed.
Selected five regular400 fonts as thin-stroke candidates; this sweep does not determine minimum stroke thickness
or usability grades for all Hangul fonts. Raster font size128px;
normalized per-glyph ink height to physical height. Resolution convergence verification is outside scope.
Nanum Myeongjo failures occurred for BBUL, BBEOSS, BBYEOS, BBOM, BBONG, BBYO, BBYU, GABS.
If even one path fails conversion, the whole glyph fails; partially generated satin is not exported as a completed glyph.
No changes were made to discard short serif paths or arbitrarily replace them with running stitches.
Failure details are in `summary.json` and per-glyph `.log` files.

Validation: `./gradlew check` BUILD SUCCESSFUL in1m1s(engine650·app Debug/Release each205,
failures0·Lint passed). `/tmp/myembro-port-out/letter/hangul-sweep-v1/check.log`.
`SWEEP_ASSERT 450 inputs / 432 gate PASS / 18 generation rejected / 432 zero-motion=0 / 432 origin=(0,0)`.
