# 미싱 프로필

확인일: 2026-10-22. 브라더 공식 자료(미국·캐나다) 기준이며 기본 기종은 **Brother SE400**입니다.
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
  USB 메모리를 미싱에 직접 꽂는 방식으로 안내하지 않습니다.
  [Brother Canada 공식 FAQ](https://support.brother.ca/app/answers/detail/a_id/133968/~/design-does-not-appear-on-lcd-after-being-transferred)는
  적용 기종에 SE400을 명시하고 도안당 최대 **30,000땀**, 도안과 저장 후프 모두 최대 4×4인치를 안내합니다.
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
  연결하세요. 기본 콜백은 기존 `onOpenLibrary`를 사용하므로 미연결 상태에서는 목록으로 이동합니다.
- 레터링 화면은 아직 없어 시트와 빠른 시작에서 숨깁니다. 실제 레터링 경로가 생기면
  `HomeScreen(letteringAvailable = true, onLettering = { ... })`로 연결하세요.
- 홈 갤러리는 실제 PES 샘플만 표시하며 빈 캔버스 템플릿은 제거했습니다.
  샘플은 기존 `onOpenRecent(file)`로 뷰어에서 열고 편집 버튼으로 프로젝트를 만듭니다.
- 도안 목록은 관리 전용입니다. 만들기 버튼은 제거했으며 기존 `LibraryScreen.onCreate` 인자는
  MainActivity 소스 호환을 위해 남겨 두었지만 호출하지 않습니다.


## SE400 파일 호환성 조사 및 엔진 Preflight

확인일: 2026-10-22. SE600/SE700 같은 다른 4×4 기종에 SE400의 땀수 제한을 그대로
적용하지 않습니다. 아래의 **미확인**은 무제한 또는 지원한다는 뜻이 아닙니다.

| 항목 | SE400에서 확인한 범위 | 근거 |
| --- | --- | --- |
| 형식 | PES, DST | [USB 설명서](https://download.brother.com/welcome/doch000186/885v31_v32_usb02_usenes.pdf), 인쇄 p.2 |
| PES 버전 | v1/v6 각각의 지원 여부와 최대 지원 버전 **미확인** | 공식 설명서는 PES라는 형식만 명시 |
| 도안 크기/저장 후프 | 최대 100×100mm, 저장된 후프도 4×4인치 이하여야 함 | [사용 설명서](https://download.brother.com/welcome/doch000184/885v31_v32_v33_om04en.pdf) p.127, [공식 FAQ](https://support.brother.ca/app/answers/detail/a_id/133968/~/design-does-not-appear-on-lcd-after-being-transferred) 항목 8 |
| 최대 땀수 | 도안당 30,000땀 | 같은 공식 FAQ 항목 9, 관련 기종에 SE400 명시 |
| 색상/색상 교체 수 | 수치 **미확인** | USB 설명서는 제한이 있다고만 명시 |
| 파일명 | 영문·숫자·`_`·`-` 권장, PES 최대 길이 **미확인** | USB 설명서 p.2. DST의 첫 8자 표시는 PES 길이 제한이 아님 |
| 폴더/PC 전송 묶음 | 이동식 디스크 최상위에 최대 12개 | USB 설명서 pp.2,4 |
| 내부 저장 메모리 | 최대 12개 또는 전체 512KB | [SE400 공식 메모리 FAQ](https://support.brother.com/g/b/faqend.aspx?c=us&faqid=faqh00000472_001&lang=en&prod=hf_se400eus) |
| PC 전송 파일당 용량 | **미확인** | 내부 메모리 512KB를 파일당/PC 전송 용량으로 확대하지 않음 |
| PEC 썸네일 필수 여부 | 생략 시 거부하는지 **미확인** | 공식 자료에 바이트 형식 요구 없음. DST는 썸네일 없이 이름 표시 가능(USB 설명서 p.2) |

### 포맷/커뮤니티 근거와 구현 선택

- [pyembroidery 1.5.1 PesWriter.py](https://github.com/EmbroidePy/pyembroidery/blob/1.5.1/pyembroidery/PesWriter.py)는
  최소 v1과 전체 v6 경로를 제공합니다. v6는 `CEmbOne`/`CSewSeg`, 사용자 실 표,
  PEC 데이터 및 추가 색상 정보를 씁니다. 이는 구현 근거이며 SE400 인증 자료가 아닙니다.
- 커뮤니티 역공학 자료 [EduTech Wiki: PES](https://edutechwiki.unige.ch/en/Embroidery_format_PES)는
  PES 편집 정보와 PEC 봉제 데이터의 역할, 버전 간 PEC 호환성 및 v4 이후 추가 정보를 설명합니다.
  이것만으로 특정 SE400 펌웨어가 v6를 받는다고 보장할 수 없습니다.
- myembro v6는 위 pyembroidery 버전의 전체 헤더/봉제 객체 구조를 포팅했습니다.
  v1/v6 모두 48×38 1비트 전체/색상별 PEC 썸네일을 기록합니다.
  PEC의 고유 실 64개·STOP 포함 255블록은 **라이터 포맷 제한**이며 SE400 색상 제한으로 취급하지 않습니다.
- v6의 저장 후프 기본값은 pyembroidery와 같은 100×100mm입니다. 실제 100×100 초과 도안은
  이 헤더만으로 적합해지지 않으므로 Preflight 경고를 확인해야 합니다.

### 통합 담당: 내보내기 전에 검사하고 버전을 선택하기

엔진 `io.PesWriteSettings`와 `export.MachineProfile`은 앱 설정/UI와 독립적입니다.
기존 앱 `ui.machines.MachineProfile`과 동명이라 둘을 함께 쓰는 곳은 import alias를 사용하세요.
앱의 기본 출력 버전 선택 UI·설정 저장·경고 표시는 이번 변경에 연결하지 않았습니다.

```kotlin
val settings = PesWriteSettings(PesVersion.V6) // 앱에서 저장한 선택값; 미지정 기본값은 기존 V1
val warnings: List<String> = Preflight.check(pattern, "design.pes", MachineProfile.SE400)
// warnings를 한국어로 표시한 후 내보내기 진행 여부를 사용자가 선택하도록 연결
val bytes = PesWriter.write(pattern, settings) // 또는 PesWriter.writeV6(pattern)
// 실제 저장 바이트의 경계/명령을 확인하려면 PesReader.read(bytes)도 검사 가능
```

`Preflight.check`는 첫 END까지 STITCH 수와 이동을 포함한 크기, 파일명 권장 문자·확장자·폴더,
전송 묶음의 도안 수를 검사해 한국어 경고 목록을 반환합니다. 자동 축소·삭제·파일명 변환은 하지
않습니다. `patternCount`는 기존 도안을 포함한 대상 묶음 수입니다. 내부 메모리 저장일 때만
`destination = ExportDestination.INTERNAL_MEMORY, totalBytes = ...`로 전체 사용량을 넘기세요.
PC 전송의 파일 용량에 512KB를 적용하지 않습니다. 미확인 색상 수·PES 이름 길이·버전 지원은
판정하지 않습니다. 프로젝트의 후프 상대 위치 초과는 기존 `Bounds.outside(project.hoop)`도
함께 확인해야 합니다.

`PesExport.prepare(data, fileName, settings)`는 PEC/DST 변환에 선택 버전을 적용하지만,
이미 PES인 파일은 편집 정보를 보존하도록 바이트 그대로 전달합니다. PES를 v6로 다시 만들려면
`PatternReader.read` 후 `PesWriter.writeV6`를 명시적으로 호출하세요. 고수준 벡터 편집 객체를
복원하는 변환은 아닙니다.

### 실제 SE400 확인 필요

엔진 왕복과 pyembroidery 교차 검증은 통과했지만 **SE400 실기 인식/봉제는 미검증**입니다.
같은 작은 100×100 이내 도안을 v1/v6로 각각 전송해 목록 표시·방향·실 순서·STOP·점프·트림을
확인해야 합니다. 특히 파일 TRIM 명령의 보존이 SE400의 자동 실 자르기 동작을 뜻하지 않습니다.
실기 확인 전에는 기본 출력 버전을 강제로 v6로 바꾸지 않습니다.
