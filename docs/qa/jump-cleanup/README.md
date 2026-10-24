# 이동실 수동 절단 안내

뷰어의 봉제 점검 상단과 USB 전송 결과에 공통 안내를 표시한다.

> 이동 실 N개 · SE400은 자동으로 자르지 않아요. 수놓은 뒤 가위로 잘라 주세요.

표시 조건: 프로필의 `automaticJumpTrim == false`, 분석 완료·확인된 양수 개수와 기종 이름. 0개·미확인·자동 절단 지원·미완료 분석에는 표시하지 않는다. SE600/SE700의 미확인 자동 절단 능력을 `없음`으로 바꾸지 않는다. 영어는 1개/여러 개 문구를 구분한다.

USB의 직접 전송/SAF 모두 성공한 파일에 전송 당시 기종·자동 절단 여부를 함께 저장한다. 합산은 성공 파일만 대상으로 한다. 이전 기록이나 일부 파일의 능력/개수가 미확인이면 안내를 생략한다. 설정을 나중에 바꾸어도 과거 전송 기록의 기종 안내를 바꾸지 않는다. PES 원본 바이트·생성·게이트 임계값 변경은 없다.

## 검증

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew \
  :app:testDebugUnitTest --tests '*ManualJumpAdviceTest' --tests '*UsbTransfer*Test' \
  :app:assembleDebug
```

15 tests / 0 failures / 0 errors. 로그 `/tmp/myembro-jump-advice-final.log`.
기종 false/true/null × 개수 null/음수/0/1/2, 뷰어 SE400/SE600/미완료, USB 성공/부분 실패/이전 기록/혼합 기종, 기록 JSON 왕복을 검사한다.

emulator-5554의 별도 applicationId `io.github.linker2k.myembro.jumpadvice`에서 실제 UI를 열어 ko/en 양쪽을 확인했다. `JumpAdviceInstrumentation`은 직접 만든 4×1mm 윤곽 두 개(이동실 1개)와 **명시적 모의 USB 성공 기록**을 준비한다. 개인 도안은 없으며 물리 USB 전송을 수행했다고 주장하지 않는다. 이 instrumentation은 `.jumpadvice` 패키지에서만 실행된다. 로컬 init script로 applicationId를 바꾸고 `-PmyembroTestRunner=io.github.linker2k.myembro.ui.JumpAdviceInstrumentation :app:assembleDebug :app:assembleDebugAndroidTest`로 재현할 수 있다.

```
JUMP_ADVICE_FIXTURE_READY count=1; synthetic USB record only
USB_EN_ADVICE_PASS
USB_KO_ADVICE_PASS
VIEWER_KO_ADVICE_PASS
VIEWER_EN_ADVICE_PASS
```

![뷰어 한국어](viewer-check-ko.png)
![USB 한국어](usb-result-ko.png)
![뷰어 영어](viewer-check-en.png)
![USB 영어](usb-result-en.png)
