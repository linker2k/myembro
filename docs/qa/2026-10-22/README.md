# 2026-10-22 UI QA

기기: emulator-5554, 기본 1080×2400 / 420dpi. 앱 언어 한국어, 기본 밝게(A안).
검증 중 main 최신 변경을 codex/worker에 통합했다. 초기 기준 main `19ebb35` + 사용자 프레임 `b55d246`, 최종 기준 main `b9d2571` 통합(`320bee8`) + 이 QA 커밋의 UI 수정.
`assembleDebug`와 앱 단위 테스트 통과. SA431/SA434 커밋에서는 엔진 전체 테스트도 통과했다.

공유 에뮬레이터에서 다른 세션의 APK 설치 및 editorpreview 앱 실행이 관측되었다. 설치 직후
빌드/기기 APK SHA-256 일치를 확인했으나 이후 기기 해시가 바뀌었다. 간섭 캡처는 삭제했으며,
아래의 실제 터치로 확인한 범위와 통합이 남은 항목을 구분한다. 화면 PNG는 원본 캡처다.

## 실제 확인한 흐름

| 흐름 | 결과 및 증거 |
| --- | --- |
| 스플래시 | 최종 통합 APK의 라이트 브랜드 배경·후프 아이콘 확인. [스플래시](01-splash.png) |
| 홈·생성 시트 | 홈 카드와 만들기 탭의 공통 시트, 빈 후프·내 도안·글자 3갈래. [시트](12-new-project-choices.png) |
| 후프 선택 | 기본 100×100, SA431, SA434 및 미지원 프레임 경고 선택. SA434 위치 미확인 표시. [선택](13-frame-choices.png) |
| 편집기 추가·이동·회전 | 빈 후프→샘플 추가→드래그 X9.15/Y11.18mm→90° 회전. [이동·회전](16-editor-moved-rotated.png) |
| 편집기 저장 | `.myembro` 앱 내부 저장 성공 메시지 확인. [저장](17-editor-saved.png). 테스트 파일은 별도 이름으로 정리 |
| 한글·영문 레터링 | 기본 봄날, 영문 MYEMBRO 입력. [한글](18-lettering-korean.png), [영문](19-lettering-english.png) |
| 3겹 콩땀 | 설치된 Hershey Sans 선택→3겹 콩땀→도안 추가. [선택](20-lettering-bean.png), [편집기](21-editor-lettering.png) |
| 전용 picker | 내 도안·샘플 탭, star 검색 3개, star triple 선택→편집기. [검색](23-picker-search.png), [결과](24-picker-to-editor.png) |
| 도안 목록 | 검색, 100×100/PES/최근 필터, 이름 정렬, QA 복사본 즐겨찾기·삭제. [검색](27-library-search.png), [즐겨찾기](28-library-favorite.png), [삭제 후](29-library-deleted-before-fix.png) |
| USB 배너 | SE400 USB 케이블·PC 이동식 디스크 안내 및 OTG 미확인 표시. [안내](26-library-usb-help.png) |
| 뷰어 | 캔버스·색상 순서·mm/in 통계, 재생 45% 진행 확인. [뷰어](30-viewer.png), [재생](31-viewer-playing.png) |
| 내보내기 | PES 저장·공유·USB 시트, SAF 기본 flower.pes 파일명, ACTION_SEND 공유 시트. 공유 대상 앱으로 전송은 하지 않음. [시트](32-export-sheet.png), [SAF](33-viewer-save-picker.png), [공유](34-viewer-share.png) |

## 발견 사항과 처리

| 상태 | 담당 | 항목 |
| --- | --- | --- |
| 수정 | embro-worker | 도안 목록의 삭제 아이콘이 확인 없이 즉시 파일을 삭제했다. 대상 개수 확인 대화상자를 추가하고 확인 당시의 선택과 일치할 때만 삭제한다. [수정 후 실제 확인](48-library-delete-fixed.png). QA 복사본을 삭제하고 목록에서 사라짐을 확인했다. |
| 수정 | embro-worker | 폴드 홈의 안내 문구에서 마지막 ‘요.’만 다음 줄로 떨어졌다. 안내를 그림 아래 전체 폭으로 옮겼다. 사용자 정의 기본 프레임도 생성 선택 목록 맨 위로 정렬한다. [폴드 홈](46-home-fold.png) |
| 수정 | embro-worker | 인치 병기로 뷰어 크기 통계가 좁은 열에서 잘릴 수 있었다. 세로 화면은 크기 전체 폭 행으로 분리했다. 목록·설정·후프 선택도 mm/in 표기를 적용했다. |
| 수정 | embro-worker | 최신 main의 ExportChecks가 기종 최대 크기만 검사했다. 선택한 사용자 프레임의 실제 0.1mm 크기를 전달하도록 연결했다. |
| 수정 | embro-worker | SE400 설정에 땀수 제한이 ‘미확인’으로 표시됐으나 docs의 공식 FAQ는 30,000땀을 확인했다. 설정 설명을 공식 확인값에 맞췄다. |
| 전달 | embro-editor | 후프·X/Y·가로/세로 속성이 mm만 표시된다. 공용 Dimensions.length/size 사용 필요. [편집기](16-editor-moved-rotated.png) |
| 전달 | embro-letter / embro-fonts | MYEMBRO에서 둥근고딕 권장 최소 높이 992.7mm, 담백한 고딕 255.8mm가 표시된다. 품질 판정 수치와 사용자 안내 재검토 필요. [영문](19-lettering-english.png) |
| 전달 | embro-letter | 레터링 후프·글자 높이·자간·줄 간격에 인치 미표기. Dimensions 포맷터 사용 필요. |
| 통합 필요 | embro-editor | MainActivity의 새 콜백 연결은 main에 반영됐다. 다만 EditorViewModel.constrainHoop/editorMachine의 목록이 사용자 프레임·미지원 프레임을 여전히 첫 지원 후프로 보정하고 내보내기를 차단한다. 요청 HoopSpec 유지 및 경고 선택 정책 반영 필요. docs/machines.md의 QA 후 통합 메모 참고. |
| 미확인 | embro-codex / embro-model | 확장 기종 공식 사양표 미수신. SA434 위치 개수·오프셋은 공식 확인 전까지 빈 목록. 임의 3위치/±35mm를 제품 사양으로 넣지 않음. |

담당 홈·목록·뷰어·설정·picker에서 하드코딩 UI 색을 검색했고 발견하지 않았다.
자수 실 색과 도안 썸네일은 실제 도안 데이터이며 브랜드 UI 토큰과 구분한다.

## 추가 설정·펼친 화면 검증

- SE400→SE600→SE700 전환과 각 USB/무선·내부 메모리 안내를 확인했다.
  [SE600](37-settings-se600.png), [SE700](38-settings-se700.png), [자유 기종](45-settings-free.png).
  자유 기종 안내 문구는 최종 커밋에서 ‘기종 제한을 적용하지 않는 자유 설정’으로 명확히 했다.
- 사용자 프레임: 1×2.5in 입력→25.4×63.5mm 저장→가로 50.8mm로 편집→기본값 선택→삭제.
  [인치 입력](40-custom-frame-inch.png), [편집](41-custom-frame-edited.png),
  [홈 기본값](42-home-custom-default.png), [삭제 확인](44-custom-delete-confirm.png).
- 사용자 프레임을 생성 시트에서 선택했지만 편집기 헤더는 100×100mm였다.
  [실제 재현](43-editor-custom-clamped.png). MainActivity 전달 이후 편집기 자체가 보정하는 문제이며
  `embro-editor`의 요청 크기 유지 수정이 남는다. 정밀 크기 저장·Preflight 어댑터 단위 테스트는 통과했다.
- `adb shell wm size 1968x2184`: [홈](46-home-fold.png), [목록](47-library-fold.png),
  [뷰어](49-viewer-fold.png), [picker](50-picker-fold.png). 600dp 이상에서 2열과 캔버스/정보 병렬 배치 확인.
  뷰어 mm/in 크기는 정보 패널에서 두 줄로 읽을 수 있으며 재생·후프·속도 컨트롤도 접근 가능하다.
- 최종 `assembleDebug`, 전체 `:app:testDebugUnitTest`, `git diff --check` 통과.

## 원복 및 범위

`wm size reset` 후 **1080×2400 / 420dpi**, 앱 언어 **[] (시스템 기본)**, 시스템 나이트 **no**,
앱 **밝게**, 기종 **SE400**, 기본 후프 **100×100**으로 복구했다. QA 사용자 프레임과 QA 도안
복사본·QA 프로젝트 파일을 제거했고 원래 도안·설치 글꼴은 유지했다. 실제 USB 하드웨어 전송과
SE400/600/700 실기 봉제는 이 에뮬레이터 QA 범위 밖이며 확인하지 않았다.

확장 기종표는 아직 수신하지 않았다. ‘확인’ 표의 공식 값이 도착하기 전에는 큰 틀 기종과 PR의
한계·호환 후프·전송 방식을 임의 추가하지 않는다. SA434의 위치 개수/오프셋도 미확인 상태다.
