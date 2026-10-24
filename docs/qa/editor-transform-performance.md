# 편집기 글자 변형 지연 수정

main `764c3d5` 기준. 개인 입력 없이 합성 채우기 윤곽과 앱 배포 OFL Noto Sans KR ‘가’를 사용했다.

## 확인한 원인

`TextObject.withTransform()`의 data-class copy마다 `pattern by lazy`가 새로 생겼다.
선택/스냅의 `placedBounds()`가 새 글자 생성과 배치/S1을 요청했고, 캔버스의 객체 전체 키도
매 포인터 이벤트에서 RenderData를 무효화했다. 레이어 썸네일은 같은 복사를 main에서 렌더했다.
회전·소수 이동의 기존 기본 배치는 생성 잠금을 제거하고 다시 계획하기도 했다.

## 변경

- 글자 내용·글꼴·높이·스타일에 속하는 lazy 땀은 `withTransform/withId/withVisible`에서 공유한다.
  내용/스타일을 실제로 바꾸는 일반 copy는 새 캐시를 갖는다. 직렬화 필드·equals·공개 메서드 시그니처는 그대로다.
- 크기별 완성 땀 캐시는 최근 한 항목만 유지한다. 크기가 바뀔 때 기존 배치/S1 구현을 한 번 호출한다.
  이동·회전은 완성 좌표와 잠금/앵커를 함께 매핑하고 잠금을 새로 생성하지 않는다.
- Canvas는 `현재 행렬 × 캐시 렌더 행렬의 역행렬`로 기존 선 배열을 그린다.
  제스처 중 렌더 생성은 하지 않는다. 선택/핸들/스냅에는 로컬 경계 사각형 4꼭짓점의
  보수적인 변환 경계를 사용한다. 포인터 프리뷰가 정밀 봉제 검사 판정이 되지는 않는다.
- 크기가 변한 제스처는 pointer-up 후 **300ms**(사용자 지정) 대기 후 Default dispatcher에서
  완성 땀/렌더 배열을 한 번 생성한다. 새 제스처가 시작되면 대기 중 작업을 취소한다.
  진행 표시를 제공하고 이동·회전만으로는 렌더 키가 바뀌지 않는다.
- 레이어 썸네일 캐시 키도 위치·회전을 제외한다. 후프 정밀 검사는 포인터/렌더 갱신이
  끝난 뒤 백그라운드에서 수행한다. 제스처는 기존대로 한 번만 History에 커밋한다.
- 편집 모델의 음수/중앙 좌표는 유지한다. P1 왼쪽 위 원점 정규화는 기존 PES 저장 경로에서 수행한다.
  새 땀 생성/잠금 알고리즘이나 새 의존성은 없다.

## 프레임 계산 비용

| 고정 입력·CPU 측정 | 변경 전 경로 평균/최대 | 캐시 행렬 프리뷰 평균/최대 |
|---|---:|---:|
| JVM 합성 채우기 윤곽, 6프레임 | 56.00 / 62.60ms | 0.0129 / 0.0475ms |
| API 35 x86_64 Android, Noto Sans KR ‘가’ 490땀, 4프레임 | 433.37 / 484.79ms | 0.443 / 1.523ms |

JVM의 이전 경로는 이전 기본 배치 함수에 새 TextObject copy를 전달해 재현했다.
Android는 새 copy의 글자 생성·RenderData 생성·drawLines와 공유 캐시의 경계 계산·
Canvas 행렬/drawLines를 비교했다. UI 전체 프레임/Choreographer/GPU FPS 또는 실기 기기
성능을 측정한 것은 아니다. 한 회 표본이며 임계 시간을 CI assert로 쓰지 않는다.

재현:
```
./gradlew :engine:test --tests '*TextTransformCacheTest'
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.editor.EditorTransformTimingInstrumentation
adb shell am instrument -w io.github.linker2k.myembro.test/io.github.linker2k.myembro.ui.editor.EditorTransformTimingInstrumentation
```

원본 출력:
```
TEXT_TRANSFORM_FRAME_JVM before_mean_ms=56.00373316666667 before_max_ms=62.599822 after_mean_ms=0.012889333333333336 after_max_ms=0.047454 frames=6
TEXT_TRANSFORM_ANDROID text=public_Noto_Sans_KR_glyph stitches=490 before_mean_ms=433.36711575000004 before_max_ms=484.788116 after_mean_ms=0.44265199999999993 after_max_ms=1.522849 frames=4
INSTRUMENTATION_CODE: -1
```

## 생성 결과 회귀 테스트

- 이동·임의 회전은 원본 로컬 땀 객체 identity가 같고, 완성 땀의 명령/개수는 그대로다.
  결과 전체 좌표를 기존 `mapCoordinates`의 단순 강체 변환과 exact 비교한다.
- 크기 변경(1.2×/0.9×)은 기존 배치/S1 함수와 전체 땀 좌표·명령·메타데이터가 exact 동일하다.
  같은 크기의 재요청은 같은 완성 패턴 객체를 반환한다. 글자 높이를 바꾸면 캐시는 분리된다.
- 60프레임 이동/회전/크기 프리뷰 중 로컬 땀은 모두 같은 객체이며 History는 증가하지 않는다.
  pointer-up에서 한 단계만 커밋하고 undo로 원상 복귀한다.

강체 이동/회전 후 잠금을 새로 계획하지 않는 것은 이번 요청에 따른 의도적인 변경이다.
기존의 ‘회전 후 재잠금’ 결과와 동일하다는 주장은 하지 않는다. 원본 생성기와 크기 변경
계산은 유지하고, 강체 변환은 기존 완성 땀을 보존하는 규칙을 테스트한다.

## 실제 편집기 스모크와 최종 검사

앱의 공개 고운바탕 ‘봄날’ 예제를 글자 화면에서 편집기로 추가한 뒤 ADB 터치로 확인했다.
`EditorTransform` debug 로그는 RenderData 새 생성만 기록하며 release 빌드에서는 꺼진다.

```
RIGID_MOVE_RENDER_REBUILDS_0
RESIZE_RENDER_REBUILDS_1 scale=1.1279402126526195,1.1279402126526195
EDITOR_MOVE_RESIZE_UNDO_SMOKE_PASS
```

드래그 후 undo 활성화, 이동 중/후 새 렌더 로그 0개, 핸들 크기 변경 뒤 로그 1개,
너비 37.20→41.96mm / 높이 19.40→21.88mm, undo 동작을 UI XML과 로그로 assert했다.
로그 `/tmp/myembro-editor-transform-smoke.log`; 스크린샷은 공개 글꼴 예제만 사용했다:
- `/tmp/myembro-photo-editor-transform-before.png`
- `/tmp/myembro-photo-editor-transform-moved.png`
- `/tmp/myembro-photo-editor-transform-resized.png`
- `/tmp/myembro-photo-editor-transform-undo.png`

```
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.editor.EditorTransformTimingInstrumentation
BUILD SUCCESSFUL in 25s
113 actionable tasks: 19 executed, 94 up-to-date
```

엔진 650, 앱 debug/release 각 225 tests / 실패·오류·skip 0.
전체 로그 `/tmp/myembro-editor-transform-check.log`, 실제 Android 수치 로그
`/tmp/myembro-editor-transform-android.log`. 로컬 코드/에뮬레이터 검증이며 실기 봉제/CI 승인을 뜻하지 않는다.
