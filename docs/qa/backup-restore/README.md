# 전체 백업·복원 QA

main `b95f2be`에서 시작. 서명 설정·키는 변경하지 않았다.

설정 → **백업 저장**에서 SAF로 ZIP을 앱 밖(Downloads 등)에 저장한다. 재설치 후 설정 → **ZIP 복원**에서 선택하고 중복 건너뛰기/사본으로 추가를 고른다. 취소하면 아무것도 복원하지 않는다. 설정과 즐겨찾기는 확인창에 안내한 대로 백업값으로 복원한다.

## 보존 범위

- `files/designs`, `projects`, `drafts`: 원본 PES와 편집 프로젝트를 파싱/재인코딩 없이 바이트 그대로 보관한다.
- `files/fonts`: 다운로드·사용자 글꼴, 메타데이터, 라이선스, 품질 정보와 manifest의 글꼴 파일 목록을 포함한다.
- `myembro_settings`, `myembro_library`, `lettering`, `generated_pes_origins`의 typed SharedPreferences: 기종·후프·테마·즐겨찾기·최근 글꼴·생성 도안 출처를 포함한다.
- USB 전송 기록 파일도 포함한다. 캐시·미완성 tmp/part 파일·USB SAF 권한은 제외한다. USB 폴더 권한은 재설치 후 다시 허용해야 한다.
- 중복 사본은 도안/프로젝트를 `이름 (1).확장자`로 추가한다. ID와 sidecar 파일로 구성된 **기존 글꼴은 유지**하고 없는 글꼴 파일을 복원한다. 글꼴 파일명만 바꾸어 깨진 글꼴 사본을 만들지 않는다.

ZIP은 버전·파일명·크기·SHA-256 manifest를 검증한 뒤에만 반영한다. 경로 이탈·중복 ZIP 엔트리·손상·지원하지 않는 버전은 거부한다. 새 파일만 생성하며 기존 파일은 덮어쓰지 않는다. 반영 실패 시 이번에 생성한 파일은 제거한다. 설정 저장 실패 시 기존 설정 복구도 시도한다. 파일별 256MiB·전체 2GiB·2만 파일 상한이며 I/O는 Dispatchers.IO에서 수행한다.

## 자동 검증

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug
```

`BackupArchiveTest` 4건: 공개 PES를 포함한 백업→데이터 디렉터리 전체 삭제→복원 후 파일 수/바이트 일치, 중복 건너뛰기/사본, 취소/실패 롤백, 경로 이탈·버전·체크섬 손상 거부.

실제 Android 검증은 별도 applicationId **`io.github.linker2k.myembro.backupsmoke`**로만 수행했다. 사용자 설치 앱과 데이터는 지우지 않았다. `BackupInstrumentation`은 실제 filesDir와 SharedPreferences를 백업 후 삭제/clear하고 복원한다. 문자열·한글 문자열 집합·boolean·int·long·float를 비교했다.

```
BACKUP_DELETE_RESTORE_PASS files=4 byte_equal=true typed_preferences_equal=true
INSTRUMENTATION_CODE: -1
```

커밋의 instrumentation은 `.backupsmoke` 패키지에서만 동작하도록 제한했다. 로컬 Gradle init script로 applicationId를 바꾸고 `-PmyembroTestRunner=io.github.linker2k.myembro.backup.BackupInstrumentation :app:assembleDebug :app:assembleDebugAndroidTest`로 빌드한다. 기본 앱/릴리스 서명 설정 변경은 없다.

에뮬레이터 emulator-5554에서 설정 → SAF 저장 → 완료 창 → SAF ZIP 선택 → 중복 확인 → 건너뛰기 → 복원 결과를 직접 클릭했다. 공개/합성 데이터 31개 ZIP에서 `Restored 0 files · skipped 31`을 확인했다. 한국어/영어 화면 스크린샷을 함께 보관한다. 로컬 로그: `/tmp/myembro-backup-instrumentation.log`, `/tmp/myembro-backup-tests.log`, `/tmp/myembro-backup-check.log`.

추가로 격리 패키지에만 실제 `pm clear`를 실행했다. 사용자 일반 앱은 변경하지 않았다.

```text
ZIP_MANIFEST_COMPARE_PASS files=31 sha256_equal=true typed_preferences_equal=true
PM_CLEAR_ISOLATED Success
ZIP_MANIFEST_COMPARE_PASS files=31 sha256_equal=true typed_preferences_equal=true
SAF_PM_CLEAR_RESTORE_AND_CANCEL_SMOKE_PASS en_ko=true
```

이 추가 검증은 `tools/smoke_backup_restore.py --zip /sdcard/Download/myembro-backup-2026-10-24.zip`로 재현한다. 먼저 격리 앱에서 합성 데이터 백업을 저장해야 한다. 백업 대상과 관계없는 Android `profileInstalled` 런타임 마커는 개수에서 제외한다. 실제 파일·설정 비교는 ZIP manifest를 기대값으로 하며 취소 후에도 다시 비교한다. 전체 `check`/APK 빌드 성공: 엔진 640건, 앱 debug/release 각각 221건, 실패 0. 로그 `/tmp/myembro-backup-real-clear.log`.

![설정 백업](settings-ko.png)
![복원 확인](restore-confirm-ko.png)
![복원 결과](restore-result-en.png)
