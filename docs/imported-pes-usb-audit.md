# 가져온 PES의 USB 전송 바이트 보존

## 코드 경로

- 직접 USB: `DirectUsbExporter.export` → `DesignFormats.preparePes` → `PesExport.prepare` → `DirectUsbExporter.write` → `Fat12Volume.writeFile`.
- 마운트된 USB(SAF): `UsbExporter.export` → 동일한 `DesignFormats.preparePes` → 출력 스트림에 `prepared.bytes` 기록.
- PES 시그니처(`#PES`)가 있으면 `PesExport.prepare`는 `PecViewportCheck.requireValid` 후 입력 바이트를 그대로 반환한다(`converted=false`). V1/V6 출력 설정을 바꿔도 기존 PES를 변환하지 않는다. 이름은 SAF 충돌 회피 또는 직접 USB 8.3 규칙에 따라 달라질 수 있다.
- PEC/DST와 편집 프로젝트는 별도의 생성/변환 경로로 `PesWriter`를 사용한다. PES를 편집 프로젝트에 넣어 다시 생성하는 동작은 원본 파일 직접 전송과 다르다.

## 회귀 테스트

`UsbTransferReportTest`는 기존 공개 픽스처 5개(`stops.v1/v6`, MIT EmbroidePy의 Brother v5/v6·Wilcom v6)를 각각 V1/V6 설정으로 준비한 뒤 실제 직접 USB 쓰기 함수와 합성 SE400 FAT12 장치를 사용한다. 독립 재마운트 후 파일을 읽어 원본과 전체 바이트를 비교한다. min x/y=0, 헤더=실제 좌표 범위도 assert한다. 음수 원점·1단위 헤더 불일치·선택 후프 초과는 재인코딩 없이 거부하고 원본 파일을 보존하는지 검사한다.

로컬 전용 검증은 `MYEMBRO_USB_AUDIT_INPUTS`에 정확히 5개의 절대 경로를 담은 `/tmp` 텍스트 파일을 지정한다. 코퍼스 중 SE400 0–999 범위 및 헤더 검사를 만족하는 5개를 사용했다. 개인 파일·경로 목록·내용은 커밋하지 않는다. 익명 치수·바이트 수·SHA-256 결과는 `/tmp/myembro-imported-usb-audit.txt`에만 저장한다.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 \
MYEMBRO_USB_AUDIT_INPUTS=/tmp/myembro-imported-usb-inputs.txt \
./gradlew :app:testDebugUnitTest --tests '*UsbTransferReportTest'
```

실행 결과: `BUILD SUCCESSFUL`, 8 tests / 0 failures / 0 errors. 공개 5개와 로컬 코퍼스 5개 모두 V1/V6 설정 각각에서 준비·쓰기·재마운트 후 전체 바이트 일치. 로그: `/tmp/myembro-imported-usb-test.log`.

이 테스트는 USB 하드웨어나 Android SAF provider를 실행하지 않는다. SAF의 공용 준비 함수와 스트림 입력 바이트는 코드 경로로 확인했고, BOT 파일 쓰기부터 FAT12 read-back은 메모리 블록 장치로 실행했다. 기존 PES의 P1/P2/P3 위반은 자동 수정하지 않고 차단한다. 이 검증이 기존 PES에 생성 도안용 잠금·밀집 정책까지 적용한다는 뜻은 아니다.
