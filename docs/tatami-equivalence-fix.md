# 기본 타타미 공식 동등성 수정 — 수치 분해

입력·공식 좌표·땀 수±5%·Hausdorff≤0.2mm 기준은 변경하지 않았다.
테스트는 전체 좌표와 잠금을 비교하고, 순서 비교의 첫 차이 인덱스도 출력한다.
중심 평행이동 외에 회전/크기 조절/점 제거는 없다. 인덱스는0부터 시작한다.

| 사례 | 수정 전 땀 수 비율(우리/공식) | 전 Hausdorff mm | 전 첫 차이 | 수정 후 땀 수 | 후 Hausdorff mm | 후 첫 차이 | 후 첫0.2mm 초과 차이 |
|---|---:|---:|---:|---:|---:|---:|---:|
| rectangle | 0.975482 | 0.583095 | 1 | 571/571 | 0.141421 | 1 | -1 |
| circle | 1.090634 | 0.531507 | 0 | 662/662 | 0.141421 | 0 | -1 |
| hole | 0.975728 | 0.583095 | 1 | 824/824 | 0.141421 | 1 | -1 |

`-1`은 해당 차이가 없다는 뜻이다. 잔여 정확 좌표 차이는 native0.1mm 양자화/
writer 출력 원점 처리 경계에 있고, 순서대로 비교한 최대 오차도3건 모두0.141422mm 미만이다.
허용치 안이라는 판정은 테스트 assert가 한다. 바이트 동일성을 주장하지 않는다.

## 원본 대조와 변경

고정 원본은 Ink/Stitch v3.3.0 `b0edd96311ece82ee48816dc93466274b12c2a9c`다.
원본은 읽기만 했으며 실행/의존성 설치는 하지 않았다.

1. `lib/stitches/tatami_fill.py:find_stitch_path`는 시작/종료 노드와 실제 시작점의
   내부 이동을 경로에 포함한다. 이전 간소화 InkAutoFill은 순회에서 outline 경로를 생략하고
   첫 fill row부터 시작해 첫 잠금 방향부터 달랐다. 별도 간소화 그래프를 제거하고 이미 포팅한
   TatamiFill/FillRouting을 native단위 호환 어댑터에서 호출한다. 중복 알고리즘은 남기지 않는다.
2. `lib/elements/fill_stitch.py:do_underlay/to_stitch_groups`는 마지막 underlay 땀을
   top의 starting_point로 전달한다. DefaultTatami도 이 연결을 보존한다.
3. 원본 travel은 `even_running_stitch`에 getter 기본값0.2mm tolerance를 준다.
   간소화 코드의0을 원본0.2mm로 바꿨다. 기존 경로 테스트는 바늘 좌표가 내부에 있는지 확인하고,
   선분 모서리 편차는 이 원본 tolerance로 확인한다(0길이 선분은 point covers 검사).
4. 원은 코어 교체 후에도662/662땀, Hausdorff0.570088mm였다. 테스트의 SVG arc
   평탄화가 원본 Inkex CSP와 달랐다. worker가 이미 포팅한 ManualCspPath.flatten을
   oracle source set에서 공유해 Ink/Stitch가 받는 것과 같은 폴리곤으로 비교했다.
   공식 golden은 바꾸지 않았다. oracle 코드 사용은 테스트 classpath에만 추가했다.

`DefaultTatamiOracleTest`는 raw underlay/top 땀 수·시작/끝점, postprocess 바늘 수,
실제 PES 바늘 수, 공식 땀 수, count_ratio, Hausdorff, 첫 차이,
첫0.2mm 초과 차이, ordered_max_error_mm를 `engine/build/tatami-oracle/*.json`에 출력한다.
이번 전/후 원본은 [comparison.json](tatami-equivalence-results/comparison.json)에 보존했다.

## 실행 판정

```text
./gradlew :engine:clean :engine:check --console=plain
BUILD SUCCESSFUL in 21s
ENGINE CHECK: 509 tests; 0 failures; 0 errors

/tmp/myembro-quality-gate/quality-gate check engine/build/tatami-oracle --json /tmp/myembro-tatami-equivalence-fix/after/gate.json
PASS 3/3 cases passed
```

공식 동등성은 대표3건이 통과했다. worker의 Kotlin POLICY 게이트 연결은 별도 대기다.
이전430조합 스윕은 이전 backend의 이력이며 이번 코어 변경에 재사용할 수 없다.
허용 규칙의 deployment_enabled=false를 유지한다. 다음 단위에서 새 backend로 재스윕해야 한다.
원격 CI 녹색 및 실제 봉제 승인은 아직 확인하지 않았다.
