# 현재 PEC 출력 정책 (2026-10-24)

최신 사용자 결정: **2mm 추가 여유 폐기**. `HOOP_SAFETY_MARGIN_UNITS_0P1MM=1`은 P2의 마지막 끝점 예약을 뜻하며 각 변에 더하는 inset이 아니다. 100mm 후프의 허용 좌표는 0..999, 최대 디자인 폭·높이는 99.9mm이다. 이전 20단위/96mm 정책은 현재 적용하지 않는다.

`SewoutLimits`가 후프 끝점·밀집·반복·땀 길이의 단일 정의다. `exportGateHoop`은 Kotlin HoopSpec/상수를 JSON으로 내보내고 Python 검사도 읽는다.

- 실제 기록된 STITCH/JUMP 도착점만 집계한다. 암묵적 원점은 포함하지 않는다. 최소 x=y=0, 최대 x<후프 폭·최대 y<후프 높이, 헤더 치수=max−min을 정수로 검사한다. 빈 이동 목록도 차단한다.
- 편집 배치는 centered 정수 구간에 같은 규칙을 적용한다. 100mm 후프는 −500..499, 홀수 1001단위는 −500..500이다. 부동소수 epsilon은 없다.
- 기본/기종/사용자 정의 HoopSpec을 동일하게 전달한다. 레터링의 검증된 허용 구간을 해당 후프 높이와 교집합하고, 편집기 속성·프로젝트/USB 준비도 실제 배치를 검사한다.
- 기본 PES Writer는 최종 바이트의 0길이 명령과 SewoutCheck 차단 사유를 require로 검사한다. 생성 표식은 생성 정책, 원본 변환은 기존 가져오기 정책을 따른다. 기존 PES는 바이트를 보존하되 전송 준비 시 원점·헤더·후프를 검사한다.
- P8은 dx²+dy²≤120²이다. 생성 도안은 차단, 가져온 도안은 주의다. 원점·헤더·후프 오류는 두 출처 모두 차단이다.
- 원본 비교 전용 `inkstitchCommands=true`는 앱에서 사용하지 않는다. 공식 동등성 검사는 이 프로필에서 동결된 공식 땀/명령 개수와 허용치를 그대로 확인한다. 앱의 0길이 정리는 별도 회귀로 검사한다.

P6의 문서 `half_stitch`와 LockStitchParameters 시작/끝 기본값 및 InkLockStitch 기본값을 직접 비교한다. AGENTS P1–P8 숫자와 n−1/n/n+1 경계 검사도 CI에서 실행한다.

## 복합 도안

`TRIM → 색 변경/STOP → 실제 JUMP`에서 정지 JUMP를 합성하던 문제를 수정했다. TRIM 상태를 실제 비제로 JUMP까지 유지해 PEC trim 비트를 함께 기록한다. 실제 바늘 좌표는 바꾸지 않는다. 종료 직전 TRIM은 upstream PEC처럼 이동을 만들지 않는다. Standalone PEC 및 원본 비교 프로필의 기존 동작은 보존한다.

다색 outline·satin 회귀는 실제 public PesWriter로 저장한다. 실제 미잠금 가져오기 경계를 포함한 복합 도안과 셀 16회 원본은 차단을 유지한다. 임계값 완화·본문 바늘 제거·자체 잠금 생성으로 통과시키지 않는다. [회귀 기록](composite-output-regressions.md) 참조.

## 재현

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :engine:exportGateHoop
python3 -m unittest discover -s tools/tests -p 'test_quality_gate_*.py'
```

Python gate v7/P8 개정판 설치 후 `:engine:exportGateHoop -PgateHoopOutput=/tmp/myembro-quality-gate/hoop-spec.json`, `python3 tools/update_gate_viewport_policy.py`로 같은 상수를 적용한다. 개인 코퍼스는 `tools/report_pec_viewport.py --corpus <local-dir> --output <report-stem>`로 읽기만 한다. 원본과 이름은 커밋하지 않는다.

staging/s4 전용 사진 연결 패치는 [photo-safety-margin.patch](integration/photo-safety-margin.patch)이며 통합 담당이 처리한다. 크기 계산은 공통 helper를 사용하므로 최신 999 끝점 의미를 따른다. 이 브랜치에는 사진 화면이 없다.

기존 재시험 파일 `/tmp/myembro-sewtest/s1/S1SAMPL3.PES`, `/tmp/myembro-sewtest/s4/S4SAMP3.PES`(+PNG)은 이전 단위 산출물이다. S4는 기존 63.5mm 폭/70mm 패킹 예외를 유지했다. 이번 단위에서 재생성하지 않았다.
