# SE400 직접 USB / FAT12 — integrator 메모

`main`을 먼저 fast-forward(`ba4f5e2`)한 `codex/worker`에서 구현했다. push는 하지 않는다.
기존 도안 목록·뷰어→목록 USB 내보내기 콜백에 이미 연결되어 별도의 MainActivity Kotlin 연결은 필요 없다.

## 계층과 동작

- `engine/fat12/BlockDevice`: `sectorSize`, `sectorCount`, LBA 단위 read/write. Android 의존성 없음.
- `Fat12Volume.mount`: sector0의 BPB를 먼저 검사해 슈퍼플로피를 인식한다. BPB가 아니면 MBR의
  첫 비어 있지 않은 **primary FAT12(type 01)** 파티션을 사용한다. GPT/확장 파티션과 FAT16/32는 거부한다.
  BPB 섹터 크기와 장치 섹터 크기는 같아야 하며 512/1024/2048/4096바이트를 지원한다.
- 루트 파일 목록·용량/할당 가능한 여유 공간·고정 루트 엔트리 수, 새 파일 쓰기/읽기/삭제를 제공한다.
  하위 폴더 생성/탐색·포맷·복구는 제공하지 않는다. 읽기 전용 파일/디렉터리는 삭제하지 않는다.
- FAT12 홀짝 12비트 엔트리와 섹터 경계 걸침을 처리한다. 2개 FAT 볼륨은 두 사본을 모두 갱신한다.
  기존 사본 불일치, 루트 파일의 교차/순환 체인, 잘못된 크기/범위를 발견하면 거부한다.
- 쓰기 순서: 데이터→FAT 사본들→루트 엔트리. 삭제 순서: 루트 참조 제거→FAT 해제.
  각 전송 후 장치에서 다시 읽어 비교하고 새 파일 전체도 재읽기한다. I/O 실패 후에는 그 세션을
  폐기하며 후속 파일을 계속 쓰거나 자동 재시도하지 않는다. 기존 BPB/MBR은 쓰지 않는다.
- `ShortNames`: ASCII 대문자 8.3, 중복 `_1`… 접미사, 비ASCII-only 이름은 DESIGN.PES 등으로 매핑.
  LFN은 생성하지 않는다. 기존 LFN이 붙은 파일의 삭제는 순번/체크섬이 확인된 소유 슬롯만 함께 지운다.
- app `LibaumsBlockDevice`: libaums **core 0.10.0** `BlockDeviceDriver`를 사용한다. 이 버전의
  `ScsiBlockDevice.blocks`는 실제로 마지막 LBA이므로 **+1**해 sectorCount로 바꾼다. 버전 갱신 시
  이 의미를 재확인해야 한다. 블록 전송은 최대 16KiB 청크이며 offset은 바이트가 아닌 LBA다.
- `DirectUsbAccess`: class 8 / transparent SCSI 6 / BOT 80 인터페이스·bulk IN/OUT·LUN 0만 지원한다.
  libaums `UsbMassStorageDevice.init`, Partition/FS factory는 호출하지 않는다. USB attach/detach,
  명시적 UsbManager 권한 요청, 거부/분리/해제를 처리한다. 권한 전에 장치에 쓰거나 마운트하지 않는다.
- `UsbExporter.drives`: 마운트된 SAF 드라이브가 있으면 기존 경로, 없으면 BOT/SCSI 타깃을 직접 드라이브로
  노출한다. 라벨에는 실제 productName(없으면 VID/PID)을 함께 보여준다. 라벨이 SE400 인증을 뜻하지 않으며
  FAT12 이외의 장치는 쓰지 않는다. 여러 직접 타깃은 기존 드라이브 선택 창으로 구분한다.
- 모든 파일 변환·장치 열기·마운트·쓰기/삭제/재읽기는 Dispatchers.IO에서 수행한다. 권한/선택 UI만 메인 스레드다.
- 배치 공간/루트 엔트리 검사를 먼저 한다. 부족하면 `DirectUsbSpaceDialog`의 기본 선택 없는 체크 목록을
  표시한다. 선택한 PES만 지우며 충분한 공간/슬롯이 되는 경우에만 확인 버튼을 켠다. 다시 열었을 때
  USB 장치 ID와 BPB/FAT/루트 fingerprint가 달라지면 삭제하지 않는다. 파일 내용 전체의 fingerprint는 아니다.

libaums 고정 버전 소스:
https://repo.maven.apache.org/maven2/me/jahnen/libaums/core/0.10.0/core-0.10.0-sources.jar
프로젝트: https://github.com/magnusja/libaums
USB 권한/인터페이스 필터: https://developer.android.com/develop/connectivity/usb/host
FAT 포맷 근거: Microsoft-authored [Microsoft FAT Specification](https://www.scs.stanford.edu/~zyedidia/docs/_other/fat.pdf).

## 검증

필수 JVM 테스트 도구는 dosfstools의 `/usr/sbin/mkfs.fat`, `/usr/sbin/fsck.fat`다.
Ubuntu CI에는 dosfstools 설치 단계와 engine/app JVM 테스트를 추가했다.

```bash
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug
```

테스트 중 `mkfs.fat -F 12 -C ... 480`으로 480KiB 이미지를 임시 디렉터리에 생성한다. 생성 이미지와
MBR 변형은 커밋하지 않는다. 정상 쓰기/삭제 뒤 `fsck.fat -n` exit 0을 확인한다. MBR 변형은 파티션
내용을 별도 임시 파일에 꺼내 fsck하고 MBR/앞쪽 영역 불변도 검사한다.

2026-10-23 최종 실행: 엔진 **196개**, 앱 JVM **110개** 테스트가 모두 통과하고 assembleDebug가 성공했다.
새 FAT12/USB 테스트는 엔진 10개 + 어댑터 3개 + 삭제 계획 2개다. APK의 libaums 라이선스/공지 포함도 확인했다.

검증 항목: 실제 PES 바이트 왕복, 홀짝/섹터 경계 FAT 엔트리, 조각난 할당, FAT 사본 일치,
디스크/루트 엔트리 부족 시 무변경, 8.3 중복 이름, LFN 삭제, 손상 BPB/짧은 장치/사본 불일치 거부,
외부 메타데이터 변경 거부, 읽기 비교 실패 및 두 번째 FAT 사본 쓰기 도중 실패 후 추가 쓰기 차단.
앱 JVM 테스트는 fake SCSI 드라이버로 마지막 섹터/16KiB 청크/짧은 읽기/엔진 브리지를 검사하고,
삭제 목록 기본 미선택·선택된 파일만 허용·공간과 루트 슬롯 동시 충족 조건을 검사한다.

## 실제 SE400 + Fold7 확인 필요

실기 쓰기는 아직 검증하지 않았다. 구현 테스트의 성공을 실기 지원 확인으로 보고하지 않는다.

1. 다른 USB 파일 관리자와 Windows 연결을 닫고 SE400 전원을 켠 상태에서 Fold7에 연결한다.
2. 내보내기 드라이브에 ‘SE400 직접 연결 · 실제 장치명’이 보이고 USB 권한 허용 후 FAT12 BPB가
   읽히는지 확인한다. VID/PID, sectorSize/sectorCount와 실제 BPB/MBR 유무는 현재 미확인이다.
3. 작은 100×100 이내 PES 한 개를 전송해 앱의 실제 8.3 이름·재읽기 완료 메시지와 미싱 목록 인식을 확인한다.
4. 공간 부족 시 취소하면 파일이 유지되는지, 선택한 PES만 삭제하는지, 충분한 용량으로 재시도되는지 확인한다.
5. 미싱 전원을 끄면 파일이 사라지는 실측 특성을 안내한다. 쓰는 도중 전원/케이블을 분리하지 않는다.

FAT는 저널 파일시스템이 아니므로 전원/케이블 분리가 FAT 사본 업데이트 중 발생하면 불일치/할당 누수가
남을 수 있다. 코드는 복구/포맷하지 않는다. 비정상 볼륨에서 반복 쓰기를 하지 말고 다시 연결해 확인한다.
SE400의 실제 펌웨어·BOT 명령 호환, 폴드7 USB 권한 UI, native AAR 동작 및 봉제는 실기 확인 범위다.
