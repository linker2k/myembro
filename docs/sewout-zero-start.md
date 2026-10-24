# SE400 T3 안전 출력: 0길이 명령과 같은 위치 트림 재시작 제거

사용자 실기 결과: T1PEDS·T3NOZRO 정상, T2OURS 생략. half_stitch 잠금은 유지한다.
추가 진단: S23SAM2의 두 구간은 STITCH@P → TRIM → JUMP(0)@P → 바늘로,
실제 이동 없이 같은 자리에서 트림·재시작하므로 연속 봉제로 잇도록 결정했다.

## 적용 경계

- `ThreadStartCleanup.clean`: 0길이 JUMP/바늘 제거. TRIM 뒤 첫 재시작 위치가 같고
  다른 위치 이동·색 변경·STOP이 끼지 않으면 TRIM도 제거한다. 비제로 잠금 경로는 재계산하지 않는다.
- `GeneratedPatternProcessor.forSewout`: 공통 **최종 봉제 단계**. 편집기 원본/생성 알고리즘과
  그 인덱스·잠금 provenance를 수정하지 않고 출력 복사본만 정리한다. 정리한 복사본의 인덱스 provenance는 제거한다.
- 기본 `PesWriter.write` / `writeV1` / `writeV6`는 자동 적용한다. 편집기/레터링/샘플러
  모두 이 경계를 사용한다. 프로젝트 뷰어 점검도 동일한 최종 복사본을 검사한다.
- 기존 PES를 읽거나 공유하는 byte pass-through와 raw PEC API는 그대로다.
  명시적 `inkstitchCommands=true`는 공식 출력 비교용 원본 호환 프로필이며 앱에서는 사용하지 않는다.
  이 원본 호환 프로필의 동작은 변경하지 않는다. 실기 출력에는 기본 안전 프로필을 사용한다.

T3의 이동 없는 잠금 시작점만 제거하며 half_stitch의 비제로 왕복은 그대로다.
원본의 0길이 바늘을 뺀 **비제로 바늘 좌표·순서가 재생성 파일과 완전히 동일**함을 Python으로 assert했다.

## 재시험 파일 (로컬 전용)

| 파일 | 땀 | 트림 | 0길이 JUMP/바늘 | 연속 최대 | v6 gate |
|---|---:|---:|---:|---:|---|
| /tmp/myembro-sewtest/s1/S1SAMPL2.PES | 685 | 37 | 0 / 0 | 1 | PASS |
| /tmp/myembro-sewtest/s23/S23SAM3.PES | 4417 | 13 | 0 / 0 | 1 | PASS |
| /tmp/myembro-sewtest/s4/S4SAMP2.PES | 2436 | 1 | 0 / 0 | 1 | PASS |

각 파일 옆에 PNG/배치 JSON이 있다. 새 글꼴·알고리즘을 섞지 않도록 기존 개별 PES를
우리 SamplerCli/PesWriter로 다시 배치·직렬화했다. S4의 기존 70mm 캔버스와 실제 63.5×30mm 크기를 유지했다.
`/tmp/myembro-sewtest/zero-start-clean-gate.{txt,json}`와 `zero-start-clean-bytes.json`이 계산 결과다.
S23SAM3 SHA-256: `427df7f2ae4bc53267a5de2e80de5c5ef2d784bd4f844b1f268faa601449f458`.

## 테스트와 staging 통합

리터럴 T3 시작 바이트에 해당하는 명령 목록, 비제로 half_stitch 왕복 보존, 정리의 멱등성,
동일점/원거리 TRIM 구분, COLOR_CHANGE/STOP 경계 보존을 엔진 테스트로 검증한다.
기존 내보내기 테스트는 원본 모델과 최종 PES의 0길이 명령 차이를 명시적으로 반영했다.
PES 파서·한계값·색상·원본 객체/잠금 생성 테스트를 유지하며 전체 check를 실행한다.

이 커밋을 staging/s2s3와 staging/s4에 cherry-pick하면 기본 PES 출력에 자동 적용된다.
생성기 쪽에서 중복 후처리를 추가하거나 잠금 종류를 바꾸지 말 것.
