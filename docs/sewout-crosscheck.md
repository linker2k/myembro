# Python/Kotlin 봉제 점검 crosscheck

## 이전 결과와 원인

codex의 main `38594a2` 비교는 코퍼스 334개 중 70개, 앱 경계 4개 중 1개, 총 71개가 달랐다. 아래 수치는 동일 파일에 중복될 수 있다.

| 조건 | Python → Kotlin | 건수 | 원인·수정 |
|---|---|---:|---|
| file | PASS → BLOCK | 71 | Kotlin이 `PEC_HOOP`를 `INVALID_PES`로 집계했다. 후프 초과는 `HOOP_EXCEEDED`로 분리한다. |
| zero_motion | WARNING → PASS | 32 | bounded UI 상세 목록에서 밀려난 주의 항목을 exporter가 놓쳤다. 전체 조건별 최악 심각도를 별도로 집계한다. |
| locks | WARNING → PASS | 26 | 위와 같은 상세 목록 제한. |
| length | WARNING → PASS | 5 | 위와 같은 상세 목록 제한. |
| repetition | WARNING → PASS | 4 | 위와 같은 상세 목록 제한. |

판정 근거는 AGENTS P1/P2/P3/P4/P5/P7/P8 및 로컬 POLICY의 코퍼스 범위다. Python crosscheck의 참조 코퍼스는 `apply_hoop=false`인데 Kotlin에는 같은 범위 지정이 없었다. 코퍼스 70개의 후프 문제를 파일 손상으로 판단한 Kotlin의 범위·분류가 잘못됐다. 앱 경계 1000은 후프 차단이 맞으며 **파일 손상 차단을 중복 표시**한 것이 오류다. 좌표 원점·헤더 불일치는 두 범위 모두 계속 차단한다.

`SewoutOptions.applyHoop=false`는 오프라인 참조 비교에서만 사용한다. 기본값 true이며 앱 전송·표시 경로는 바꾸지 않는다. `findingSeverities`는 상세 항목의 최대 표시 수와 무관한 전체 조건 집계다. exporter도 이 집계를 사용한다. 기존의 imported 주의 정책(잠금·반복·0길이·긴 땀) 및 밀집 15 초과 주의/30 초과 차단을 유지한다. Python core·임계값·고정 프로필을 바꾸어 맞추지 않았다.

## 재현

```sh
/tmp/myembro-quality-gate/crosscheck.sh \
  --repo /home/gosh2/workspace/hoonyjung/myembro-worker --commit HEAD \
  --kotlin-command python3 \
  /home/gosh2/workspace/hoonyjung/myembro-worker/tools/crosscheck_kotlin_export.py \
  '{manifest}' '{output}' '{checkout}'
```

crosscheck는 지정 커밋을 `/tmp`에 archive하여 Kotlin을 실행한다. exporter에는 입력 파일·해시만 전달하며 Python 판정 결과를 읽지 않는다. 개인 도안·이름·경로·상세 결과는 커밋하지 않는다. 공개 회귀 테스트는 `CrosscheckScopeTest`와 `ExactPolicyTest`이며 `check`에 자동 포함된다.

재실행 출력은 `0`(종료 코드 0). 코퍼스 **0/334 불일치**, 앱 경계 **0/4 불일치**, 총 **0/338 불일치**. 로컬 결과는 `/tmp/myembro-worker-crosscheck.stdout`, `/tmp/myembro-quality-gate/crosscheck-latest.json`과 그 파일이 가리키는 `comparison.json`에 있다. `CrosscheckScopeTest`는 999/1000/1001, 잘못된 헤더, `maxFindings=1`에서도 조건 심각도·최종 판정이 보존됨을 검사한다.
