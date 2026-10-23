# S4 채움 공식 매트릭스 재현

> **현재는 보관 문서입니다.** 2026-10-24 총괄 지시에 따라 아래 225건 재현·반복 실행을 중단합니다.
> 새 검증은 기능별 대표 사례 10개 이내에서 땀 수·모양을 확인합니다. 과거 통계는 참고 기록이며
> 새 차단 조건이 아닙니다. 30–60분 단위 보고 후 다음 지시를 기다립니다.

S4 차례까지 FillStitch는 preview로 유지한다. 기존 TatamiFill/OutlineFill, UI, 원본 잠금 기본값을 바꾸지 않는다. US-6은 S2 객체 땀 탭 이후에 착수한다(`docs/ui-stories.md`).

## 입력과 실행

- 고정 매트릭스: `/tmp/myembro-quality-gate/matrix-inkstitch.json`
- SHA-256: `7ce25a2faa933047030f804b0512a6cc476fb9a2437e98a014a5aa07966221ee`
- 담당: `owner=embro-model`, `feature=fill_stitch`, **225건**.
- 실제 SVG: `/tmp/myembro-inkstitch-oracle/inputs/fill_stitch--*.svg`.
- 7도형 × 8크기(4–150mm) × 4프로필 = 224건 + 0 간격 음성 입력 1건.
- 기본 출력: `/tmp/myembro-port-out/model/<case-id>.pes`, 전체 입력 목록·결과·해시는 `results.json`.
- JDK17과 기존 Gradle 환경을 사용한다. 원본 Ink/Stitch/확장/pytest 실행과 의존성 설치는 하지 않는다.

깨끗한 고정 커밋 체크아웃에서:

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
python3 tools/reproduce_fill_matrix.py \
  --matrix /tmp/myembro-quality-gate/matrix-inkstitch.json \
  --owner embro-model \
  --oracle-input-dir /tmp/myembro-inkstitch-oracle/inputs \
  --output-dir /tmp/myembro-port-out/model \
  --results-json /tmp/myembro-port-out/model/results.json \
  --planner-source-root /path/to/worker/engine/src/main/kotlin
```

S1 미병합 동안에만 공통 planner source root가 필요하다. 실행기는 필요한 소스를
`output-dir/dependencies/source/`에 읽어서 복사하고, **그 스냅샷**을 컴파일한다.
파일별 SHA-256와 원본 경로는 `dependencies.json` 및 결과 manifest에 남긴다.
이 외부 WIP는 model 커밋의 일부라고 주장하지 않는다. 이미 main에 있으면 해당 인수는 필요 없다.
현재 dependency 목록은 LockStitches, StitchPlanBuilder, SewingOptions,
GeneratedPatternProcessor, PathEndPoints다. GeneratedPatternProcessor는 타입 의존성 때문에
컴파일되지만 runner에서 호출하지 않는다.

`--commit FULL_SHA`는 .git 없는 archive에 필요하다. Git 체크아웃에서는 실제 HEAD와
일치해야 하고 dirty 상태는 거절한다. `--case-filter REGEX`는 디버그 부분집합용이며,
제출용 실행은 기본 `.*`로 **225건 모두** 기록한다. 생성 실패도 ERROR로 기록하고 마지막에
비정상 종료한다. 양성 케이스를 빼거나 거절해서 숨기지 않는다.

## 파라미터/출력 대응

| 매트릭스 | 실제 입력/엔진 |
|---|---|
| `angle_deg` | SVG `inkstitch:angle` → FillParameters.angle |
| `row_spacing_mm` | 동일 이름, 원본 getter의 최소 0.1mm 보정 |
| `max_stitch_length_mm`, `staggers` | 동일 이름 |
| `underlay` | SVG `inkstitch:fill_underlay` |
| 전역 metadata | min_stitch_len_mm=0.1, collapse_len_mm=3.0 → 공통 StitchPlanBuilder |
| 좌표 | 원본 v3.3.0 `get_viewbox_transform`: **T(-viewBox.x,-viewBox.y) @ S**, 각 값 `%f` 6자리 |
| shape | 변환 후 0.1px flatten, 큰 path를 shell/나머지를 hole로 구성, make_valid, precision, 면적 >3px² |
| 실/라이터 | 실제 SVG fill RGB → **PES v1**(공식 출력과 같은 PEC 팔레트 근사) |

입력 SVG는 별도 fixture reader로 읽는다. S5 전체 SVG importer라고 주장하지 않는다.
해석하지 못하는 요소/CSS/파라미터는 명시적 오류다. matrix 파라미터가 준비된 SVG 속성과
맞는지 검사하고 원래 입력과 준비된 SVG 각각의 해시를 기록한다. 원본 grating 위상에 영향을
주는 viewBox 이동을 제거하거나 표준 SVG의 S@T로 바꾸지 않는다.

원본과 같은 형태로 생성한 그룹 → `StitchPlanBuilder.fromGroups` → raw adapter → PES 순이다.
공통 후처리를 건너뛰어 raw row 시작 중복을 파일에 그대로 쓰지 않는다. PE-Design의 잠금
바이트 형태를 맞추기 위한 별도 후처리를 넣지 않는다.

**음성 입력 주의:** `zero-row-spacing`은 매트릭스에는 REJECTED지만 공식 v3.3.0은
0→0.1mm로 보정해 PES를 생성한다. 이 runner도 GENERATED로 기록한다. 이 불일치를
매트릭스/기준 수정이나 가짜 거절로 숨기지 않고 Codex 검토에 넘긴다.

## 수치 관측과 독립 판정 구분

공식 동등성 판정은 Codex의 고정 comparator/criteria와 검토가 담당한다. 재보정 중
`export_coordinates.py`가 `FROZEN_GATE_CHANGED: gate.py`로 중단되면 기준 해시를
고치거나 검사를 우회하지 않는다.

그동안 다음은 **참고 관측치**를 만든다. 기존 pyembroidery와 시스템 numpy/scipy가 필요하며
설치는 하지 않는다. `--pes-python`에는 기존 pyembroidery 1.5.1 인터프리터를 지정한다.

```sh
python3 tools/measure_fill_matrix.py \
  --results-json /tmp/myembro-port-out/model/results.json \
  --pes-python /path/to/existing/pyembroidery-venv/bin/python
```

`observations/measurements.json`은 count, 대칭 needle Hausdorff, bbox 차이, 길이 분포,
순서 거리, 양쪽의 짧은 땀/0길이/반복 좌표, hole 침범과 material coverage 표본을 기록한다.
잠금·언더레이 태그는 PES에서 복원할 수 없다. 짧은 땀은 **잠금을 포함한 전체 STITCH의
양수 길이**이며 body-only 통계가 아니다. 이 파일은 게이트 PASS/동등 판정이 아니다.

hole 관측은 양쪽 bbox 중심을 평행이동 정규화한 30–70% 사각 구멍을 사용한다. 양자화에
대한 0.05mm inset, 61×61 재료 영역 표본, ≤0.2mm segment 표본,
`row_spacing/2+0.15mm` 거리 기준의 진단이다. 실물 커버리지나 완전한 면적 증명이 아니다.
구멍 내부 바늘·봉제 선 침범과 구멍 **바깥 재료 영역의 누락**을 별도 수치로 남긴다.

초기 8건에서는 count가 근접해도 hole/10mm diagonal의 봉제 순서 차이가 컸다.
단순 짧은 땀 차단만으로 원본 동등성을 판정하지 않는다. 공식 8mm hole dense 자체에도
0.3mm 미만 양수 땀이 있으며, 정상 잠금/재방문은 동일점 누적을 증가시킨다.
전체 수치와 차단 원인은 후속 결과 기록에서 보고한다.


## 1차 기준선과 그래프 복제 교정

고정 커밋 `088b3caedff0330e07988e024687270b15256972`의 깨끗한 체크아웃에서
225/225 PES를 1분 52초에 생성했다. 원본 판독은 pyembroidery만 사용한 참고 관측이다.
입력/출력 해시와 결과는 `/tmp/myembro-port-out/model-baseline-088b3ca/`에 보관한다.

| 범위 | 건수 | 땀 수 ±5% | needle Hausdorff ≤0.2mm | 두 조건 모두 | 순서 p95≤0.3mm·최대≤1mm |
|---|---:|---:|---:|---:|---:|
| 전체(음성 1 포함) | 225 | 191 | 95 | 93 | 83 |
| rectangle | 32 | 32 | 29 | 29 | 29 |
| circle | 32 | 31 | 6 | 6 | 7 |
| hole | 32 | 31 | 3 | 3 | 0 |
| sharp_corner | 32 | 14 | 0 | 0 | 0 |
| narrow_tip | 32 | 32 | 28 | 28 | 23 |
| self_intersection | 32 | 19 | 1 | 0 | 0 |
| crossing_strokes | 32 | 32 | 27 | 27 | 24 |

이 표는 전체 동등 판정이 아니다. 잠금·제어명령 검토 등 독립 기준이 더 남아 있다.

정적 대조에서 `FillRouting.Graph.copy`가 global edge 삽입순으로 복제하던 차이를 확인했다.
원본이 쓰는 [NetworkX MultiGraph.copy](https://networkx.org/documentation/stable/_modules/networkx/classes/multigraph.html#MultiGraph.copy)는
**node → neighbor → key** 순서로 양방향 간선을 다시 넣는다. 이 순서가 달라지면
`pick_edge`의 첫 outline 선택이 바뀌고 같은 행 집합의 긴 섹션 순서가 바뀐다.
A/B/C 3노드 회귀로 순서를 확인하고 이 복제를 맞췄다. 이는 source 동작 교정이며
PE-Design 출력 패턴을 맞추는 변경이 아니다. 수정 후 전체 재생 결과는 후속 기록에 추가한다.

관측된 구멍/짧은 땀 관련 사항:

- hole 32건의 재료 영역 coverage 표본은 양쪽 모두 100%, 0.05mm 안쪽 구멍 내부 바늘은
  양쪽 모두 0개였다. 따라서 현재 표본에서는 넓은 미충전 영역이 주원인이라는 근거가 없다.
- running tolerance/양자화로 모서리를 잘라 구멍 inset을 지나는 봉제 선은 공식 22건,
  포트 18건이었다. 이 관측을 바늘이 구멍에 꽂혔다는 뜻으로 해석하지 않는다.
- 전체 74건에서 포트의 양수 <0.3mm 땀 수가 원본보다 많았다. hole에서는 6건이었다.
  정상 잠금/재방문과 row/travel의 짧은 끝땀을 포함하므로 이 통계만으로 오류를 확정하지 않는다.
- `short` 원인 후보는 행·travel 연결/순서 변화, JTS와 GEOS의 repair·경계 순서,
  quantization이다. `stitch_row`의 raw 시작 중복 자체는 원본에도 있고 최종 0.1mm 필터가 처리한다.
- 명시적 start/end가 행과 닿지 않는 hole에 놓이면 현재 노드를 버리는 동작과,
  underpath 소진 후 경로가 없을 때 원본의 직선 후보→polygon clamp 복구 대신 오류를 내는
  차이가 남아 있다. 이 225건에서는 생성 실패가 없으므로 관측된 실패라고 단정하지 않는다.
  구멍 명령 별도 회귀를 통해 S4에서 다룰 항목이다.


## 교정 후 제출 결과

생성 코드 커밋: **`3412ebbdddd2e8a58e4526b7cec4b35add7d511d`**.
깨끗한 `/tmp/myembro-model-fill-core-check` 체크아웃에서 전체를 두 번 생성했다.
225/225건 생성, **225/225 PES 바이트 동일**. 두 실행은 각각 1분 42초/1분 56초였다.
PES를 자체 PesReader와 pyembroidery로 읽었고 manifest의 PES SHA-256를 모두 재검증했다.

| 범위 | 건수 | 땀 수 ±5% | needle Hausdorff ≤0.2mm | 두 조건 모두 | 순서 p95≤0.3mm·최대≤1mm |
|---|---:|---:|---:|---:|---:|
| 전체(음성 1 포함) | 225 | 193 | 128 | 125 | 125 |
| rectangle | 32 | 32 | 32 | 32 | 32 |
| circle | 32 | 31 | 7 | 7 | 8 |
| hole | 32 | 32 | 27 | 27 | 26 |
| sharp_corner | 32 | 14 | 1 | 0 | 0 |
| narrow_tip | 32 | 32 | 30 | 30 | 31 |
| self_intersection | 32 | 20 | 1 | 0 | 0 |
| crossing_strokes | 32 | 32 | 29 | 29 | 28 |

**이 열의 일부 기준을 만족한 건수는 전체 동등/PASS 판정이 아니다.** 길이 분포·잠금 경계·
제어명령·원본 정적 검토까지 포함한 Codex 판정은 아직 없다.

- 주 결과: `/tmp/myembro-port-out/model/results.json`
- 반복 결과: `/tmp/myembro-port-out/model-repeat/results.json`
- 수치 전체: `/tmp/myembro-port-out/model/observations/measurements.json`
- 요약: `docs/fill-matrix-summary.json` 및 `/tmp/myembro-port-out/model/summary.json`
- 공통 S1 소스의 고정 hash 목록: `/tmp/myembro-port-out/model/dependencies.json`
- 재현에 사용할 불변 스냅샷 source root:
  `/tmp/myembro-port-out/model-baseline-088b3ca/dependencies/source`

Graph.copy 교정으로 rectangle10 diagonal의 순서 p95가 4.6→약 0.141mm,
hole10 horizontal은 9.16→약 0mm가 됐다. 후자는 땀 수도 143/140→143/143으로 맞았다.
형상 교체나 PE-Design 잠금에 맞춘 조정 없이 source graph 복제 순서만 교정한 결과다.

구멍 32건의 교정 후 coverage 표본도 양쪽 최소 100%, inset 구멍 내부 바늘은 양쪽 0개다.
구멍을 일부 지나는 봉제 선은 공식 22건/포트 23건으로, running tolerance와 quantization을
분리해 추가 검토해야 한다. 0.3mm 미만 양수 땀 수는 공식보다 증가 73건/감소 95건/동일 57건이다.
감소를 품질 개선이라고, 증가를 전부 회귀라고 단정하지 않는다.

다음 S4 동등성 보강 우선순위:

1. **자기교차 repair/조각·ring 순서:** count ±5%를 벗어난 32건 중 sharp_corner 18건,
   self_intersection 12건이다. JTS GeometryFixer/precision과 GEOS make_valid의 linework·
   순서 차이를 먼저 대조한다. 나머지는 circle 1건, 0 간격 음성 1건이다.
2. **원형 flatten/경계 traversal:** count는 대부분 근접하지만 Hausdorff·순서가 남는다.
   원본 cspsubdiv와 현재 SVG arc flatten의 샘플/경계 투영을 대조한다.
3. **출력 양자화:** 원본 `lib/output.py`는 bbox 중심 이동 후 px→0.1mm encoder를 사용한다.
   현재 raw adapter는 절대 px를 Math.round로 양자화한다. 동일한 rectangle150 dense의
   땀 수 35,306개와 순서가 근접해도 짧은 땀은 공식 669/포트 821개였다.
   이동/반올림 단계가 이 차이에 얼마나 기여하는지 별도 probe가 필요하다.
   원본 출력 정책 검토 없이 짧은 땀을 지워 수치만 맞추지 않는다.
4. **추가 hole 명령 회귀:** 행 없는 링·underpath 경로 소진 후 복구는 앞서 기록한 미검증 차이다.

검증: 일반 엔진 **394개**, frozen S1 조합의 preview **410개** 테스트를 통과했다.
공식 comparator는 재보정 중 `FROZEN_GATE_CHANGED: gate.py`로 좌표 export가 중단되므로
통과를 선언하지 않는다. 재현 커맨드/고정 소스/PES/참고 수치는 Codex에 검토용으로 제출한다.
UI와 기존 채움 교체는 S4 사용자 검토 차례까지 계속 보류한다.
