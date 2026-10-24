# myembro 이미지 에셋

따뜻한 린넨, 목재 자수 후프, 테라코타 새틴 스티치의 세 곡선을 공통 모티프로 사용합니다.
네 가지 원화는 내장 `image_gen` 도구로 제작했습니다. 전체 프롬프트와 참조 이미지 관계는
[generation-prompts.json](generation-prompts.json)에 기록했습니다. 생성 결과는 최종 크기로
리샘플링했으며, 투명 이미지의 알파 채널을 보존했습니다.

| 파일 | 크기 / 형식 | 설명 및 용도 |
| --- | --- | --- |
| [launcher-icon-artwork.png](launcher-icon-artwork.png) | 1024×1024, RGB PNG | 린넨 위 목재 후프와 굵은 새틴 스티치. 런처 벡터 제작의 원화이며 adaptive icon에 직접 넣는 비트맵은 아닙니다. |
| [readme-banner.png](readme-banner.png) | 1024×500, RGB PNG | 왼쪽 앱 이름·소개, 오른쪽 후프. 저장소 README 상단에서 사용합니다. 이미지 속 문구는 아직 "Open-source embroidery viewer for Android"이며 다음 이미지 갱신 때 바꿉니다. 그 전까지는 README의 alt 텍스트에 현재 기능을 적습니다. |
| [play-feature-graphic.png](play-feature-graphic.png) | 1024×500, RGB PNG | 왼쪽 후프와 오른쪽 앱 이름·PES 뷰어 소개. Play 스토어 등록용 불투명 피처 그래픽입니다. |
| [empty-state.png](empty-state.png) | 1024×1024, RGBA PNG | 도안이 없는 빈 린넨 후프와 실타래. 글자가 없고 바깥 배경이 투명한 빈 화면용 원본 일러스트입니다. 앱에서는 아래 WebP 파생본을 사용합니다. |
| [launcher-icon-preview.png](launcher-icon-preview.png) | 960×360, RGB PNG | XML 벡터에서 렌더링한 원형·둥근 사각형·단색·48px 미리보기입니다. 생성형 이미지가 아닌 리소스 검수용 이미지입니다. |

## 런처 리소스

- `app/src/main/res/drawable/ic_launcher_foreground.xml`: 원화의 후프와 스티치 곡선을 단순화한 108×108dp 벡터.
- `app/src/main/res/values/colors.xml`: `ic_launcher_background`를 린넨색 `#F5EDDF`로 설정.
- 전경은 중심 (54, 54)의 지름 66dp 원 안에 들어갑니다. XML 경로를 SVG로 옮겨 10배 해상도로 렌더링한 결과, 안티앨리어싱 픽셀까지 포함한 최대 반경은 약 **30.54dp**였습니다(허용 반경 33dp).
- 후프 중심은 투명하고 도형은 불투명합니다. 기존 adaptive icon의 `<monochrome>`이 같은 전경을 참조하므로, 시스템 틴트에서도 후프의 구멍과 스티치 실루엣이 유지됩니다.
- 미리보기는 XML의 경로·색·획을 Rsvg로 렌더링한 정적 검수 자료입니다. 실제 기기 스크린샷은 아닙니다.

안전영역과 단색 레이어는 [Android adaptive icon 공식 문서](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)를 참고했습니다.

## 검증

- `./gradlew :app:assembleDebug`: 성공.
- PNG 디코딩, 크기, 색상 모드 확인. Play 피처 그래픽은 1024×500 RGB이며 알파 채널이 없습니다.
- 빈 화면 일러스트는 알파 최솟값 0, 최댓값 255로 실제 투명도를 확인했습니다.
- 원형·둥근 사각형 마스크 및 48px 단색 미리보기 육안 확인.

이 디렉터리의 에셋은 저장소의 GPL-3.0-or-later 라이선스를 따릅니다.

## 앱 빈 화면 파생본

`app/src/main/res/drawable-nodpi/empty_state.webp`는 원본 `empty-state.png`를
512×512로 Lanczos 리샘플링한 뒤 무손실 WebP로 인코딩한 파일입니다(178,466바이트,
300KB 이하). 리샘플링된 이미지와 디코딩한 WebP의 알파 채널이 바이트 단위로 같은지
확인했습니다. `EmptyState`에서 160dp 크기로 표시하며 작은 화면에서는 세로로 스크롤됩니다.
새 이미지 생성 없이 기존 원화를 변환했습니다.
