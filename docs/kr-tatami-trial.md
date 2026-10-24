# Korean tatami physical trial

2026-10-24: public bundled Noto Sans KR (OFL), fontTools static weight700.
No private design/font data is included. W, the acceptable minimum stroke width,
is **unconfirmed**: no production threshold or route was changed.

Reproduce: `python3 tools/make_kr_sewtest.py --output /tmp/myembro-sewtest/kr-new`
(requires local fontTools/Pillow and the existing pyembroidery environment).
The generator refuses existing trial names. Never reuse names already sewn.
Copy **only KRSAMP1.PES** to the machine; parts/ contains generation inputs.
The requested one-file trial exceptionally permits90×90mm, rather than60×60mm.
Fill15/20/25mm occupies the left column; matching running outlines the right.
The right25mm item is lower to avoid overlap while preserving physical sizes.
Labels in overview.png are reference annotations and are not stitched.

Final independent command:
```
/tmp/myembro-quality-gate/quality-gate check --style fill --hoop-bounds-mm 0 0 100 100 --json /tmp/myembro-sewtest/kr/sampler-gate.json /tmp/myembro-sewtest/kr/KRSAMP1.PES
```
Output: `PASS 1/1 cases passed`. Encoded bounds(0,0)..(899,899),
PEC header899×899 (0.1mm units), zero motions0, one thread,5357 stitches.
Thus size89.9×89.9mm fits90mm and the100mm hoop with more than2mm size allowance.
Parts plus routing comparisons independently passed9/9. Short-stitch ratios are
reference metrics, not blocking conditions. Final acceptance requires user sewout.

JTS1.20 interior Voronoi vertices of boundary samples at0.1mm spacing measure
2×distance to continuous ink boundary. Sampled minima are corner/tip-sensitive,
not a certified stroke minimum. For15/20/25mm, minima are approximately
0.014/0.017/0.021mm; P10 widths0.578/0.700/0.896mm. W remains unknown.
JUnit StrokeWidthMeasurementTest checks rectangle widths, scaling, holes, empties.

Ink/Stitch v3.3.0 `lib/elements/fill_stitch.py` at
b0edd96311ece82ee48816dc93466274b12c2a9c supplies the comparison routing:
previous endpoint carried forward, polygon ordering from that endpoint, next
polygon nearest-point target, default underpath enabled; trim between characters.
Actual app baseline jumps7/9/9 become3/4/4 at15/20/25mm.
However sewn connections outside ink remain: counts7/4/8 become18/11/9;
lengths15.019/8.842/13.330mm become9.151/8.417/9.179mm.
Therefore reduced jump counts alone do not establish reduced visible thread.
Comparison is experimental, not automatically enabled in production.

Validation: `./gradlew check` BUILD SUCCESSFUL43s;
subsequent `:engine:check` BUILD SUCCESSFUL10s after trial instrumentation.
Machine-readable measurements, placement and independent final gate output are
in [measurements/kr-tatami-trial](measurements/kr-tatami-trial/).

### 글자별 획 폭 — 사용자 봉제 결과 대조용

동일한 Bold700·‘자’ 기준 높이·0.1mm 경계 샘플로 글자별로 다시 측정했다.
단위는 mm이며, 최소값은 끝점·모서리를 포함한 **표본 최소값**이다.
P10은 보조 지표이고, 허용 최소 획 폭 W는 아직 미확정이다.

| 설정 높이 | 정 최소 | 민 최소 | 정 P10 | 민 P10 |
|---:|---:|---:|---:|---:|
| 15 | 0.013666 | 0.095167 | 0.512575 | 0.689934 |
| 20 | 0.017089 | 0.092284 | 0.689254 | 0.891165 |
| 25 | 0.020692 | 0.097608 | 0.873291 | 1.092970 |

계산: 임시 JVM 측정 드라이버가 기존 `KoreanTatamiTrial.paths/region`과
`StrokeWidthMeasurement.measure(..., 0.1)`을 호출했다. 글자별 분리 측정이므로
앞의 ‘정민’ 전체 P10과 다르다. 고정 간격 표본 최소값은 크기에 따라 단조 증가하지
않을 수 있다(예: ‘민’ 15→20mm). 도안·PES·앱 설정 변경 없음.

### KRSAMP7 — 작은 Bold 채우기 시험

사용자 피드백에 따라 러닝 윤곽을 제외하고 Bold700 ‘정민’ 8/10/12mm
세 줄만 생성했다. 결과22.2×36.0mm, 한 색1,976땀, 원점(0,0),
0길이0건, PEC 헤더222×360(0.1mm), 독립 게이트1/1 PASS.
글자별 표본 최소 획 폭과 Regular400 15mm 비교값은
[시험 README](measurements/kr-small-trial/README.md)에 있다.
Regular400은 비교 재현값이며 당시 S23 유효 굵기와의 동일성은 미확인이다.
재현: `python3 tools/make_kr_small_trial.py`.
