# Grayscale option for color drawings

Added a single ko/en grayscale toggle beside the color-count slider. The 2–6 levels and existing
size slider are retained. Changing the toggle rebuilds the preview from the original bitmap.
Stitch generation runs only at the result stage.

- [Color preview](color-preview-ko.png)
- [Grayscale preview, toggle, and size](grayscale-preview-ko.png)
- [Grayscale result and required threads](grayscale-result-ko.png)

Screenshots use a synthetic face drawn directly by instrumentation; no personal images/emoticons
were bundled. Development, installation, and screenshots used **emulator-5556 only**. Nothing
was installed on release 5554 or canvas 5558.

## Standard and matching basis

The code-value luma-signal formula from [ITU-R BT.709-6](https://www.itu.int/rec/R-REC-BT.709),
[original PDF §3.2·3.5](https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.709-6-201506-I%21%21PDF-E.pdf):

`Y′ = round(0.2126 R′ + 0.7152 G′ + 0.0722 B′)`

Input is the app-decoded 8-bit RGB. The implementation rounds using
`(2126*R + 7152*G + 722*B + 5000)/10000` and preserves alpha. Code value Y′ is replicated into
all three RGB channels. It is not reinterpreted as linear-light Y or the video-limited 16–235 range.
Both pre-quantization background separation and final Wu N-level quantization receive this grayscale
input. The standard Wu algorithm, area cleanup, shared classification, and per-region fallback are unchanged.

Only the 6 achromatic-family colors in the fixed Brother PEC table are candidates. Their names/
catalog numbers are explicitly listed rather than inventing a hue threshold:

| Number | Brother name |
|---:|---|
| 20 | Black |
| 23 | Dark Gray |
| 19 | Pewter |
| 39 | Gray |
| 16 | Silver |
| 29 | White |

Each Wu grayscale representative color is matched to the minimum `PecThreads.distance` in this
set. Actual thread-table Dark Gray/Pewter RGB values do not satisfy exact R=G=B, but are supplier-
named grays; colored threads such as Warm Gray/Beige are not candidates. Similar quantization
levels may match the same thread, leaving fewer required threads than selected levels.

## Automated decisions

`Bt709GrayscaleTest`:

- Assert primary-color references red=54, green=182, blue=18, and alpha preservation.
- Assert that all 256 gray inputs remain unchanged and all 256 match the nearest color within the restricted candidates.
- Assert gray palettes, budget compliance, and achromatic-thread restriction for each Wu 2–6 setting.
- Assert 0 exclusions, needles present, and no sew-out gate blockers for synthetic PNG → grayscale PES.

`ColorDrawingSmokeInstrumentation`:

- Color → grayscale → restored color from the same original.
- Retain color count 3 and selected size; required grayscale threads stay within the candidates above, with 0 exclusions and a passing sew-out check.
- Actual Compose before/after screens and actual PES saving.

```text
BUILD SUCCESSFUL in 50s
engine tests=738 failures=0
app debug/release tests=234 each, failures=0
GRAYSCALE_UI_SMOKE_PASS color_stitches=1502 gray_stitches=1493
threads=[(23, Dark Gray), (16, Silver), (19, Pewter)]
toggle_off_restores_color=true
INSTRUMENTATION_CODE: 0
PASS 3/3 cases passed
```

Reproduction:

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
GRAYSCALE_OUT=/tmp/myembro-port-out/editor/grayscale ./gradlew check :app:assembleDebug
# 5556에만 테스트 APK를 설치하고 ColorDrawingSmokeInstrumentation 실행.
/tmp/myembro-quality-gate/quality-gate check \
 /tmp/myembro-port-out/editor/grayscale/grayscale-face.pes \
 /tmp/myembro-port-out/editor/grayscale/android-color-face.pes \
 /tmp/myembro-port-out/editor/grayscale/android-grayscale-face.pes \
 --style mixed --json /tmp/myembro-port-out/editor/grayscale/gate.json
```

Logs: `/tmp/grayscale-final-check.log`, `/tmp/grayscale-android-smoke.log`, `/tmp/grayscale-gate.log`.
Physical USB transfer/actual sewing are not included in this smoke test.
