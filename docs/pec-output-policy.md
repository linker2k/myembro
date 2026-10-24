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

## P2a 및 최종 출력 경계 추가

`HOOP_SAFETY_MARGIN_UNITS_0P1MM=20`이 단일 정의다. P2의 정수 인덱스 상한과 별개로 도안 폭/높이≤후프−40을 검사한다(100mm 후프 최대 96mm). 실제 편집 배치는 양쪽 좌표에 2를 곱한 정수 비교로 각 변 20단위를 보장하며 홀수 크기 후프에도 반 단위 공차를 허용하지 않는다.

- 레터링 높이 슬라이더는 기존 폰트 범위 이내에서 선택 후프의 안전 높이로 제한한다. 정렬·추가 가능 여부도 같은 여유를 사용한다.
- 이 브랜치의 편집기는 크기 숫자 입력이다. 안전 영역 밖 속성의 적용을 막고 안전 크기를 표시한다. 편집기/프로젝트 내보내기와 USB 프로젝트 준비에서 실제 배치도 검사한다.
- `PesOutputSafety`는 실제 최종 PEC를 다시 읽어 0길이 명령과 SewoutCheck 차단 사유를 검사한다. 생성 표식이 있는 도안은 생성 정책, 원본에서 변환한 도안은 명시적 기존 가져오기 정책을 따른다. 따라서 가져온 도안의 잠금·길이·반복은 주의이고 밀집 30회 초과는 차단이다. 기본 Writer가 새로 쓰는 파일은 0길이 명령을 허용하지 않는다.
- 비제로 half_stitch 경로를 바꾸거나 새로운 잠금 알고리즘을 만들지 않는다. 원본 비교 전용 `inkstitchCommands=true`는 앱에서 사용하지 않으며 원본 비교를 위한 직렬화만 유지한다.
- 최종 검사에서 일부 기존 복합/다색 생성 사례의 정지 TRIM/JUMP 및 잠금·밀집 위반이 검출되어 내보내기가 차단된다. 포맷 단위 테스트는 해당 거부를 assert하고 바이트 테스트를 계속한다. 이 결과를 생성 품질 통과로 해석하면 안 된다.

### 사진 화면 통합 필요

이 브랜치 및 main에는 사진 화면이 없다. staging/s4 전용 PhotoViewModel에 적용할 [photo-safety-margin.patch](integration/photo-safety-margin.patch)를 준비했다. 크롭 종횡비를 유지하면서 가로·세로 안전 영역에 모두 맞는 출력 크기를 사용한다. 사진 화면에는 현재 크기 슬라이더가 없고 상세도 슬라이더만 있다. 통합 담당이 staging/s4에서 `git apply <worker-worktree>/docs/integration/photo-safety-margin.patch`로 적용해야 한다. 이 사진 패치는 현재 브랜치의 Android 빌드 대상으로 검증하지 못했다. 공통 Writer/전송 검사는 UI와 무관하게 안전 여유 위반을 차단한다.

## 계산 결과 (P2a 적용)

상세 결과는 `/tmp/myembro-pec-policy-margin-results.json`과 `.md`에 있다. 개인 도안과 상세 실패 해시는 저장소에 넣지 않는다.
| 대상 | 건수 | 원점 | 후프 범위 | 2mm 여유 | 헤더 일치 | ⑤통과 |
|---|---:|---:|---:|---:|---:|---:|
| 로컬 코퍼스 | 334 | 334 | 264 | 260 | 333 | 259 |

| 시험 파일 | ⑤ | 전체 게이트 | 사유 |
|---|---|---|---|
| s1/S1SAMPL.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(-246,-165), required=(0,0) |
| s1/S1SAMPL2.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(-246,-165), required=(0,0) |
| s1/S1SAMPL3.PES | PASS | PASS | — |
| s23/S23SAMPL.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(-297,-277), required=(0,0) |
| s23/S23SAM3.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(-297,-277), required=(0,0) |
| s23/S23SAM4.PES | PASS | PASS | — |
| s4/S4SAMPL.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(-317,-150), required=(0,0) |
| s4/S4SAMP2.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(-317,-150), required=(0,0) |
| s4/S4SAMP3.PES | PASS | PASS | — |
| ab/T1PEDS.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(10,10), required=(0,0) |
| ab/T2OURS.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(10,10), required=(0,0) |
| ab/T3NOZRO.PES | BLOCK | BLOCK | PEC_ORIGIN: min=(10,10), required=(0,0) |

P2a 적용 후 SE400 고정 후프 기준 ⑤는 259/334이다. 원점은 334/334, 헤더는 333/334, 물리 인덱스 범위는 264/334, 2mm 여유는 260/334이다(실패 사유는 서로 겹친다). 코퍼스의 큰 후프용 도안을 SE400용으로 임의 축소하거나 헤더 불일치를 허용하지 않았다.

새 샘플러:

| 파일 | 좌표 범위 mm | 바늘 / 트림 | 독립 게이트 |
|---|---|---|---|
| `/tmp/myembro-sewtest/s1/S1SAMPL3.PES` + PNG | 0..49.2 / 0..33.0 | 685 / 37 | PASS |
| `/tmp/myembro-sewtest/s4/S4SAMP3.PES` + PNG | 0..63.5 / 0..30.0 | 2436 / 1 | PASS |

기존 샘플러의 바늘 순서·좌표를 평행이동만 했음을 코드로 assert했다. 양쪽 모두 제로 이동 0개다. S4는 앞서 허용된 70mm 패킹 한계와 실제 63.5mm 폭을 유지했으며, 이번 변경에서 크기를 임의로 줄이지 않았다. 따라서 일반 60mm 시험 세트 규칙의 예외가 그대로 남는다. 상세 게이트 출력과 바이트 검증은 `/tmp/myembro-sewtest/margin-samplers-gate.txt` 및 `margin-samplers-byte-check.json`이다.

검증: `./gradlew check :app:assembleDebug` → BUILD SUCCESSFUL (48s), 엔진 422 / 앱 debug·release 각 177 테스트 실패 0. Python 실제 규칙 함수 경계 테스트 14개 통과. 이후 Python 참조와 문서 갱신 후 ExactPolicyTest/GatePolicyParityTest 재실행도 성공.

중단 시점 추가 확인: 빈 PEC 우회 차단 후 `:engine:test :app:assembleDebug` BUILD SUCCESSFUL (11s). 다음 단위: staging 사진 패치 적용·빌드, 복합 생성의 정지 TRIM/JUMP 경계와 잠금·밀집 거부 사례 원인 수정, P6 문서/default 파라미터 일치 직접 assertion 추가.
