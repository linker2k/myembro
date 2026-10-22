# 화면 테마

기본값은 시스템 설정과 관계없이 A안 라이트다. 설정의 화면 테마에서 밝게·어둡게·시스템 설정 따르기를 선택하면 `myembro_settings.theme_mode`에 저장된다. 누락되거나 알 수 없는 값은 밝게로 복구한다. `ThemeModeTest`가 이 규칙을 검증한다.

`MyembroTheme` 루트에서 설정을 관찰하며 중첩된 화면은 같은 테마를 상속한다. 편집기·레터링·폰트 화면도 기존 `MyembroTheme`와 MaterialTheme 토큰을 통해 반영된다. 동적 색상은 사용하지 않는다. 창 배경과 시스템 바 아이콘도 같은 토큰으로 갱신한다.

OS는 앱 코드가 실행되기 전에 스플래시 창을 그린다. 시스템 다크 상태에서도 첫 설치 기본값이 밝게 보이도록 고정 라이트·고정 다크·시스템 추종 시작 액티비티를 두고 저장값에 맞는 하나만 활성화한다. 세 진입점은 같은 파일 열기·공유 인텐트를 MainActivity로 전달한다. 실제 화면과 내비게이션은 MainActivity가 계속 담당한다. MainActivity Kotlin 소스는 수정하지 않는다.

Android 12 이상에서는 UiModeManager의 앱별 모드도 저장한다. `MODE_NIGHT_AUTO`는 앱별 qualifier를 해제하여 시스템 설정을 따르게 한다([AOSP 구현](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/UiModeManagerService.java), [Android 다크 테마 안내](https://developer.android.com/develop/ui/views/theming/darktheme)). 라이트·다크 스플래시의 XML 색은 Color.kt에서 생성하므로 A/B/C 토큰 선택도 동일하게 적용된다.

검증: `:app:assembleDebug`, `:app:testDebugUnitTest --tests '*ThemeModeTest'`. emulator-5554에서 설정 선택·저장, 다크 화면과 시스템 다크 상태의 라이트 시작 창을 확인했다.
