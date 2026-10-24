# 단선 레터링 보통/굵게 (v0.2.8)

2026-10-24 총괄 결정: 새 단선 글자의 기본은 **보통**, 굵게는 Ink/Stitch bean의 총 3회 통과다. 사용자가 전에 저장한 BEAN 객체를 수정하면 굵게로 열리고, 변경을 완료하면 기존 `TextObject.stitchStyle`에 저장된다. 새 직렬화 필드는 없다.

## 구현

- 레터링 글꼴 카드 아래에 두 칸 **보통 / 굵게**만 추가했다. 단선 SVG/Hershey의 자동 RUNNING 경로에서만 표시한다. TTF 윤곽·한글 새틴에는 표시하지 않는다.
- Ink/Stitch 디지타이즈 글꼴은 원본 요소가 반복을 소유하므로 모두 선택을 숨긴다. `*_bean` 등 id에 bean이 있는 단선 글꼴도 숨긴다. 해당 글꼴에 BEAN을 또 씌우지 않는다.
- 본 미리보기·빠른 선택 카드·도안 추가·기존 글자 수정은 같은 선택을 사용한다. 굵기만 바꿔도 변경으로 인식해 뒤로가기 시 기존 버리기 확인을 거친다. 보통은 원본 경로를 한 번 실행한다. 원본 글꼴의 되돌아감은 유지하며 별도 분기 재배열 알고리즘은 만들지 않았다.
- BEAN 확장은 worker의 `inkstitch.stitches.RunningStitch.beanStitch` 하나로 통일했다. 이동·트림·색 변경·종료마다 본문 run을 나눠 호출하고 공통 S1 후처리는 한 번 적용한다. 기존 bean 좌표·땀 수·색·곡선·저장/복원 6개 회귀 테스트는 그대로 통과한다.

## upstream repeats와 중복 구간

원본: Ink/Stitch v3.3.0 `lib/stitches/running_stitch.py:bean_stitch` 및 `lib/elements/stroke.py:do_bean_repeats`, commit `b0edd96311ece82ee48816dc93466274b12c2a9c` (GPL-3.0-or-later).

`repeats=[1]`은 각 선분에 왕복 1회를 더해 **앞→뒤→앞, 총 3회**다. `repeats=[3]`은 총 7회이므로 이 UI에서는 쓰지 않는다. ‘굵게 3중’과 upstream 반복 파라미터의 숫자를 구별한다.

원본은 `tags_to_ignore`로 이미 지정된 태그의 반복을 건너뛸 수 있지만 **이미 지난 선분을 자동 판별해 반복을 줄이는 옵션은 없다**. 일반 Stroke 호출도 `bean_stitch(stitches, self.bean_stitch_repeats)`만 넘긴다. 새 방문 판정·중복 제거 규칙을 추가하지 않았다. 따라서 원본이 같은 구간을 두 번 지나면 굵게에서는 **6회**가 될 수 있다. [전체 계산 표](single-line-passes.md)의 EMS Readability 10mm에서 이를 확인했다. 보통에서도 원본 중복은 남는다.

## 검증·재현

공개 카탈로그의 pinned SVG `hershey-script-1`·`ems-readability`와 각 라이선스가 설치된 **emulator-5556**에서:

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest \
  -PmyembroTestRunner=io.github.linker2k.myembro.ui.lettering.SingleLineWeightInstrumentation
adb -s emulator-5556 install -r -d app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w \
  io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.lettering.SingleLineWeightInstrumentation
adb -s emulator-5556 exec-out run-as io.github.linker2k.myembro \
  tar -C files -cf - single-line-weight > /tmp/single-line-weight.tar
# 압축 해제한 폴더에 기존 quality-gate check --text --style running 실행
```

실제 `automaticLetteringState` → 굵기 선택 → `renderLettering` → `TextObject` → PES 경로다. 문구 Yeonwoo, Black 40wt, 자간 0mm(현재 4입력 UI와 동일). Hershey Script 1-stroke 10/15mm, EMS Readability 10mm × 보통/굵게 = **6건**. 각 객체는 저장/복원 후 땀 좌표까지 일치한다.

출력 `/tmp/myembro-port-out/letter/single-line-weight/`: `build.log`, `instrumentation.log`, `gate.json`, `gate.txt`; `single-line-weight/` 하위의 PES 6건, `result.tsv`, `normal.png`, `bold.png`, `discard.png`. 기존 게이트 **6/6 PASS**, 게이트 코드·상한은 변경하지 않았다. 이는 기능 기준 검사이며 최종 봉제 판정은 사용자 실기다.

검사 결과: `./gradlew check`·debug APK/계측 APK 빌드 PASS, `SingleLineWeightTest` 3/3, 기존 `BeanStitchTest` 6/6. 5556 실제 칩 클릭·굵기만 변경 시 버리기 확인·직렬화 땀 일치 PASS.

| 글꼴 | 높이 mm | 보통 땀 수 | 굵게 땀 수 | 게이트 |
|---|---:|---:|---:|---|
| Hershey Script 1-stroke | 10 | 182 | 348 | 양쪽 PASS |
| Hershey Script 1-stroke | 15 | 205 | 417 | 양쪽 PASS |
| EMS Readability | 10 | 120 | 234 | 양쪽 PASS |

위 수는 잠금 포함 전체 바늘 수여서 정확히 3배가 아니다. 본문 선분의 반복은 3회이며 잠금·이동은 bean으로 늘리지 않는다.
