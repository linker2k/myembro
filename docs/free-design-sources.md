# 무료 자수 도안 사이트 디렉터리와 재배포 검토

확인일: **2026-10-22**. 무료 가격은 파일 재배포 허가가 아니다. 아래 사이트는 앱에
도안을 추가 번들하지 않고 사용자가 브라우저에서 직접 받는 링크만 제공한다.
사이트 원문을 읽고 작성한 한영 요약·형식·계정 조건·확인일·근거 URL은
[`free_design_sources.json`](../app/src/main/assets/sources/free_design_sources.json)에 있다.
사이트 소개/약관은 요약일 뿐이며 각 도안의 최신 조건과 후프 크기를 함께 확인한다.

## 앱에 포함한 링크 (5곳)

| 사이트 / 무료 목록 | 직접 확인한 약관·형식·다운로드 근거 | 확인된 조건 |
|---|---|---|
| [Brother Sewingcraft](https://sewingcraft.brother.eu/en/inspiration-and-events/projects-and-free-patterns) | [공식 이용약관 §2·§5](https://sewingcraft.brother.eu/en/terms-of-use), 무료 페이지의 PES 다운로드 링크 | 개인용; 파일 재배포·판매용 이용 불가. 방문자도 이용 가능. 영어 페이지. |
| [Embroidery Library](https://emblibrary.com/pages/free-embroidery-designs) | [라이선스](https://emblibrary.com/pages/licensing), [지원 형식](https://emblibrary.com/blogs/help/find-embroidery-file-formats), [계정/ZIP 다운로드](https://emblibrary.com/blogs/help/download-embroidery-design-files) | PES·DST 등 제공, 계정 주문 내역에서 ZIP 다운로드. 직접 수놓은 완제품의 제한적 판매 허용, 대량 생산·디지털 파일 공유 금지. |
| [Urban Threads](https://urbanthreads.com/pages/free-embroidery-designs) | [라이선스](https://urbanthreads.com/pages/licensing), [실제 $0 무료 상품의 PES 포함 형식](https://urbanthreads.com/products/i-heart-ut), [계정 다운로드 절차](https://urbanthreads.com/blogs/help/downloading-instructions) | PES·DST 등 제공, 계정/무료 주문 절차. 직접 수놓은 완제품의 제한적 판매 허용, 대량 생산·파일 공유 금지. 구매 조건부 사은품과 독립 무료 목록을 구분한다. |
| [Designs by JuJu](https://www.designsbyjuju.com/free-designs) | [2026년 이용 조건](https://www.designsbyjuju.com/terms-of-use), [계정/형식/다운로드 FAQ](https://www.designsbyjuju.com/knowledge-base) | PES·DST 등 제공, 계정/주문 절차. **무료 도안은 판매용 완제품에 사용 불가**; 해당 도안을 구매해야 판매용 이용 가능. 무료/유료 모두 파일 공유 금지. |
| [Kreative Kiwi](https://www.kreativekiwiembroidery.co.nz/Download-Free-In-the-hoop-Embroidery-Designs) | [저작권/완제품 판매](https://www.kreativekiwiembroidery.co.nz/copy-right), [무료 상품의 PES 포함 형식](https://www.kreativekiwiembroidery.co.nz/product/free-embroidery-addict-), [무료 주문/계정 절차](https://www.kreativekiwiembroidery.co.nz/blog/How-to-Download-our-Free-Embroidery-Designs.html) | PES·DST 등 제공, 계정/무료 주문 절차. 직접 만든 완제품 판매 가능; 대량 판매는 별도 상업 라이선스 문의. 파일 재배포 금지. |

Embroidery Library·Urban Threads·JuJu·Kiwi의 목록/조건/형식·다운로드 페이지는
실제 HTTP 요청으로 200 응답과 본문을 확인했다(리다이렉트는 최종 URL 사용).
Brother는 일반 Python HTTP 요청에 403을 반환했으나 웹 열람 도구에서 무료 목록과
이용약관 본문을 모두 확인했다. 접근 제한을 우회하거나 계정 생성/주문/결제는 하지 않았다.
디렉터리에는 도안 파일/사이트 이미지/로고/원격 코드를 복사하지 않았다.

## 조사했으나 앱에서 제외한 후보

| 후보 | 확인 결과 / 제외 사유 |
|---|---|
| [Janome 공식 무료 목록](https://www.janome.com/free-embroidery-designs/) | 무료 목록은 열람 도구에서 확인됨. 페이지가 두 파일을 JEF로 명시하여 PES 제공 근거가 없고, 연결된 `/terms-conditions/` 본문을 가져오지 못했다. 조건을 추측하지 않고 제외. 일반 HTTP는 403. |
| Husqvarna Viking / Pfaff / mySewnet | 공식 mySewnet 루트·라이브러리는 HTTP 200이지만 현재 CREATIVATE 표기/로그인 중심 동적 화면. 이용 조건 URL도 HTTP 200이나 읽을 수 있는 약관 본문과 현재 무료 다운로드·PES 형식을 함께 확정하지 못했다. 브랜드별 무료 링크를 추측해 넣지 않음. |
| [Ink/Stitch](https://inkstitch.org/) 갤러리 | 공식 홈은 HTTP 200. 홈의 OSS 소프트웨어 라이선스와 별개로, 현재 무료 도안 다운로드 갤러리·개별 도안 이용 조건을 함께 확인하지 못했으므로 제외. |

사이트 변경에 대비하여 JSON에는 확인일을 표시한다. 재확인은 무료 목록·약관·형식·
다운로드 조건 모두를 대상으로 하며 하나라도 확인되지 않으면 앱 목록에서 제외한다.

## 앱 공유 가져오기

공개 화면은 `FreeDesignSourcesScreen(onBack)`이며 A안 테마 토큰과 Custom Tabs를 쓴다.
내려받은 PES/PEC/DST 또는 ZIP을 공유하면 `DesignLibrary.importShared(uri)`로
라이브러리에 넣을 수 있다. **MainActivity 라우팅/인텐트 수신 연결은 통합 담당 작업**이며,
[연결 메모](free-design-sources-integration.md)에 구체적인 변경 지점을 남겼다.
Manifest의 SEND/SEND_MULTIPLE 필터, ZIP 안전 추출 함수와 단위 테스트는 준비되어 있다.

## 번들된 공개 도안 출처(기존 샘플 팩)

이 링크 디렉터리는 기존 샘플 팩을 바꾸지 않는다. 기존 공개 데이터는
[EmbroidePy/samples 고정 커밋](https://github.com/EmbroidePy/samples/tree/6e65f1ae8599b1e04d3e082aae1b86abed52ec9e)의
[MIT 라이선스](https://github.com/EmbroidePy/samples/blob/6e65f1ae8599b1e04d3e082aae1b86abed52ec9e/LICENSE)에
따라 `spiralbox.pes` 파생본 하나만 포함한다. 형식만 다른 동일 도안은 추가 개수로 세지 않았다.
Brother Artspira의 계정/기기 제한 도안, 개인용 전용 도안, 종료된 무료 프로모션의 파일은 번들하지 않는다.

## Our starter pack

26 PES designs (25 original arrangements + one MIT community design), all centered
within an 86×86 mm area in a 100×100 mm hoop. Original sample artwork is expressly
CC0; converted Hershey glyphs retain their acknowledgement and Jua font attribution
is recorded. See [pack licenses](../app/src/main/assets/samples/LICENSES.md),
[manifest](../app/src/main/assets/samples/manifest.json) and [thumbnail sheet](samples.png).

`tools/make_samples.py` is offline and reads only checked-in font/PES data. With the
existing pyembroidery 1.5.1 environment plus local Pillow/fontTools:

```sh
PYTHONPATH=/usr/lib/python3/dist-packages /path/to/venv/bin/python tools/make_samples.py
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug
```

Running/triple-running paths target 2.5 mm stitches; scanline fills use 0.4 mm row
spacing, 3 mm along-row stitches and a 1.5 mm alternating phase. Normal running
segments target the requested 1.5–4 mm range. Shorter contour-end/corner stitches,
zero-distance needle entry after a jump, and short 0.4 mm fill turnarounds are
intentional; no needle-down move exceeds 4 mm. Disconnected travel is trimmed
before jumping. These are geometric starter designs; fabric/stabilizer and machine
settings still need an ordinary test sew. The verifier uses our own PesReader.

First launch/first launch after upgrading from the single-flower demo installs the
whole pack once. Existing user files are preserved. Deleting a sample does not
make it reappear at next launch. Sample file names start with `sample_` and the
library shows a localized sample badge. A failed copy leaves no partial PES and is
retried on the next launch; completed copies are not overwritten.
