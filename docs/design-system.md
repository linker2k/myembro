# myembro 디자인 시스템 — A안 "따뜻한 크래프트" (확정)

2026-10-22 확정. 캔버스의 B안(모던 스튜디오)·C안(밝은 미니멀)은 보류이며 참고용으로 남긴다.
(`Color.kt`의 `BOTANICAL`/`INK` colorway는 A 팔레트의 변형으로, 캔버스의 B·C안과는 다르다.)

- 캔버스: https://claude.ai/artifact/Xa3DHb178qPXu6drysJFW8 — 페이지 "디자인 방향"(목업),
  "디자인 시스템"(토큰·타이포·컴포넌트·스플래시/시작 화면 프로토타입), "프로토타입"(화면 흐름)
- 구현: `app/.../ui/theme/` — `MyembroTheme`, `Color.kt`, `Type.kt`, `Shape.kt`, `MyembroSplash.kt`
  (연결 방법은 아래 "구현 연결")

## 규칙

1. 모든 화면은 `MyembroTheme` 안에서 `MaterialTheme.colorScheme.*`, `MaterialTheme.typography.*`,
   `MaterialTheme.shapes.*`/`MyembroShapes`만 쓴다. **`Color(0x…)` 하드코딩 금지** — 예외는 실 색
   (도안 데이터)과 `Color.kt` 자체뿐이다.
2. 글자/배경 쌍은 4.5:1 이상(큰 글자 3:1). 아래 표의 `on*` 짝을 그대로 쓰면 만족한다.
3. **세이지(secondary)·골드(tertiary)는 채움 전용.** 린넨 위 글자로 쓰면 2.7:1·2.0:1로 기준 미달이다.
   그 위 글자는 `onSecondary`/`onTertiary`(잉크)를 쓰고, 세이지·골드 계열 글자가 필요하면
   `onSecondaryContainer`/`onTertiaryContainer`를 쓴다.
4. 상태를 색만으로 구분하지 않는다(배지에는 글자, 경고에는 아이콘과 문구).
5. 터치 영역 최소 48dp(아이콘 버튼 포함). 본문 최소 12sp.
6. 다크 모드는 아래 다크 열을 쓴다. 다크에서는 그림자 대신 surfaceContainer 단계로 높이를 표현한다.

## 컬러 토큰

| 토큰 (`colorScheme.`) | 역할 | 라이트 | 다크 |
|---|---|---|---|
| `primary` | 주 버튼, 선택 상태, 강조 | `#B5492A` | `#EF9A78` |
| `onPrimary` | primary 위 글자 | `#FFFFFF` | `#472314` |
| `primaryContainer` | 선택된 칩, 탭 알약, 선택 레이어 행 | `#F5DED1` | `#693923` |
| `onPrimaryContainer` | | `#492516` | `#FFDBC8` |
| `secondary` | 세이지 — 채움 전용 | `#7F9A76` | `#A4BC99` |
| `onSecondary` | secondary 위 글자(잉크) | `#2B2420` | `#1F331E` |
| `secondaryContainer` | 토널 버튼, PES 배지 배경 | `#E6ECDF` | `#354A30` |
| `onSecondaryContainer` | 세이지 계열 글자 | `#273522` | `#E0EAD8` |
| `tertiary` | 골드 — 후프 나사, 하이라이트 채움 | `#D9A441` | `#E8BE68` |
| `onTertiary` | tertiary 위 글자(잉크) | `#2B2420` | `#402E05` |
| `tertiaryContainer` | 주의 배너, 팁 | `#F7E8C6` | `#5C4313` |
| `onTertiaryContainer` | 골드 계열 글자 | `#493506` | `#FFE2A5` |
| `background` | 앱 바탕(린넨) | `#F6F0E6` | `#1C1815` |
| `onBackground`, `onSurface` | 본문 글자(잉크) | `#2B2420` | `#F4EBDF` |
| `surface` | 카드, 시트, 하단 탭 | `#FFFDF8` | `#26211D` |
| `surfaceVariant` | 입력칸, 캔버스 바탕 | `#EEE5D8` | `#443A32` |
| `onSurfaceVariant` | 보조 글자·아이콘 | `#72645A` | `#D2C3B4` |
| `outline` | 테두리, 구분선 | `#D8CBB8` | `#998A7A` |
| `outlineVariant` | 약한 구분선 | `#E8DED0` | `#50463C` |
| `error` / `onError` | 오류, 후프 초과 | `#AD352C` / `#FFFFFF` | `#FFB4AA` / `#690005` |
| `errorContainer` / `onErrorContainer` | 경고 배너 | `#F9DEDB` / `#4B1715` | `#89251E` / `#FFDAD4` |
| (확장) hoop | 후프 나무테 (`LightHoopArgb`/`DarkHoopArgb`) | `#916448` | `#D7B58F` |

surfaceContainer 단계(라이트 / 다크): Lowest `#FFFFFF`/`#15120F`, Low `#FBF6ED`/`#211C18`,
기본 `#F2E9DC`/`#2B2520`, High `#ECE1D1`/`#362E28`, Highest `#E5D7C4`/`#413831`.

`Color.kt`는 primary `0xFFB5492A`로 맞춰져 있고(흰 글자 대비 기준 충족), 이전 값 `#C4552D`는 실 색 팔레트(`ThreadTerracotta`)로 옮겼다.

### 실 색 팔레트 (콘텐츠 색, UI 상태에 쓰지 않음)

테라코타 `#C4552D`, 크림 `#EFE3C8`, 세이지 `#7F9A76`, 잎새 `#4F6B3A`, 골드 `#D9A441`,
로즈 `#D86A7A`, 하늘 `#7FA7D9`, 먹색 `#2B2420`. 실제 도안의 실 색은 도안/PEC 팔레트 값을 그대로 쓴다.

## 타이포그래피 (`MyembroTypography`)

본문은 **Noto Sans KR**(가변, OFL), 디스플레이는 **Gowun Batang**(OFL) 하나만 쓴다. 둘 다 앱에 번들된다.

| 스타일 | 서체 | 크기/행간 (sp) | 굵기 | 쓰임 |
|---|---|---|---|---|
| displayLarge | Gowun Batang | 48/62 | 400 | 스플래시 워드마크 |
| displayMedium | Gowun Batang | 40/54 | 400 | 시작 화면 제목 |
| displaySmall | Gowun Batang | 34/46 | 400 | 화면 큰 제목 |
| headlineLarge/Medium/Small | Gowun Batang | 30/42 · 26/38 · 24/34 | 400 | 섹션 제목 |
| titleLarge | Noto Sans KR | 22/32 | 600 | 앱 바 제목 |
| titleMedium | Noto Sans KR | 18/28 | 600 | 카드·시트 제목 |
| titleSmall | Noto Sans KR | 16/24 | 500 | 목록 제목 |
| bodyLarge | Noto Sans KR | 16/26 | 400 | 본문 |
| bodyMedium | Noto Sans KR | 14/22 | 400 | 보조 본문 |
| bodySmall | Noto Sans KR | 12/18 | 400 | 메타 정보(최소 본문) |
| labelLarge | Noto Sans KR | 14/22 | 600 | 버튼 |
| labelMedium | Noto Sans KR | 12/18 | 500 | 칩, 하단 탭 |
| labelSmall | Noto Sans KR | 11/16 | 500 | 배지 |

숫자(mm, 땀 수)는 `fontFeatureSettings = "tnum"`으로 폭을 고정한다.

## 간격 · 모양 · 높이

- 간격(dp, 4 단위): 4 · 8 · 12 · 16 · 20 · 24 · 32 · 40. 화면 좌우 여백 20, 카드 안쪽 16,
  목록 항목 사이 12, 섹션 사이 24.
- 모양(`MyembroShapeScheme`): extraSmall 8(배지·툴팁), small 12(칩), medium 16(버튼·입력칸),
  large 20(카드·캔버스), extraLarge 28(바텀시트 위 모서리·다이얼로그), 완전 원형(FAB·스위치·탭 알약).
- 높이: 카드 1dp, 떠 있는 툴바·레이어 패널 3dp, 바텀시트·FAB 6dp. 그림자 색은 잉크 계열이고,
  다크에서는 surfaceContainer 단계로 대신한다.

## 컴포넌트 규격 (dp)

| 컴포넌트 | 규격 |
|---|---|
| Primary 버튼 | 높이 52, shape medium(16), `primary`/`onPrimary`, labelLarge, 아이콘 18 + 간격 8. 화면당 하나 |
| Tonal 버튼 | 높이 52, `secondaryContainer`/`onSecondaryContainer` |
| Outline 버튼 | 높이 48, 1.5dp `outline` 테두리, 글자 `onSurface`. 비활성: `surfaceVariant` 바탕 + `onSurfaceVariant` |
| 필터 칩 | 높이 40, 좌우 16, shape small(12). 선택 `primary`/`onPrimary`, 기본 `surface` + 1dp `outline` |
| 배지 | 좌우 8, 상하 4, shape extraSmall(8), labelSmall. PES = `secondaryContainer`, 후프 초과 = `errorContainer`, 품질 좋음/주의/부적합 = `secondaryContainer`/`tertiaryContainer`/`errorContainer` + 글자 |
| 도안 카드 | shape large(20), `surface`, 1dp `outlineVariant` 테두리, 썸네일 높이 176(후프 안 미리보기, `surfaceContainer` 바탕), 본문 패딩 12/14, 제목 titleMedium, 메타 bodySmall `onSurfaceVariant` |
| 하단 탭 | 높이 80, `surface`, 4개 고정(구현: 홈 · 도안 · 만들기 · 설정). 선택은 64×32 알약 `primaryContainer`, 아이콘·글자 `primary`, labelMedium |
| 바텀시트 | 위 모서리 28, 손잡이 32×4 `outline`, 안쪽 좌우 24, 제목 titleMedium + 탭(변형/스티치/색상), 아래 주 버튼 하나. 뒤 scrim은 `onBackground` 40% |
| 슬라이더 | 트랙 4, 활성 `primary`, 비활성 `surfaceVariant`, 썸 20. 값은 항상 오른쪽에 숫자+단위 |
| 세그먼트 | 높이 44, 바탕 `surfaceVariant` + 패딩 4, 선택 `primary`/`onPrimary`, 모서리 12 |
| 입력칸 | 높이 52, shape medium(16), `surface` + 1dp `outline`, 단위(mm)는 오른쪽 bodyMedium. 오류는 2dp `error` 테두리 + 아래 문구 |
| 검색창 | 높이 52, 왼쪽 돋보기 20, 오른쪽 필터 버튼 |
| 상태 배너 | 높이 52 이상, shape medium. USB 연결·후프 맞음 = `secondaryContainer`, 주의 = `tertiaryContainer`, 오류 = `errorContainer`. 아이콘 20 + 문구 |
| FAB "새 도안" | 높이 56, 원형, `primary`, 아이콘+글자 |

## 화면별 목업

목업 이미지는 저장소 밖(로컬 Codex 출력)에 있고, 캔버스 "디자인 방향" 페이지에도 같은 이미지가 있다.
목업의 글자·수치는 예시다. 색과 간격은 목업보다 이 문서의 토큰이 우선한다.

| 화면 | 목업 경로 |
|---|---|
| A1 스플래시 | `~/.codex/generated_images/myembro-themes-20261022/A1-splash-1080x2520.png` |
| A2 시작 화면 | `~/.codex/generated_images/myembro-themes-20261022/A2-start-1080x2520.png` |
| 편집기 | `~/.codex/generated_images/01a162d0-39d1-7b21-819b-44594fafe232/exec-59d08e72-95f9-4155-af93-a0055b778bde.png` |
| 도안 목록 | `~/.codex/generated_images/01a162d0-39d1-7b21-819b-44594fafe232/exec-a850e7a3-73ee-4ece-88a0-32ff3b188695.png` |
| 뷰어 | `~/.codex/generated_images/01a162d0-39d1-7b21-819b-44594fafe232/exec-f8b7e243-980e-46f3-884d-e5a6c093b51f.png` |
| 스티치 설정 | `~/.codex/generated_images/01a162d0-39d1-7b21-819b-44594fafe232/exec-b7d3effa-3b53-4d78-9992-5d8ae9619788.png` |
| 레터링 | `~/.codex/generated_images/01a162d0-39d1-7b21-819b-44594fafe232/exec-5b2a6777-b290-4895-bc8e-ac0e47585153.png` |
| 사진 → 자수 | `~/.codex/generated_images/01a162d0-39d1-7b21-819b-44594fafe232/exec-e3c010f6-a74b-445a-8b89-37554b64ee2d.png` |
| 내보내기 | `~/.codex/generated_images/01a162d0-39d1-7b21-819b-44594fafe232/exec-38c45026-4aba-462b-9bca-68e626a3a6ba.png` |
| 설정 | `~/.codex/generated_images/01a162d0-39d1-7b21-819b-44594fafe232/exec-bfe5f9e9-cbd6-43d0-b83b-ac18014da551.png` |

## 구현 연결

embro-worker가 작성한 테마·서체·스플래시·홈 연결 안내(원문 유지).

### 공통 테마

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

### 서체 출처

두 폰트는 Google Fonts에서 받은 **변형·서브셋 없는 원본**으로, 오프라인 사용이 가능합니다.
한글 음절·자모·라틴 문자 범위를 확인했습니다. 폰트 원본은 합계 약 18MiB이며,
OFL 원문은 `assets/licenses/`와 `THIRD_PARTY_NOTICES.md`에 포함했습니다.

| 폰트 | 출처 | SHA-256 |
| --- | --- | --- |
| Noto Sans KR | [google/fonts/notosanskr](https://github.com/google/fonts/tree/main/ofl/notosanskr) | `194018e6b2b293a7964f037b25c0249ce1418bc9ab3c971060a03aa57861e252` |
| Gowun Batang Regular | [google/fonts/gowunbatang](https://github.com/google/fonts/tree/main/ofl/gowunbatang) | `466c593e7147412e748af4856d5ad14709b5a860bdf62b9c2546f2c5874e9849` |

### 스플래시 연결

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

### 홈 화면 연결

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

- 후프 프리셋: 100×100 기본, 130×180, 160×260, 200×200mm. 선택은 화면 상태로 유지하며
  새 프로젝트 버튼과 만들기 탭이 선택한 후프를 전달합니다.
- `HomeViewModel`은 화면 재개 시 `files/projects/*.myembro`와 기존 `files/designs/`를
  수정 시각 역순으로 최대 6개 읽습니다. 실제 파일만 표시하며 파일 IO와 썸네일은 백그라운드에서 처리합니다.
- 꽃 샘플은 번들 PES를 전용 캐시에 복사해 실제 파일을 콜백으로 전달합니다. 정사각·세로형
  템플릿은 각각 100×100 / 130×180의 **빈 프로젝트 시작점**입니다.
- 도안 가져오기와 글자 넣기는 콜백으로 연결합니다. 사진→자수는 준비 중 상태로 비활성화했습니다.
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
