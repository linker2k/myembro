# 새틴 하한 스윕 (2026-10-24)

목적: `StitchTypeRule.SATIN_MIN_MM`(0.9mm)를 계산으로 확정. 좁은 영역을 폭별로 만들어 규칙 하한을 0으로 내린 채
영역 새틴 경로(`RegionSatin`, letter 한글 새틴과 같은 경로)로 강제하고, 앱 기본 PES를 `SewoutCheck`(P1–P8 포함)로 판정했다.

재현: `SATIN_SWEEP=<dir> ./gradlew :engine:test --tests '*SatinMinimumSweep*'` (약 8초)

o = 완전한 새틴 + 차단 0, x = 실패

| 도형 \ 폭(mm) | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 | 1.0 | 1.1 | 1.2 | 1.3 | 1.4 | 1.5 | 1.6 | 1.7 | 1.8 | 1.9 | 2.0 | 2.5 | 3.0 | 4.0 | 5.0 | 6.0 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| bar20 | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o |
| arc | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | x |
| ring | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o |
| zigzag | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | x | o | x | x |
| bar6 | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | x |

실패 내역:

- arc 6.0mm: no complete satin ([TATAMI])
- zigzag 3.0mm: no complete satin ([TATAMI])
- zigzag 5.0mm: no complete satin ([TATAMI])
- zigzag 6.0mm: no complete satin ([TATAMI])
- bar6 6.0mm: no complete satin ([TATAMI])

## 결론

- **게이트로는 하한이 나오지 않는다.** 0.2mm까지 모든 도형이 완전한 새틴이 되고 차단이 0이다. 실패는 모두 넓은 쪽
  (꺾임에서 최대 폭이 6mm를 넘어 규칙이 타타미로 보낸 경우)이라 하한과 무관하다.
- 따라서 0.9mm는 이 스윕으로 확정할 수 없다. 쓸 수 있는 근거는 둘이다.
  - 0.9mm: 사용자 PE-Design 334개 교차 땀 길이 p5 (`/tmp/myembro-quality-gate/auto-rules.md`, 총괄 정책 채택값).
    기둥 폭이 아닌 교차 땀 길이이고, 검출기가 0.7mm 미만을 제외하므로 하한 근거로는 약하다고 같은 문서가 적고 있다.
  - 1mm: Ink/Stitch v3.3.0 `lib/utils/settings.py`의 `min_satin_stroke_width` 기본값 (원본 기본값).
- 판정이 바뀌는 범위(0.9–1.0mm)는 좁다. 어느 값을 쓸지는 결정 사항이다.

