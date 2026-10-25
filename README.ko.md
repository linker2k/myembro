# myembro

[English](README.md) | **한국어**

![myembro — 안드로이드용 오픈소스 자수 앱(브라더 PES 도안 보기·편집·글자·도안 만들기)](docs/images/readme-banner.png)

**안드로이드용 오픈소스 자수 앱** — 휴대폰에서 브라더 **PES** 도안을 보고, 편집하고, 글자와 사진으로
새 도안을 만들고, 자수기로 보냅니다.

현재 릴리스: **v0.2.8** (GitHub pre-release).

사용 설명서: [docs/manual/user-guide.ko.md](docs/manual/user-guide.ko.md)

PC에서 제어(Claude Code, Codex CLI 등 MCP 클라이언트): [docs/agent/mcp.md](docs/agent/mcp.md)

## 실패 없는 디자인

앱이 만드는 모든 PES는 저장·전송 전에 실제 봉제 실패를 일으키는 조건(0.5mm 칸 바늘 밀집, 같은 점 연속
바늘, 시작·끝 잠금 땀 누락, 최대 땀 길이 초과, 파일 오류·후프 범위 초과)을 코드로 검사하고, 하나라도
걸리면 저장·전송을 막습니다. 땀 생성은 새로 고안하지 않고 검증된 원본(Ink/Stitch, Potrace, AutoTrace,
Wu 양자화)을 기본값 그대로 포팅하며, 원본과 같은 결과인지 자동 비교로 확인합니다. 땀 종류·크기 범위처럼
실패할 수 있는 선택은 앱이 자동으로 정하고, 세부 설정은 노출하지 않습니다.

**후프 범위:** 땀 좌표는 0부터 시작하는 0.1mm 단위 정수이므로 100mm 후프는 0–999를 허용하고 1000부터
막습니다. 디자인 최대 크기는 99.9mm이고, 안전 여유는 이 0.1mm뿐입니다. 모든 후프에 같은 규칙을 적용합니다.

## 기능 (v0.2.x)

- **도안 보기·땀 재생:** PES (v1–v10), PEC, DST 열기, 실 색 렌더링, 확대·이동, 바느질 재생, 통계(땀 수·크기·색·트림·예상 시간), 후프 표시
- **편집기:** 도안·글자 배치, 크기, 회전, 프로젝트 저장
- **레터링:**
  - Ink/Stitch 자수 글꼴 72종 새틴(검증된 글꼴 카탈로그)
  - TTF 글꼴(한글 포함): 글자 크기에 따라 앱이 땀 종류(새틴·채우기·러닝)와 허용 크기 범위를 자동으로 정합니다
- **채우기:** Ink/Stitch 기본 타타미와 바탕 땀
- **사진→자수:** 단색 실루엣(채우기)·외곽선 모드, 크기 조절
- **봉제 점검:** 위의 실패 조건을 자동 차단. 가져온 PES는 바이트 그대로 유지
- **자수기 전송:** Brother SE400 — 휴대폰과 USB 케이블로 직접 전송(실기 확인), 전송 기록, 자동 절단이 없는 기종을 위한 이동 실 정리 안내. SE600/SE700 — 기종 프로필(100×100mm, USB 메모리)
- **전체 백업/복원:** 도안·프로젝트·다운로드 글꼴·설정을 ZIP 하나로 저장·복원
- **버전:** v0.2.0부터 배포마다 v0.2.N. GitHub pre-release로 배포

## 알려진 한계

- SE400은 자동 실 절단이 없어 색·위치 이동 실은 봉제 후 손으로 정리해야 합니다(앱이 안내).
- 현재 APK는 개발용 서명 키로 서명되어 있습니다. 정식 서명 키로 바뀌는 시점에는 기존 앱 위 업데이트가 되지 않아 한 번 삭제 후 재설치가 필요합니다. 그 전에 설정의 전체 백업을 받아 두세요.
- SE600/SE700은 매뉴얼 기준 프로필이며 실기 전송은 아직 확인하지 않았습니다.

## 프로젝트 구성

```
engine/   Pure Kotlin (JVM) — model and file readers, no Android dependency
app/      Android app (Jetpack Compose)
tools/    make_fixtures.py — builds test fixtures with pyembroidery
```

## 빌드

필요 환경: JDK 17, Android SDK (platform 35).

```bash
./gradlew :engine:test          # reader parity tests
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

### 테스트 픽스처 다시 만들기

엔진 테스트는 우리 리더를 [pyembroidery](https://github.com/EmbroidePy/pyembroidery)와 땀 단위로 비교합니다:

```bash
pip install pyembroidery==1.5.1
python3 tools/make_fixtures.py engine/src/test/resources/fixtures
```

## 출처

모든 서드파티 고지와 라이선스 전문: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

- [Ink/Stitch](https://github.com/inkstitch/inkstitch) v3.3.0 — 땀 알고리즘(새틴, 타타미 채우기, 잠금) 포팅과 자수 글꼴 (GPL-3.0-or-later; 글꼴은 각자의 라이선스)
- [Potrace](https://potrace.sourceforge.net/) 1.16 — 비트맵 윤곽 추적 (GPL-2.0-or-later)
- [AutoTrace](https://github.com/autotrace/autotrace) — 세선화와 중심선 추적 (LGPL-2.1-or-later / 일부 GPL-2.0-or-later)
- Xiaolin Wu 색 양자화, Graphics Gems II (Graphics Gems 허가)
- [pyembroidery](https://github.com/EmbroidePy/pyembroidery) — 파일 형식 리더 포팅, 테스트 기준으로 사용 (MIT)
- JTS Topology Suite, JGraphT, libaums, AndroidX, 동봉 글꼴(Noto Sans KR, Gowun Batang, Jua, …) — 고지 참조

## 라이선스

[GPL-3.0-or-later](LICENSE). 기여 환영 — 이슈와 풀 리퀘스트를 열어 두었습니다.
