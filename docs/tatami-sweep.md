# 타타미 확장 스윕 — 로컬 전용

> 이전 backend의 이력이다. [동등성 수정](tatami-equivalence-fix.md)에서 backend를 바꿨으므로
> 새 실행 없이 이430조합 결과를 현재 backend에 적용할 수 없다. 허용 규칙은 배포 비활성이다.

CI의 대표 사례(공식 기본값 사각·원·구멍 3건 + smoke 3건)는 유지한다.
확장 스윕 태스크는 `check`의 의존성이 아니며 단계 릴리스 전 로컬에서 별도로 실행한다.
규칙 계산기 단위 테스트 3건만 CI에 추가했다.

## 재현 명령

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
python3 -m unittest discover -s tools/tests -p test_tatami_sweep.py
python3 tools/sweep_tatami.py --out /tmp/myembro-tatami-sweep-1mm
```

스크립트가 `./gradlew :engine:generateTatamiSweep`를 호출한 후 독립된 외부
`/tmp/myembro-quality-gate/quality-gate check --matrix …/generation.json --json …/gate.json`
판정을 수집한다. worker의 POLICY.md Kotlin 게이트는 아직 이 브랜치에 병합되지 않아
구형 SewoutCheck 결과를 동일 정책으로 간주하지 않는다. 외부 게이트 코드·임계값·POLICY.md의
SHA256을 기록하고 검사 중 변경되면 규칙 발행을 중단한다. 원본 Ink/Stitch는 실행하지 않는다.

## 계산한 입력 공간

- 크기: **5–90mm, 1mm 간격** 86개. 각도는 기본값0° 하나뿐이다.
- 도형: 정사각, 원, 도넛(내/외경 비율0.4), 별(5꼭지·내/외반경0.45), 굵은 H.
- H는 직접 만든 윤곽이다. 높이=size, 너비=0.8×size, 획 두께=0.2×size.
- 별의 size는 외접원 지름이다. 실제 bbox는 size보다 작다.
- 총430조합. 개인 도안·폰트를 사용하지 않으며 도형은 오리지널(CC0-1.0)이다.
- 기본 DefaultTatami(top0°/.25mm/4mm, 바탕90°/.75mm/4mm), S1 기본 planner,
  PES v6 writer를 사용한다. 입력 평탄화 오차는0.01mm다. 알고리즘은 변경하지 않는다.

## 생성 결과와 허용 규칙

출력 디렉터리에는 `generation.log/json`, 후보 PES430개, 독립 판정 `gate.log/json`,
`results.json`, `failures.json`, `summary.md`, `fill-allowed-regions.json`, `provenance.json`이 생긴다.
생성 실패·파일 불일치·게이트 BLOCK은 자동으로 허용 목록에서 제외한다.
도형별 통과 크기, 실패 크기, 첫 실패 크기, 최소 통과 표본 면적을 코드로 계산한다.
비단조 실패를 최소 크기 하나로 숨기지 않고 정확한 통과 목록을 유지한다.

**허용 목록은 이 유한 입력 집합만 의미한다.** 예를 들어 5mm와6mm가 통과해도5.5mm를
자동 허용하지 않는다. 임의 폴리곤/글자/구멍 비율/곡률까지 검증한 결과가 아니며, 면적 하나로
일반화하지 않는다. `minimum_verified_area_mm2`는 도형별 표본 관찰값이고 일반 면적 기준이 아니다.
5mm 미만은 검사하지 않아 실제 최소 안전 크기를 찾았다고 주장하지 않는다.
미검사 입력은 DENY, `interpolation_allowed=false`, `arbitrary_polygons_covered=false`다.
중간 실패나 전체 실패로부터 허위 안전 범위를 만들지 않는지 단위 테스트로 확인한다.
중단된 실행은 INCOMPLETE/DENY 규칙만 남기며 이전 실행의 허용 목록을 재사용하지 않는다.

허용 JSON은 후보 규칙이며 앱에 연결하지 않는다. 현재 공식 동등성 회귀 테스트3개가
실패하는 상태라 `deployment_enabled=false`를 기록한다. 게이트 통과는 공식 동등성/CI 녹색/
실제 봉제 승인과 별도 판정이다. 스윕 결과만으로 이를 통과했다고 보고하지 않는다.

## 실행 출력

```text
python3 -m unittest discover -s tools/tests -p test_tatami_sweep.py
Ran 3 tests
OK

python3 tools/sweep_tatami.py --out /tmp/myembro-tatami-sweep-1mm
GATE: 430/430 PASS; 0 BLOCK
```

| 도형 | 통과 | 최소 통과 표본 mm | 최소 표본 면적 mm² | 최대 셀 밀집 | 첫 실패 크기 |
|---|---:|---:|---:|---:|---|
| square | 86/86 | 5 | 25.000 | 9 | 없음 |
| circle | 86/86 | 5 | 19.562 | 8 | 없음 |
| donut | 86/86 | 5 | 16.447 | 11 | 없음 |
| star | 86/86 | 5 | 8.266 | 14 | 없음 |
| block_h | 86/86 | 5 | 12.000 | 9 | 없음 |

판정 원본과 모든 후보 PES는 `/tmp/myembro-tatami-sweep-1mm/`에 있다.
저장소에는 [계산 요약](tatami-sweep-results/summary.json),
[허용 규칙](tatami-sweep-results/fill-allowed-regions.json),
[실패 목록](tatami-sweep-results/failures.json)을 보관한다.
최대 동일점 연속2회, 최대 땀4.102mm 미만이다. 게이트 로그의 짧은 땀 지표는 참고값이다.
5mm보다 작은 입력은 이번 범위 밖이므로 실패 최소 크기는 발견되지 않았다고 기록한다.
