# 정수 기반 PEC 출력 검사 (2026-10-24)

실제 기록된 STITCH/JUMP 도착점만 집계한다. 암묵적 시작 원점은 포함하지 않는다. 최소 x=y=0, 최대 x<HoopSpec.width 및 최대 y<HoopSpec.height, PEC 헤더 치수=max−min을 정수로 검사한다. 한 단위 오차도 허용하지 않는다. 최신 경계 결정은 100mm 후프에서 999 허용/1000 차단이다.

`SewoutLimits`가 반복·밀집·셀 크기·땀 길이와 후프 최대 인덱스의 단일 정의다. `exportGateHoop`은 실제 Kotlin HoopSpec/상수를 JSON으로 내보내며 Python도 이 값을 읽는다. P8은 dx²+dy²≤120²이며 가져온 파일에서는 길이 초과만 주의다. 파일 원점/헤더/후프 오류는 출처와 무관하게 차단한다.

기본/프로필/사용자 정의 후프를 Writer 설정에 전달한다. 프로젝트 내보내기는 프로젝트의 HoopSpec을 사용한다. Writer 직후 release에서도 활성인 require를 실행하며, 기존 PES도 원본 바이트를 유지한 채 전송 준비 직전에 검사한다. 따라서 원본을 보정해서 숨기거나 잘못된 파일을 그대로 전송하지 않는다.

CI는 Python의 실제 정수 판정 함수와 Kotlin에서 n−1/n/n+1, 모든 앱 후프 카탈로그/기종/사용자 정의 후프, AGENTS.md 값과 상수 일치를 검사한다. 이전 포맷 테스트의 의도적 비정상 픽스처는 Writer 거부를 먼저 assert하고 테스트 전용 형식 생성기로만 구성한다. 운영 출력의 우회는 없다.

실행:

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :engine:exportGateHoop
python3 -m unittest discover -s tools/tests -p 'test_quality_gate_*.py'
```

독립 Python gate v7(P8 개정판)이 설치되어 있어야 한다. `:engine:exportGateHoop -PgateHoopOutput=/tmp/myembro-quality-gate/hoop-spec.json` 후 `tools/update_gate_viewport_policy.py`로 재현한다. 로컬 전용 코퍼스는 `tools/report_pec_viewport.py --corpus <local-dir> --output <report-stem>`로 읽기만 한다. 원본 파일·이름은 커밋하지 않는다.

## 계산 결과

| 대상 | 건수 | 원점 | 후프 범위 | 헤더 일치 | ⑤통과 |
|---|---:|---:|---:|---:|---:|
| 로컬 코퍼스 | 334 | 334 | 264 | 333 | 263 |

| 시험 파일 | ⑤ | 전체 게이트 | 사유 |
|---|---|---|---|
| s1/S1SAMPL.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(-246,-165), required=(0,0) |
| s1/S1SAMPL2.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(-246,-165), required=(0,0) |
| s23/S23SAMPL.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(-297,-277), required=(0,0) |
| s23/S23SAM3.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(-297,-277), required=(0,0) |
| s23/S23SAM4.PES | PASS | PASS | — |
| s4/S4SAMPL.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(-317,-150), required=(0,0) |
| s4/S4SAMP2.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(-317,-150), required=(0,0) |
| ab/T1PEDS.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(10,10), required=(0,0) |
| ab/T2OURS.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(10,10), required=(0,0) |
| ab/T3NOZRO.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(10,10), required=(0,0) |

코퍼스 원점은 334/334이지만 전체 ⑤ 통과는 334개가 아니다. 큰 후프용 도안 70개가 SE400 범위를 넘는다. 나머지 1개는 실제 폭 0, 헤더 폭 10단위라 무오차 규칙에서 차단된다. 이 예외를 감추거나 임계값을 완화하지 않는다. 상세 JSON과 익명 실패 목록은 `/tmp/myembro-pec-policy-results.json`이다. 예전 A/B 파일은 min=(10,10)이라 새 규칙에서 차단한다. 실기 성공과 이번 엄격 원점 정책의 통과 여부는 구분한다.
