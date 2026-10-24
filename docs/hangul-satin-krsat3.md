# KRSAT2 → KRSAT3 연결 뭉치 라우팅 비교

점프는 도착 뭉치/글자에 귀속, 최초 원점 이동 포함. 이동 길이는 실제 PEC JUMP 길이 합.
이전/이후 렌더러의 명시적 뭉치 순서를 사용한다.

| 글자 | 이전 점프 | 이후 점프 | 이전 이동(mm) | 이후 이동(mm) |
|---|---:|---:|---:|---:|
|정|3|3|29.777|16.361|
|민|3|3|37.829|27.932|

| 글자·뭉치(봉제순) | bbox(px) | 이전 진입 점프 | 이후 진입 점프 | 이후 내부 점프 |
|---|---|---:|---:|---:|
|정·2|[15, 17, 83, 76]|1|1|0|
|정·0|[76, 8, 110, 77]|1|1|0|
|정·4|[33, 81, 110, 124]|1|1|0|
|민·3|[138, 18, 192, 72]|1|1|0|
|민·1|[217, 8, 227, 91]|1|1|0|
|민·5|[153, 84, 231, 120]|1|1|0|

총 JUMP 6→6, 이동 67.606→44.293mm. 글자 왕복 제거, 뭉치 내부 JUMP 0. 모든 6개 run 시작/끝 잠금 PASS(최초 이동의 출발 원점에는 잠금 대상 실 없음).
게이트 PASS, 0길이0, 원점(0,0), 셀 최대5, 최대 땀2.507987mm.

자모 필순 인식/전통 필순 판정은 하지 않는다. 뭉치는 8방향 잉크 연결 성분이며 줄/순서 규칙은 각 렌더러 커밋 문서를 따른다.
최종 수락은 사용자 봉제. 앱 기본값 연결은 이 실기 샘플 생성 단위에 포함하지 않음.

## 변경·근거

근거는 **2026-10-24 사용자 확정 규칙**이다. 같은 글자의 두 뭉치에서
`overlap=max(0,min(bottomA,bottomB)-max(topA,topB))`,
`2*overlap >= min(heightA,heightB)`이면 같은 줄로 보고 left가 작은 뭉치를 먼저
봉제한다. 그렇지 않으면 top이 작은 뭉치를 먼저 봉제한다. 50%를 포함하며 정수
곱셈으로 검사해 부동소수 tolerance를 추가하지 않는다. 글자 인덱스가 최우선이다.

동일 줄 판정은 쌍별 관계이므로 전이적이지 않을 수 있다. 비전이 comparator를
JVM sort에 넣지 않고 각 쌍의 선후 관계를 DAG로 풀며, 서로 모순된 배치는
명시적으로 거부한다. 임의로 다른 tolerance나 자모 인식을 더하지 않는다.
이것은 사용자 정렬 제약의 적용이며 새틴·러닝·잠금 알고리즘은 변경하지 않았다.

실제 출력의 뭉치 순서 assert:
- 정: component2(ㅈ) → 0(ㅓ) → 4(ㅇ)
- 민: component3(ㅁ) → 1(ㅣ) → 5(ㄴ)

위 자모 이름은 요청된 정민 샘플의 설명용 라벨이다. 엔진은 자모를 인식하지 않고
기존 잉크 연결 성분 bbox만 사용한다. 정 상단 두 뭉치는 작은 높이59px 중59px,
민 상단 두 뭉치는 작은 높이54px 중54px 겹쳐 각각 같은 줄로 판정된다.

추가 회귀4건: 서로 다른 top의 상단 뭉치 좌→우, 정확히50%/50%미만 경계,
글자 인덱스 우선·입력 순서 독립, 쌍별 제약 모순의 명시 거부.
기존 연결 성분·AutoTrace·새틴/라우팅·PES 회귀는 유지한다.

최종 `/tmp/myembro-sewtest/krs/KRSAT3.PES`: 624땀·5TRIM·한 색 Black40wt,
26.9×14.4mm. SHA256
`c9cc6ce067b013c2e289e888df5a9dabaf3cb640d1c5d8f4c545315a914587d4`.
KRSAT1/2는 덮어쓰지 않았다. 동일 stem `.png`는 실제 decoded-PES 렌더링이며
`-comparison.md/json`, `-routing.json`, `-gate.json/log`도 보존했다.
외부 gate v7 PASS: 6개 봉제 run 시작/끝 잠금 모두 존재, 셀 최대5, 0길이0,
min(0,0)/max(269,144), header269×144 정확히 일치.

재현(새 파일명 사용):

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:hangulSatinSample -PhangulInput=/tmp/myembro-sewtest/krs2 -PhangulOutput=/tmp/myembro-sewtest/krs/NEWNAME -PstrokeWidthMm=1.13
/tmp/myembro-quality-gate/quality-gate check --text --style satin --json /tmp/myembro-sewtest/krs/NEWNAME-gate.json /tmp/myembro-sewtest/krs/NEWNAME.PES
# 기존 게이트 Python 환경으로:
python tools/hangul_satin/compare-components.py /tmp/myembro-sewtest/krs/KRSAT2.PES /tmp/myembro-sewtest/krs/NEWNAME.PES /tmp/myembro-sewtest/krs/NEWNAME-routing.json /tmp/myembro-sewtest/krs/NEWNAME-comparison --before-routing /tmp/myembro-sewtest/krs/KRSAT2-routing.json
```

이전 9경로 KRSAT1 비교 모드도 유지한다. 앱 기본값 연결은 여전히 다음 단위이며,
이 샘플 생성 변경을 앱 연결 완료로 보고하지 않는다. 최종 수락은 사용자 봉제다.

검증: `./gradlew check` BUILD SUCCESSFUL in37s, 엔진667·앱 Debug205/Release205
실패0·Lint 통과. 출력 `/tmp/myembro-port-out/letter/krsat3-check.log`.
