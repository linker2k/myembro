# 컬러 그림의 흑백 옵션

색 수 슬라이더 옆에 ko/en 흑백 토글 하나를 추가했다. 2–6 단계와 기존 크기 슬라이더는 그대로 사용하며, 토글 변경 시 원본 비트맵부터 미리보기를 다시 만든다. 땀 생성은 결과 단계에서만 실행한다.

- [컬러 미리보기](color-preview-ko.png)
- [흑백 미리보기·토글·크기](grayscale-preview-ko.png)
- [흑백 결과·필요한 실](grayscale-result-ko.png)

스크린샷은 instrumentation이 직접 그린 합성 얼굴이며 개인 이미지/이모티콘을 번들하지 않았다. 개발·설치·스크린샷은 **emulator-5556만 사용**했다. 릴리스 5554와 canvas 5558에는 설치하지 않았다.

## 표준과 매칭 근거

[ITU-R BT.709-6](https://www.itu.int/rec/R-REC-BT.709), [원문 PDF §3.2·3.5](https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.709-6-201506-I%21%21PDF-E.pdf)의 코드값 휘도 신호 식:

`Y′ = round(0.2126 R′ + 0.7152 G′ + 0.0722 B′)`

입력은 앱이 디코드한 8비트 RGB다. 구현은 `(2126*R + 7152*G + 722*B + 5000)/10000`으로 반올림하며 알파를 보존한다. 코드값 Y′를 RGB 세 채널에 복제한다. 선형광 Y나 영상용 16–235 제한 범위로 재해석하지 않는다. 양자화 이전 사전 배경 분리와 최종 Wu N단계 모두 이 회색조 입력을 받는다. 표준 Wu 알고리즘·면적 정리·공용 분류와 영역별 대체는 변경하지 않았다.

Brother 고정 PEC 색표의 무채색 계열 6종만 후보로 사용한다. 색조 임계값을 고안하지 않고 색표의 이름/카탈로그 번호를 명시했다:

| 번호 | Brother 이름 |
|---:|---|
| 20 | Black |
| 23 | Dark Gray |
| 19 | Pewter |
| 39 | Gray |
| 16 | Silver |
| 29 | White |

각 Wu 회색조 대표색을 이 집합에서 `PecThreads.distance` 최소값에 매칭한다. 실제 실 색표의 Dark Gray/Pewter RGB는 완전한 R=G=B가 아니지만 공급자가 이름 붙인 회색 계열이며, Warm Gray/Beige 등 유색 실은 후보에 없다. 비슷한 양자화 단계가 같은 실에 매칭되면 필요한 실 개수는 선택 단계보다 적을 수 있다.

## 자동 판정

`Bt709GrayscaleTest`:

- 원색 기준 red=54, green=182, blue=18과 알파 보존 assert.
- 회색 입력 256개가 그대로 유지됨과 256개 모두 제한된 후보 내 최근접 거리 assert.
- Wu 2–6 각각 회색 팔레트·예산 준수·무채색 실 제한 assert.
- 합성 PNG → 회색조 PES의 제외 0·바늘 존재·봉제 게이트 차단 없음 assert.

`ColorDrawingSmokeInstrumentation`:

- 같은 원본에서 컬러 → 흑백 → 컬러 복원.
- 색 수 3과 크기 선택을 유지, 흑백 필요 실이 위 후보 집합 안에 있음, 제외 0·봉제 검사 통과.
- 실제 Compose 전후 화면과 실제 PES 저장.

```text
BUILD SUCCESSFUL in 50s
engine tests=738 failures=0
app debug/release tests=234 each, failures=0
GRAYSCALE_UI_SMOKE_PASS color_stitches=1502 gray_stitches=1493
threads=[(23, Dark Gray), (16, Silver), (19, Pewter)]
toggle_off_restores_color=true
INSTRUMENTATION_CODE: 0
PASS 3/3 cases passed
```

재현:

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
GRAYSCALE_OUT=/tmp/myembro-port-out/editor/grayscale ./gradlew check :app:assembleDebug
# 5556에만 테스트 APK를 설치하고 ColorDrawingSmokeInstrumentation 실행.
/tmp/myembro-quality-gate/quality-gate check \
 /tmp/myembro-port-out/editor/grayscale/grayscale-face.pes \
 /tmp/myembro-port-out/editor/grayscale/android-color-face.pes \
 /tmp/myembro-port-out/editor/grayscale/android-grayscale-face.pes \
 --style mixed --json /tmp/myembro-port-out/editor/grayscale/gate.json
```

로그: `/tmp/grayscale-final-check.log`, `/tmp/grayscale-android-smoke.log`, `/tmp/grayscale-gate.log`. 물리 USB 전송/실물 봉제는 이 스모크에 포함하지 않는다.
