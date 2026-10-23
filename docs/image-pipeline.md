# 사진 → 자수 S5: 조사와 적용 계획

2026-10-24 개정. 기준: `docs/ui-stories.md`의 **실패 없는 디자인**.

## Wu 양자화 교체 단위 — 2026-10-24

- 원본은 Xiaolin Wu의 Graphics Gems II(1991) 알고리즘 **C v2**, `gemsii/quantizer.c`.
  고정 커밋 `ca4898517a93b6b45444a9cda5fd50132694b864`; 원본 SHA-256
  `38b0963722bc3346b18ba58695c87bb7beeb375278f1abd0af05e46933d2a8f6`.
- custom Graphics Gems 재사용 허가와 원본의 “Free to distribute” 고지를 보존했다.
  MIT/public domain으로 표시하지 않는다. 전문·저자 고지는 엔진 리소스와 배포 고지에 포함한다.
- main에는 동결된 자체 ImageQuantizer가 없으므로, `ImageQuantizer.kt`를 Wu 기반 엔진
  진입점으로 새로 추가한다. `codex/editor`의 median cut/CIELAB/알파·영역 휴리스틱 전체를
  가져오지 않는다. 이후 통합에서 실험 파일을 이 새 구현으로 대체해야 한다.
- 알고리즘 근거는 원본 그대로: 33³ 누적 모멘트, 5비트 RGB 버킷, float32 제곱합·점수·분산,
  최대 분산 상자 선택, 빨강→초록→파랑 동률 우선순위, 첫 최대 분할, 정수 나눗셈 팔레트,
  박스 소속 픽셀 라벨. 최근접 색 재배정/팔레트 정렬/디더링은 추가하지 않는다.
- RGB24만 받는다. 알파 합성/투명 픽셀/실 색표 매칭은 원본 Wu 코어 범위 밖이므로
  임의 기본값을 적용하지 않는다. 요청 색 수는 원본 1–256을 지원하며 모바일 색 수 UI와 분리한다.
- 원본 비교는 대표 3개만: 원색, 8×8 그라데이션, 5비트 경계 색. C main의 기존 I/O 빈칸만
  채워 실제 LUT/Qadd를 추출한다. 팔레트 순서·모든 픽셀 인덱스를 정수 단위로 assert한다.
  재현 명령/출처/해시는 `engine/src/test/resources/wu/README.md` 참조.
- 출력 `/tmp/myembro-port-out/editor/wu/`. 앱 호출/새 땀/PES는 없으며 봉제 품질 판정 대상이 아니다.

### Wu 포팅의 명시적 차이

| 항목 | 차이와 근거 |
|---|---|
| 실행/메모리 | C 전역 배열 대신 호출별 JVM 배열; 입력 불변, 반환 인덱스 방어 복사 |
| 통계 정수 | LP64 C long을 Kotlin Long으로 보존. 입력 1,048,576픽셀 제한은 JVM 자원 한계이며 색 선택 규칙이 아님 |
| Bottom/Top | 같은 하위 박스의 8항 Vol로 계산. 제한된 Long 정수합은 정확하므로 동일 통계, float 점수 순서는 유지 |
| 경계 검사 | 빈 입력·RGB24 범위 밖·1–256 외 색 수 거부. 원본의 미정의 입력/메모리 오류를 JVM 예외로 차단 |
| 분류·알파 | 기존 자체 휴리스틱은 미이식. RGB 양자화와 다른 단계로 분리 |

### Wu 단위 실행 결과

- `./gradlew :engine:test --tests '*ImageQuantizerOracleTest'`: 4 tests, 0 failures, 0 errors.
  원본 C 3개 입력의 팔레트 순서와 전체 픽셀 인덱스 exact equality assert 포함.
- `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:check`:
  엔진 397 tests, 0 failures, 0 errors.

```text
BUILD SUCCESSFUL in 9s
6 actionable tasks: 4 executed, 2 up-to-date
```

이는 로컬 엔진 검사 결과이며 CI 녹색이나 새 자수/PES 봉제 품질 통과를 의미하지 않는다.


## 단색 레드워크 통합 1차

사용자 결정의 첫 경로를 `RedworkPipeline.generate`로 연결했다. RGB24 → Wu 2색 →
호출자가 지정한 배경 픽셀 라벨 제외 → Potrace 기본 최적 윤곽 → 기존 SVG 평탄화 →
Ink/Stitch 러닝 기본값 → S1 → PES v6. 앱 호출은 없다.
직접 만든 사각형/원형 PBM 두 개, 코드 assertion, 외부 quality-gate 원본 출력과
내부 연속 반복 집계 회귀 수정은 [qa/redwork-engine.md](qa/redwork-engine.md)에 기록한다.
재현: `tools/redwork/check-fixtures.sh`; 출력 `/tmp/myembro-port-out/editor/redwork/`.
