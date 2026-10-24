# myembro

![myembro — open-source embroidery app for Android (view, edit, letter and digitize Brother PES designs)](docs/images/readme-banner.png)

**Open-source embroidery app for Android** — view, edit, letter and digitize Brother **PES**
designs on your phone and send them to the machine.

**안드로이드용 오픈소스 자수 앱** — 휴대폰에서 브라더 **PES** 도안을 보고, 편집하고, 글자와 사진으로
새 도안을 만들고, 자수기로 보냅니다.

## 실패 없는 디자인 / Failure-free designs

앱이 만드는 모든 PES는 저장·전송 전에 실제 봉제 실패를 일으키는 조건(0.5mm 칸 바늘 밀집, 같은 점 연속
바늘, 시작·끝 잠금 땀 누락, 최대 땀 길이 초과, 파일 오류·후프 범위 초과)을 코드로 검사하고, 하나라도
걸리면 저장·전송을 막습니다. 땀 생성은 새로 고안하지 않고 검증된 원본(Ink/Stitch, Potrace, AutoTrace,
Wu 양자화)을 기본값 그대로 포팅하며, 원본과 같은 결과인지 자동 비교로 확인합니다. 땀 종류·크기 범위처럼
실패할 수 있는 선택은 앱이 자동으로 정하고, 세부 설정은 노출하지 않습니다.
Every generated PES is checked in code for the conditions that cause real sew-out failures and is
blocked from saving or sending if any is found. Stitch generation ports verified originals with
their default parameters instead of inventing new algorithms.

## Features / 기능 (v0.2.x)

- **도안 보기·땀 재생 / Viewer:** PES (v1–v10), PEC, DST 열기, 실 색 렌더링, 확대·이동, 바느질 재생, 통계(땀 수·크기·색·트림·예상 시간), 후프 표시
- **편집기 / Editor:** 도안·글자 배치, 크기, 회전, 프로젝트 저장
- **레터링 / Lettering:**
  - Ink/Stitch 자수 글꼴 72종 새틴(검증된 글꼴 카탈로그) — 72 Ink/Stitch embroidery fonts as satin
  - TTF 글꼴(한글 포함): 글자 크기에 따라 앱이 땀 종류(새틴·채우기·러닝)와 허용 크기 범위를 자동으로 정합니다
- **채우기 / Fill:** Ink/Stitch 기본 타타미와 바탕 땀
- **사진→자수 / Photo to embroidery:** 단색 실루엣(채우기)·외곽선 모드, 크기 조절
- **봉제 점검 / Sew-out check:** 위의 실패 조건을 자동 차단. 가져온 PES는 바이트 그대로 유지
- **자수기 전송 / Machines:** Brother SE400 — 휴대폰과 USB 케이블로 직접 전송(실기 확인), 전송 기록, 자동 절단이 없는 기종을 위한 이동 실 정리 안내. SE600/SE700 — 기종 프로필(100×100mm, USB 메모리)
- **전체 백업/복원 / Backup:** 도안·프로젝트·다운로드 글꼴·설정을 ZIP 하나로 저장·복원
- **버전 / Versions:** v0.2.0부터 배포마다 v0.2.N. GitHub pre-release로 배포

## 알려진 한계 / Known limitations

- SE400은 자동 실 절단이 없어 색·위치 이동 실은 봉제 후 손으로 정리해야 합니다(앱이 안내).
- 현재 APK는 개발용 서명 키로 서명되어 있습니다. 정식 서명 키로 바뀌는 시점에는 기존 앱 위 업데이트가 되지 않아 한 번 삭제 후 재설치가 필요합니다. 그 전에 설정의 전체 백업을 받아 두세요.
- SE600/SE700은 매뉴얼 기준 프로필이며 실기 전송은 아직 확인하지 않았습니다.

## Project layout

```
engine/   Pure Kotlin (JVM) — model and file readers, no Android dependency
app/      Android app (Jetpack Compose)
tools/    make_fixtures.py — builds test fixtures with pyembroidery
```

## Build

Requirements: JDK 17, Android SDK (platform 35).

```bash
./gradlew :engine:test          # reader parity tests
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

### Regenerating test fixtures

The engine tests compare our readers stitch-by-stitch with
[pyembroidery](https://github.com/EmbroidePy/pyembroidery):

```bash
pip install pyembroidery==1.5.1
python3 tools/make_fixtures.py engine/src/test/resources/fixtures
```

## Credits

All third-party notices and full license texts: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

- [Ink/Stitch](https://github.com/inkstitch/inkstitch) v3.3.0 — stitch algorithms (satin, tatami fill, locks) ported, and embroidery fonts (GPL-3.0-or-later; fonts under their own licenses)
- [Potrace](https://potrace.sourceforge.net/) 1.16 — bitmap contour tracing (GPL-2.0-or-later)
- [AutoTrace](https://github.com/autotrace/autotrace) — thinning and centerline tracing (LGPL-2.1-or-later / GPL-2.0-or-later parts)
- Xiaolin Wu color quantizer, Graphics Gems II (Graphics Gems permission)
- [pyembroidery](https://github.com/EmbroidePy/pyembroidery) — file format readers ported and used as a test reference (MIT)
- JTS Topology Suite, JGraphT, libaums, AndroidX and bundled fonts (Noto Sans KR, Gowun Batang, Jua, …) — see the notices

## License

[GPL-3.0-or-later](LICENSE). Contributions welcome — issues and pull requests are open.
