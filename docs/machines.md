# 미싱 프로필

확인일: 2026-10-22. 브라더 미국 공식 자료 기준이며 기본 기종은 **Brother SE400**입니다.
공식 사양 확인과 Android 실기 검증은 구분합니다. 앱의 내보내기 형식은 PES입니다.

| 기종 | 최대 단일 자수 영역 | 확인된 후프 (가로×세로) | 전송 |
| --- | --- | --- | --- |
| SE400 | 100×100mm | 100×100, 별매 60×20, 재배치형 100×170 | USB 케이블 → PC의 이동식 디스크 |
| SE600 | 100×100mm | 100×100, 별매 60×20, 재배치형 100×170 | USB 메모리 |
| SE700 | 100×100mm | 100×100, 별매 60×20 | USB 메모리 / 브라더 무선 전송 |

큰 재배치 후프는 원단을 다시 끼우지 않고 여러 위치에 수놓는 용도입니다. 단일 도안의
최대 영역을 넓히지 않습니다. 앱은 자동 분할·재배치를 지원하지 않으며 프로젝트 후프는
세 기종 모두 100×100만 제공합니다. 나머지 홈/설정 프리셋은 선택할 수 없습니다.

## 출처와 확인 범위

- **SE400**: [Operation Manual](https://download.brother.com/welcome/doch000184/885v31_v32_v33_om04en.pdf)
  인쇄 p.127(후프), [공식 사양](https://www.brother-usa.com/p/sewing-embroidery/SE400)(최대 4×4인치),
  [USB Connections Manual](https://download.brother.com/welcome/doch000186/885v31_v32_usb02_usenes.pdf)
  인쇄 pp.2–4(PES, Windows 이동식 디스크, 최상위 폴더, 임시 디스크 최대 12개).
  PC에 USB 케이블로 연결하면 이동식 디스크로 표시됩니다. Android 폰/OTG 인식은 **미확인**입니다.
  USB 메모리를 미싱에 직접 꽂는 방식으로 안내하지 않습니다. PES 땀수 제한 수치는 **미확인**입니다.
  메모리 카드용 FAQ의 수치를 USB PES 전체 제한으로 적용하지 않았습니다.
- **SE600**: [Operation Manual](https://download.brother.com/welcome/doch101011/888m20_om02en.pdf)
  인쇄 pp.8,60(후프), pp.74–75(최대 10×10cm, PES, USB 메모리).
  도안 수·PES 땀수 제한 수치는 **미확인**입니다.
- **SE700**: [Operation Manual](https://download.brother.com/welcome/doch102582/888p10_om01en.pdf)
  인쇄 p.76(후프), pp.80–81(저장·불러오기), p.96(사양).
  **내부 메모리**는 최대 20개 / 1024KB, 도안당 최대 100,000땀으로 기재됩니다.
  이를 USB 메모리의 제한으로 확대하지 않습니다. USB 도안 수·땀수 제한 수치는 **미확인**입니다.
  [공식 무선 안내](https://support.brother.com/g/b/faqend.aspx?c=us&faqid=faqh00101118_008&lang=en&prod=hf_se700eus)는
  Design Database Transfer(Windows)와 PE-DESIGN 11을 안내하며,
  [공식 제품 페이지](https://www.brother-usa.com/p/sewing-embroidery/SE700)는 Artspira도 안내합니다.
  myembro 직접 무선 전송은 구현하지 않았습니다. 이 조사에서 대형 재배치 후프 호환은 **미확인**입니다.

세 기종의 **PES 파일명 길이 제한은 미확인**입니다. 매뉴얼은 영문·숫자·하이픈·밑줄 사용을
권장합니다. DST의 표시 길이를 PES 파일명 제한으로 사용하지 않으며, 파일명을 강제 변환하지 않습니다.

## 상태와 통합 API

`ui/machines/MachineProfile.kt`가 출처 URL과 확인된 값만 보관합니다. 영역은 engine의
`HoopSpec`을 그대로 사용하며 단위는 0.1mm입니다. `null`/빈 제한 목록은 무제한이 아닌 미확인입니다.
직접 입력한 다른 기종은 프로필이 `null`이며 SE400의 확인된 기능을 상속하지 않습니다.

```kotlin
val preferences = SettingsPreferences(context)
val settings = preferences.read()
val profile = settings.machineProfile      // MachineProfile?; 미확인 기종은 null
val hoops = preferences.editorHoops()      // List<engine.edit.HoopSpec>; 세 기종은 [H100]
val initial = preferences.defaultEditorHoop() // HoopSpec?; 미확인 기종은 null
val dispose = preferences.observe { /* 기종/기본 후프 변경 시 새 목록 조회 */ }
// 화면 또는 ViewModel 폐기 시 dispose()
```

- 기종 변경 시 기본 후프를 100×100으로 함께 저장합니다. 이전 버전의 큰 후프 설정도 읽을 때
  보정합니다. 미설정/빈 기종은 SE400으로 전환하고 기존의 직접 입력한 기종 이름은 유지합니다.
- 설정·홈·목록·뷰어는 같은 SharedPreferences 변경을 관찰합니다. 뷰어의 공개 `hoop` setter도
  지원되지 않는 후프를 보정합니다. 목록 배지는 현재 프로필과 실제 도안 경계를 비교합니다.
- 목록/뷰어의 `profile.fits(bounds)`는 **도안 크기** 판정입니다. 편집기에서 배치한 오브젝트는
  기존 engine의 `Bounds.outside(project.hoop)`로 위치까지 판정해야 합니다.
- **통합 담당**: 편집기 후프 메뉴를 `editorHoops()`로 바꾸고 새 프로젝트에 `defaultEditorHoop()`를
  사용하세요. 기종 변경으로 기존 편집 도안이 초과하더라도 자동 축소하거나 삭제하지 말고
  기존 초과 표시를 유지하세요. 미확인 기종은 수동 설정 안내가 필요합니다.
  편집기와 MainActivity 소스는 이 커밋에서 변경하지 않았습니다.

## 생성 동선

- 홈 새 프로젝트 카드와 만들기 탭은 동일한 `NewProjectSheet`를 엽니다. 빈 후프 선택 시
  지원 후프가 하나면 별도 후프 질문 없이 `onBlank(H100)`으로 시작합니다.
- `NewProjectSheet(onBlank, onFromDesign, onFromText, hoops)`는 호스트 콜백을 제공합니다.
  **통합 담당**은 `HomeScreen.onCreateFromDesign`을 도안 선택 → 선택 파일로 편집기 진입에
  연결하세요. 새 전용 `DesignPickerScreen(onSelect, onBack)`의 연결 코드는
  [디자인 시스템의 통합 메모](design-system.md#전용-도안-picker와-펼친-화면)에 있습니다.
  기본 콜백은 기존 `onOpenLibrary`를 사용하므로 호스트에서 생성 전용 경로를 지정해야 합니다.
- 레터링 진입점은 `HomeScreen(letteringAvailable = true, onLettering = { ... })`로 실제 화면에
  연결할 때만 노출합니다. 기본값은 비노출입니다.
- 홈 갤러리는 실제 PES 샘플만 표시하며 빈 캔버스 템플릿은 제거했습니다.
  샘플은 기존 `onOpenRecent(file)`로 뷰어에서 열고 편집 버튼으로 프로젝트를 만듭니다.
- 도안 목록은 관리 전용입니다. 만들기 버튼은 제거했습니다.
