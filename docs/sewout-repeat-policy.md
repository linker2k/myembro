# SE400 시작 명령 사고 후 반복 정책 v6

사용자 지시: 앱 생성 출력은 **STITCH/JUMP 도착 좌표가 연속 2회 초과면 차단**한다.
0길이 바늘과 0길이 JUMP도 각각 명령 1회로 센다. 암묵적인 원점은 세지 않고,
TRIM/STOP/COLOR_CHANGE/END는 횟수를 더하거나 연속성을 숨기지 않는다.
다른 좌표로 가는 JUMP/STITCH는 연속 횟수를 1로 다시 시작한다.
가져온 PES는 반복 초과를 주의로 유지한다. 밀집 30회 초과, 파일 유효성·후프·최대 땀 정책은 유지한다.

## 계산 근거와 정책 선택

334개 PE-Design 로컬 코퍼스 전체를 읽기만 하여 계산했다. 파일은 커밋하지 않는다.

| 정의 | 1회 | 2회 | 3회 | 5회 | 선형 보간 p99 |
|---|---:|---:|---:|---:|---:|
| 바늘(STITCH)만 | 173 | 158 | 1 | 2 | 2.0 |
| STITCH + JUMP | 39 | 286 | 7 | 2 | 3.0 |

새 임계값 2는 **바늘 전용 p99=2를 더 엄격한 JUMP 포함 정의로 확장한 사용자 지정 정책**이다.
JUMP 포함 분포의 p99가 2라고 주장하지 않는다. 앱 출력 9/334개에 해당하는 코퍼스형 패턴도
이 기준에서는 차단되지만, 실제 외부 PES는 가져오기 정책을 적용하므로 반복만으로는 차단되지 않는다.
이 변경은 실기 원인 확정이나 봉제 통과를 뜻하지 않는다.

## 재현과 검증

- `python3 tools/update_gate_repeat_policy.py`: 독립 `/tmp/myembro-quality-gate`를 v6으로 개정한다.
  원본 gate.py SHA-256을 확인하고 실행 가능한 v5 파일들을 versions/frozen-v5-before-motion-repeat에 보존한다.
  변경은 반복 집계와 한계값뿐이며 기존 임계값을 재보정하지 않는다. 새 helper·설정 해시를 manifest에 기록한다.
- `python3 -m unittest discover -s tools/tests -p test_quality_gate_repeat.py`: Python 경계/명령 테스트 5개.
- `./gradlew check`: Kotlin 경계 테스트와 대표 PES 10종의 Python v6 스냅샷 판정/지표 비교.
  Python은 독립 기대값 갱신 때만 필요하다. CI는 커밋된 fixture와 SHA-256/지표를 비교한다.
- `tools/refresh_sewout_gate_reference.py --inputs /tmp/myembro-gate-parity-v6`:
  export 환경변수 사용법은 스크립트 머리말 참고. 과거 v5 기대값은 보존한다.

| 실제 저장 파일 | JUMP 포함 연속 최대 | v6 앱 출력 |
|---|---:|---|
| S1SAMPL.PES | 4 | 차단 |
| S23SAMPL.PES (0a60ff0 및 이전판) | 3 | 차단 |
| T1PEDS.PES | 1 | 통과 |
| T2OURS.PES | 3 | 차단 |
| T3NOZRO.PES | 1 | 통과 |

기존 S1 생성 경로의 5개 프로젝트 회귀 사례도 반복 차단을 검증하도록 바꿨다.
입력/생성 알고리즘/파일을 수정하여 게이트에 맞추지 않는다. 공식 오라클 2종의 좌표·땀 수 비교는 유지한다.
