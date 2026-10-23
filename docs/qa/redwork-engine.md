# 단색 레드워크 엔진 1차

앱 미연결. 직접 만든 PBM 2개만 사용하며 개인 사진/도안은 없다.

## 실행 경로와 근거

- 입력 RGB24/물리 너비/알려진 배경 픽셀 → Wu v2 고정 2색 → 배경 라벨 제외.
- Potrace 1.16 기본 MINORITY, turdsize=2, alphamax=1, opticurve=1, opttolerance=0.2.
- 좌표: raster top-down → Potrace y-up → 중심 원점 SVG px/y-down, 종횡비 유지.
- 최적 Bézier → 기존 `SvgPath.flatten(tolerance=0.01px)`; 기존 `StrokeFixtureTest`의 동일 경계 사용.
- Ink/Stitch v3.3.0 `StrokeParameters()` 그대로: running 2.5mm, tolerance 0.2mm,
  repeats=1, bean=0. 기본 half-stitch 시작/끝 잠금.
- `GeneratedPatternProcessor.fromGroups`(S1 잠금·필터·native 중복 제거) → `PesV6Writer`.
- 새로운 파라미터/자동 배경/골격 추적/경로 재정렬/bean 설정 UI를 넣지 않았다.
  두꺼운 띠의 양쪽 경계가 별도 윤곽으로 생성되는 점은 그대로 보존한다.
- Wu/Potrace 원본 커밋·라이선스·C 비교 테스트는 의존 병합 커밋으로 보존했다.
  이 조합 전체가 Ink/Stitch 공식 이미지 기능이라는 의미는 아니다.

## 재현과 산출물

```sh
tools/redwork/check-fixtures.sh /tmp/myembro-quality-gate/quality-gate
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check
```

산출물 `/tmp/myembro-port-out/editor/redwork/`: PBM, SVG, PES, SewoutCheck findings,
커밋/입출력 SHA-256 포함 `matrix.json`, `quality-gate.json`, `quality-gate.txt`.
첫 실행과 CI의 단위 테스트는 엔진만 호출하며 앱 진입점은 없다.
게이트 프로그램/임계값/단계 설정 파일은 수정하지 않았다. 단계 자동 진전이나 릴리스 승인도 없다.

## 외부 quality-gate check 출력

```text
PASS /tmp/myembro-port-out/editor/redwork/rectangle.pes within five-condition checks
PASS /tmp/myembro-port-out/editor/redwork/round.pes within five-condition checks
PASS 2/2 cases passed
```

| 입력 | 바늘 수 | 최대 연속 동일점 | 0.5mm 셀 최대 | 최대 땀 mm | 시작/끝 잠금 미확인 |
|---|---:|---:|---:|---:|---:|
| rectangle | 62 | 1 | 4 | 2.6000 | 0/0 |
| round | 51 | 1 | 4 | 2.5554 | 0/0 |

## 내부 판정 불일치 회귀 수정

기존 SewoutCheck는 좌표별 누적 방문 횟수를 연속 반복으로 집계해, 잠금 후 돌아오는 정상
닫힌 윤곽도 REPEATED_PENETRATION으로 차단했다. 사용자 정책은 **같은 점 연속 3회 초과**다.
threshold 4는 유지하고, 다른 바늘 좌표로 이동했을 때만 연속 카운터를 초기화하도록 수정했다.
다른 제어 명령 사이에도 바늘 좌표가 동일하면 계속 집계한다.

- 기존 실제 4회 연속 반복 차단 테스트는 유지한다.
- 떨어진 재방문은 연속 반복이 아니라는 회귀 테스트를 추가한다.
- 기존 부채꼴은 밀집으로 계속 차단함을 assert하며, 잘못된 연속 반복 assertion만 0으로 갱신한다.
- 기존 내부 잠금 WARNING과 기계 미확인 INFO는 숨기지 않는다. 외부 v5 기능 잠금 검사는
  두 파일 모두 시작/끝 잠금을 확인했다. 내부/외부 잠금 검출 전체 동등성은 주장하지 않는다.


## 전체 check 실행 결과

```text
./gradlew check
BUILD SUCCESSFUL in 54s
66 actionable tasks: 24 executed, 42 up-to-date
```

엔진 416 tests / 0 failures / 0 errors, 앱 debug·release 각 176 tests / 0 failures.
위 숫자는 로컬 JUnit XML과 Gradle 출력이며 원격 CI 녹색으로 표시하지 않는다.
