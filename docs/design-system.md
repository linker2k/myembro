# myembro 디자인 시스템과 홈 연결

## 공통 테마

`ui/theme/MyembroTheme`은 라이트·다크 테마, 서체, 모서리를 제공합니다. Dynamic color는
사용하지 않습니다. 중첩된 기존 `EmbroideryTheme`도 상위의 명시적 모드·팔레트를 유지합니다.
목록·뷰어·설정·내보내기 시트는 공통 테마로 전환했습니다. MainActivity와 편집기 소스는
통합 담당의 작업 범위이므로 수정하지 않았습니다.

- `Color.kt`: A `CRAFT`가 기본값입니다. 린넨 `#F6F0E6`, 표면 `#FFFDF8`, 테라코타
  `#B5492A`, 세이지 `#7F9A76`, 골드 `#D9A441`, 외곽선 `#D8CBB8`, 잉크 `#2B2420`.
  기존 테라코타 `#C4552D`는 실 팔레트용 `ThreadTerracotta`로 분리했습니다.
  다크 팔레트는 따뜻한 검정·갈색 표면과 밝은 테라코타·세이지·골드를 사용합니다.
- `DefaultColorway` 하나를 `BOTANICAL`(B: 세이지 강조) 또는 `INK`(C: 라이트 잉크 /
  다크 골드 강조)로 바꾸면 전체 기본 팔레트가 전환됩니다. 호출별 `colorway` 지정도 가능합니다.
- Android 스플래시·런처 배경은 Gradle의 `generateMyembroBrandColors`가 같은 파일의
  ARGB 상수와 기본 팔레트 선택에서 생성합니다. 생성 XML은 직접 편집하지 않습니다.
- `Type.kt`: 본문·UI는 Noto Sans KR 가변 폰트의 400/500/600/700 축, 디스플레이·큰 제목은
  고운바탕 Regular를 사용합니다. 기본 가변 축이 100이므로 400을 명시했습니다.
- `Shape.kt`: 카드 20dp, 버튼 16dp, 칩 12dp. 화면별 색상과 주요 모서리는 이 토큰을 참조합니다.

```kotlin
MyembroTheme { /* app navigation */ }
MyembroTheme(darkTheme = true, colorway = MyembroColorway.BOTANICAL) { /* preview */ }
```

## 서체 출처

두 폰트는 Google Fonts에서 받은 **변형·서브셋 없는 원본**으로, 오프라인 사용이 가능합니다.
한글 음절·자모·라틴 문자 범위를 확인했습니다. 폰트 원본은 합계 약 18MiB이며,
OFL 원문은 `assets/licenses/`와 `THIRD_PARTY_NOTICES.md`에 포함했습니다.

| 폰트 | 출처 | SHA-256 |
| --- | --- | --- |
| Noto Sans KR | [google/fonts/notosanskr](https://github.com/google/fonts/tree/main/ofl/notosanskr) | `194018e6b2b293a7964f037b25c0249ce1418bc9ab3c971060a03aa57861e252` |
| Gowun Batang Regular | [google/fonts/gowunbatang](https://github.com/google/fonts/tree/main/ofl/gowunbatang) | `466c593e7147412e748af4856d5ad14709b5a860bdf62b9c2546f2c5874e9849` |

## 스플래시 연결

`core-splashscreen:1.2.0`은 이 프로젝트의 SDK 35 / AGP 8.7.3과 호환됩니다.
Manifest의 시작 테마는 `Theme.Myembro.Starting`, 이후 테마는 `Theme.Myembro`입니다.
기존 런처 후프·새틴 스티치 형상을 이용해 후프가 펼쳐지고 실이 그려지는 760ms AVD를
추가했으며 브랜드 배경은 라이트·다크에 대응합니다. 오래 실행시키는 타이머는 없습니다.

통합 담당이 MainActivity의 **super.onCreate 이전**에 다음을 호출해야 합니다.

```kotlin
installMyembroSplashScreen()
super.onCreate(savedInstanceState)
```

이 훅은 compat 설치·시작 테마 전환과 180ms 퇴장 페이드를 처리합니다. AVD 재생은 Android
12 이상에서 지원되며, 이전 버전에서는 완성된 정적 아이콘을 사용합니다.
[Android 스플래시 연결 문서](https://developer.android.com/develop/ui/views/launch/splash-screen/migrate)
참고. MainActivity의 기존 dynamic palette 블록도 통합 시 `MyembroTheme`으로 교체합니다.

## 홈 화면 연결

```kotlin
HomeScreen(
    onNewProject = { hoop -> /* convert ui.Hoop to the editor model; create project */ },
    onOpenRecent = { file -> /* open the existing local file */ },
    onImport = { /* launch the existing import flow */ },
    onLettering = { /* open the lettering/editor route */ },
    onOpenLibrary = { /* navigate to LibraryScreen */ },
    onSettings = { /* navigate to SettingsScreen */ },
)
```

- 새 프로젝트 카드와 만들기 탭은 같은 `NewProjectSheet`로 연결합니다. 기종별 후프와 생성 동선은
  [미싱 프로필 및 통합 메모](machines.md)를 참조하세요. SE400이 기본이며 세 기종은 100×100만 선택할 수 있습니다.
- `HomeViewModel`은 화면 재개 시 `files/projects/*.myembro`와 기존 `files/designs/`를
  수정 시각 역순으로 최대 6개 읽습니다. 실제 파일만 표시하며 파일 IO와 썸네일은 백그라운드에서 처리합니다.
- 꽃 샘플은 번들 PES를 전용 캐시에 복사해 실제 파일을 뷰어 콜백으로 전달합니다. 갤러리의 빈 템플릿은 제거했습니다.
- 도안 가져오기는 기존 콜백으로 연결합니다. 글자 넣기는 실제 레터링 화면 연결 전까지 숨기며, 사진→자수는 준비 중으로 비활성화했습니다.
- 하단 탭은 홈 / 도안 / 만들기 / 설정이며, 화면 라우팅은 호스트 콜백이 담당합니다.
- `HomeLightPreview` / `HomeDarkPreview`는 파일 IO나 ViewModel 없이 디자인을 확인할 수 있습니다.

## A안 화면 컴포넌트

- 목록은 작업실 헤더, 세이지 USB 안내 배너, 검색·정렬·필터와 20dp 카드로 구성합니다.
  하트는 `myembro_library`의 즐겨찾기로 저장하며 즐겨찾기 필터에도 반영합니다.
- 뷰어는 크기·땀 수·색상·예상 시간 카드, 실제 실 색상의 순서 칩, 재생 카드를 사용합니다.
  저장·공유는 `ExportSheet`로 통합했습니다. `ViewerScreen.onExportUsb`는 선택 콜백으로,
  호스트에서 기존 라이브러리 USB 동작에 연결합니다. 연결 전에는 시트에서 해당 버튼이 비활성화됩니다.
- `ExportSheet`의 `preview`·`designSummary`는 선택 사항이며 실제 도안 정보만 전달합니다.
  파일명과 PES 배지는 출력 파일을 나타내며 저장 위치는 기존 호스트 콜백이 결정합니다.
- 설정의 후프 카드는 실제 기본값에 저장됩니다. 썸네일은 투명 배경으로 렌더링하고
  캔버스의 원단·이동선·바늘 표시도 현재 테마 토큰을 사용합니다. 실 자체의 색상은 도안 데이터를 유지합니다.
