# 복합 출력 회귀와 staging 병합

- 원인: PEC canonicalizer가 TRIM 바로 뒤 명령이 색 변경/STOP이면 0길이 JUMP를 삽입했다. 색 명령 후 실제 JUMP에 TRIM 비트를 붙이도록 pending 상태를 보존했다. raw PEC의 원래 포맷 API는 바꾸지 않았다.
- 종료 직전 TRIM은 실제 이동을 만들지 않는다. 따라서 P4를 만족하며, 별도 종료 이동/바늘도 생성하지 않는다.
- `CompositeTrimRegressionTest`는 COLOR_CHANGE/STOP×PES v1/v6의 리터럴 기대 명령·좌표를 비교한다. 기본 다색 outline과 satin은 public Writer 검사 전체를 통과해야 한다.
- 실제 원본 셀 밀집 16회는 P7 차단을 유지하고, prepareForOutput 전후 본문 땀이 같음을 assert한다. 미잠금 가져오기 경계도 차단한다. 검사 조건을 완화하거나 본문을 임의로 희소화하지 않았다.
- `ExactPolicyTest`는 AGENTS P6의 타입과 시작/끝 파라미터·InkLockStitch 기본 타입이 동일함을 assert한다.

사용자 개정에 따라 2mm 정책을 제거했다. 1단위 상수는 마지막 끝점을 예약하는 P2의 별칭이며 별도 inset이 아니다. 998/999는 허용, 1000은 거부한다. 슬라이더 검증 구간·숫자 입력·배치·실제 전송 검사에 같은 HoopSpec을 사용한다.

`origin/staging/s2s3` f230c13을 이 worker worktree에 병합했다. 충돌 시 staging의 간소화된 레터링 UI, 폰트 카탈로그, 타타미·스윕·재생 코드를 유지하고 worker의 출력 경계/검사만 적용했다. 원본 공식 오라클의 동결 개수와 허용치는 변경하지 않았다. 원본 비교는 명시적 호환 프로필을 사용한다.

검증 명령/로그:

- `./gradlew check :app:assembleDebug :engine:exportGateHoop` → BUILD SUCCESSFUL (32s), `/tmp/myembro-staging-worker-final.log`.
- 실제 public Writer를 쓰는 다색·새틴 + TRIM/P6 회귀와 APK → BUILD SUCCESSFUL (3s), `/tmp/myembro-composite-targeted.log`.
- Python 정수 정책 경계 테스트 14개 통과.

push 전 비교는 `git diff origin/staging/s2s3..HEAD --stat`으로 수행한다. 재생 문자열·MainActivity·폰트 asset/catalog가 staging과 동일함도 검사한다.

최종 재확인: `:engine:test :app:assembleDebug` BUILD SUCCESSFUL (14s), 엔진 609개 실패 0. 앱 debug/release는 각 205개 실패 0. `/tmp/myembro-worker-merge-final.log`.
