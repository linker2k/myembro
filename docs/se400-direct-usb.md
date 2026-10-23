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

## 실제 SE400 + Fold7 검증

2026-10-23 alpha26: 사용자가 **직접 USB 전송 및 SE400에서 도안이 정상적으로 열림**을 확인했다.
아래는 최초 확인 절차이며 봉제, 전원/케이블 분리 중 오류 등은 별도 확인 범위다.

1. 다른 USB 파일 관리자와 Windows 연결을 닫고 SE400 전원을 켠 상태에서 Fold7에 연결한다.
2. 내보내기 드라이브에 ‘SE400 직접 연결 · 실제 장치명’이 보이고 USB 권한 허용 후 FAT12 BPB가
   읽히는지 확인한다. VID/PID, sectorSize/sectorCount와 실제 BPB/MBR 유무는 현재 미확인이다.
3. 작은 100×100 이내 PES 한 개를 전송해 앱의 실제 8.3 이름·재읽기 완료 메시지와 미싱 목록 인식을 확인한다.
4. 공간 부족 시 취소하면 파일이 유지되는지, 선택한 PES만 삭제하는지, 충분한 용량으로 재시도되는지 확인한다.
5. 미싱 전원을 끄면 파일이 사라지는 실측 특성을 안내한다. 쓰는 도중 전원/케이블을 분리하지 않는다.

FAT는 저널 파일시스템이 아니므로 전원/케이블 분리가 FAT 사본 업데이트 중 발생하면 불일치/할당 누수가
남을 수 있다. 코드는 복구/포맷하지 않는다. 비정상 볼륨에서 반복 쓰기를 하지 말고 다시 연결해 확인한다.
SE400의 실제 펌웨어·BOT 명령 호환, 폴드7 USB 권한 UI, native AAR 동작 및 봉제는 실기 확인 범위다.

## alpha14 긴급 통합: RAM 디스크 호환성과 진단

main `7aa0806` 기반. `:engine:test` **214개**, `:app:testDebugUnitTest` **117개**, `:app:assembleDebug` 통과.
추가 회귀 검증은 비표준 점프·OEM·미디어, BPB/FAT 미디어 차이, 16/32비트 BPB 용량 클램프,
파티션 뒤 영역 불변, MBR 오인 거부, FAT12 범위·FAT 크기 유지, 512바이트 진단 hex 왕복,
실제 BOT 응답 관찰 시 버퍼 위치·통신 횟수 불변이다. 생성 이미지 바이너리는 커밋하지 않는다.

`Fat12Volume.warnings`에 호환 경고를 보관하고 내보내기 결과에도 표시한다. 클램프는 접근 가능한
영역만 제한하며 BPB·볼륨명은 쓰지 않는다. 원래 선언된 클러스터 수가 FAT12가 아니거나 FAT가
작으면 여전히 거부한다. 실제 영역 밖을 가리키는 기존 파일 체인도 거부하며 자동으로 고치지 않는다.

실기 재시도에서 실패하면 도안 목록의 USB 실패 다이얼로그에서 **진단 정보 복사** 또는 **공유**로
텍스트를 전달한다. Superfloppy/MBR 실패 원인 모두와 INQUIRY vendor/product/revision,
READ CAPACITY 원시값·블록 수·블록 크기, sector0 512바이트 hex·BPB 해석값이 포함된다.
진단용 추가 SCSI 명령은 없고 libaums 초기화 응답을 관찰한다. 초기화/읽기 이전 실패 항목은 미확인으로
표시한다. 실기 성공 여부는 이 텍스트와 사용자 재시험 결과를 기다린다.

일괄 가져오기는 긴급 작업과 분리해 `WIP batch design import before urgent SE400 diagnostics`
이름의 git stash에 보관했다. 이 긴급 커밋만 통합해 alpha14를 배포할 수 있다.

## alpha20 긴급 통합: 실제 MBR 길이 제한

실기 진단: 순수 MBR, boot `0x80`, type `0x01`, start `32`, count `987`,
READ CAPACITY lastLBA `987` / blockCount `988`.
파티션 길이를 **956섹터(LBA 32..987)**로 제한하고 기존 BPB total 클램프에 넘긴다.
장치 밖 start·0 길이, 범위 안에 들어오지 않는 FAT/루트/데이터 및 파일 체인은 계속 거부한다.
파티션 길이를 제한해도 MBR·BPB·라벨 바이트는 그대로 두며 포맷하지 않는다.

`Fat12Volume.mount(device, onBootSector)`는 기존 읽기 결과를 파싱 전에 관찰할 수 있게 한다.
USB 진단 v2는 이를 이용해 sector0와 partition start sector (LBA 32)의 512바이트 hex·BPB를
별도로 남긴다. 실제 BPB가 실패해도 진단에 포함하며, 진단을 위한 추가 SCSI 읽기는 없다.

`Se400FirmwareLayoutTest`는 제공된 MBR 필드를 리터럴로 재현하고 988블록 장치와 합성 BPB/FAT를
만든다. 제공되지 않은 CHS/부트코드는 0으로 채웠다. PES 쓰기→재마운트→읽기, 남은 공간을
마지막 LBA 987까지 채우기, 모든 쓰기가 상대 섹터 0..955 안에 있는지, FAT 사본 일치,
MBR/파티션 앞 영역/BPB/두 볼륨 라벨 불변, 잘못된 start와 손상된 제한 영역의 거부를 검사한다.
실제 LBA 32 BPB와 미싱의 파일 인식 여부는 다음 실기 보고로 확인한다.

검증 완료: `:engine:test` **240개**, `:app:testDebugUnitTest` **156개**, `:app:assembleDebug` 통과.

## 진단 v3 긴급 통합: FAT 예약 바이트·사본 차이와 실제 115클러스터

실측 BPB는 512B/sector, 8 sectors/cluster, reserved 1, FAT 2개 × 1섹터,
root 512 entries, total 987, hidden 32다. FAT1=LBA33, FAT2=LBA34, root=LBA35..66,
data=LBA67이다. 선언 119클러스터 중 장치 안에 완전히 들어가는 **115개(FAT ID 2..116)**만
할당한다. LBA987은 한 섹터만 남아 클러스터로 사용하지 않는다. 어느 FAT든 초과 네 슬롯
**ID 117..120**이 비어 있지 않으면 마운트/쓰기를 거부하고 사본·ID·값을 보고한다.

예약 엔트리 `00 00 00`도 경고로 받아들인다. FAT1의 예약 바이트는 유지하고 정상값으로
고치지 않는다. FAT 사본 차이도 경고로 받아들이며 FAT1 기준으로 파일을 쓸 때 두 사본을
동일하게 갱신한다. 마운트는 수정하지 않는다. `unchanged()`는 각 사본의 초기 스냅샷과 비교해
기존 차이와 마운트 후 외부 변경을 구분한다. I/O 실패 후 같은 세션에서 추가 쓰기는 계속 금지한다.

`Fat12Volume.MountObserver`는 부트섹터뿐 아니라 기존 FAT/루트 읽기도 검증 전에 관찰한다.
진단 v3는 FAT1·FAT2의 앞 64바이트와 루트 첫 섹터 전체 hex를 포함한다. 실패 뒤 추가 읽기를
보내지 않으므로 이 값은 **마운트 시점 스냅샷**이다. 포맷·볼륨명 변경은 없다.

실측 BPB 필드를 재현한 테스트에서 예약 바이트 두 경우의 PES 왕복, 115개 클러스터 전체 채우기,
예약 바이트·MBR·BPB·라벨·LBA987 불변, FAT 사본 차이/동기화, 양쪽 FAT의 초과 할당 거부,
마운트 이후 외부 FAT 변경 거부와 진단 원시 바이트 보존을 검증한다. 제공되지 않은 부트코드·CHS·OEM
등은 테스트에서 합성한 값이며, 생성 이미지 바이너리는 커밋하지 않는다.

검증 완료: `:engine:test` **269개**, `:app:testDebugUnitTest` **156개**, `:app:assembleDebug` 통과.

## alpha26 이후: 지속되는 결과 창과 최근 20건 기록

- SAF/직접 USB 모두 성공·부분 실패·실패를 스낵바 대신 명시적 닫기 버튼이 있는 결과 창으로 표시한다.
- 기본 화면에는 사용자 요약(실제 이름·전송 크기·남은 공간)과 실제 문제만 표시한다.
  기술 로그/원시 실패 이유는 기본으로 접힌 ‘자세히’와 ‘진단 복사/공유’에만 표시한다.
- 확인된 SE400의 MBR/BPB 보정, 115개 클러스터 한도, 예약 바이트 00과 파생 미디어 차이는
  해당 실측 geometry에서만 정상 펌웨어 동작으로 취급한다. 엔진의 검증/쓰기 경계는 변경하지 않는다.
- 도안 목록 상단 기록 버튼에서 최근 20건을 시간·파일·결과로 열람한다. 앱 내부 JSON을 원자적으로
  교체해 보존하며 미닫힘 결과도 복원한다. 실패 후에는 손상 가능 세션에서 여유 공간을 다시 읽지 않는다.
- 전송 크기는 실제 PES 출력 바이트, 여유 공간은 FAT에서 남은 클러스터를 기준으로 한다.
  SAF 공급자가 여유 공간을 제공하지 않으면 ‘알 수 없음’으로 표시한다.

검증: `:engine:test` 269개, `:app:testDebugUnitTest` 162개, `:app:assembleDebug` 통과.
추가 JVM 검증은 최근 20건 보존/닫힘 복원/진단 왕복, 실측 SE400 배치에서 실제 이름·크기·여유
공간, 이름 충돌, 부분 쓰기 실패 시 완료 파일 보존·추가 I/O 금지, 예상 밖 FAT 차이 경고를 포함한다.
분리된 QA applicationId와 테스트 기록으로 에뮬레이터에서 en/ko 요약, 기본 접힘, 뒤로/바깥 터치로
결과 유지, 진단 복사, 닫힘의 프로세스 재실행 후 유지, 최근 기록 및 부분 실패 화면을 확인했다.
이번 후속 UI 빌드의 실제 미싱 재전송은 수행하지 않았다. QA 앱/앱별 언어 설정은 정리했다.
