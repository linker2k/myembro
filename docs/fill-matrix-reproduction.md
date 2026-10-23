# S4 채움 공식 매트릭스 재현

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
