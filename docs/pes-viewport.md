# SE400 PES 좌표 원점 회귀 (2026-10-24)

SE400은 PEC에 선언된 디자인 사각형을 배치한다. 중심 기준 음수 좌표를 그대로 기록한 큰 샘플러에서 사용자가 왼쪽 후프 한계에 막힌 반복 바늘을 관찰했다. 로컬 PE-Design 비교 표의 좌상단 원점과 맞춰, 기본 PES v1/v6 쓰기는 활성 명령의 최소 좌표를 `(0,0)`으로 평행이동한다. 편집기·프로젝트 원본과 화면 좌표는 바꾸지 않는다.

`PesWriter.prepareForOutput`은 원본의 첫 END까지만 범위를 구한다. 기존 0길이 정리와 좌표 이동 후 정리를 적용하며, 잠금 모양을 다시 생성하지 않는다. 첫 이동이 `(0,0)`이면 이동과 같은 위치 바늘을 생략한다. 이때 원점은 암묵적으로 존재하므로 PEC 크기와 썸네일은 0부터 최대 좌표까지 사용한다. v6의 CEmbOne/CSewSeg도 같은 폭·높이와 변환을 사용한다. 최소 v1의 22바이트 헤더에는 별도 변환 필드가 없다.

## 앱 경로

| 경로 | 실제 payload 생성 |
|---|---|
| 직접 USB / SAF USB | `DirectUsbExporter.export` / `UsbExporter.export` → `DesignFormats.preparePes` |
| 뷰어 SAF 저장 / cache 공유 | `readViewerDesign`의 프로젝트 PES 생성 → `ViewerViewModel.export` → `PesExport.prepare` |
| 편집기 PES 저장 | `EditorViewModel` → `PesWriter.write(project.flatten(), settings)` |
| PEC/DST → PES 변환 | `PesExport.prepare` → `PesWriter.write` |
| 기존 PES | 원본 바이트 보존 (변환·재배치하지 않음) |

모든 생성·변환 경로에서 기본 설정의 Writer v1/v6가 좌상단 보정을 수행한다. 명시적 `inkstitchCommands=true`는 앱에서 사용하지 않는 원본 비교용 프로필로 기존 동작을 유지한다. 기존에 저장된 잘못된 원점의 PES를 가져오는 경우도 원본 보존 정책을 따르므로, 프로젝트로부터 다시 내보내야 한다.

## 자동 회귀

- `PesViewportTest`: 공개 Writer 오버로드 전체의 리터럴 좌표 기대값, 좌상단 PES 재왕복, PEC 폭/높이, v6 변환, 암묵적 원점, 입력 불변.
- `PesOutputRoutesTest`: 실제 프로젝트 flatten/뷰어 payload/USB 공용 준비 함수/SAF·공유 공용 변환 함수의 바이트 일치, PEC 변환, 원본 PES 보존.
- 기존 생성기 테스트는 원본 좌표 기대값을 유지한다. PES 재읽기 기대값만 최종 출력 좌표로 갱신한다.
- `GatePolicyParityTest`: 변경된 합성 PES 바이트를 Python gate v6으로 다시 측정한 SHA/지표와 비교. 긴 첫 땀 사례는 원점 이동 후에도 20mm 이동을 남겨 차단 검증을 유지한다.

실행: `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug`

실기 재시험 파일: `/tmp/myembro-sewtest/s23/S23SAM4.PES` 및 PNG. 총괄이 SAM3와 바늘 순서·평행이동 일치, 범위 0..59.4 / 0..55.5mm, 0길이 0개를 독립 확인했다. Python gate 출력은 같은 경로의 `S23SAM4-gate.txt`에 `PASS 1/1`로 기록된다. 개인 코퍼스와 임시 산출물은 저장소에 포함하지 않는다.
