# 무료 도안 디렉터리 / 공유 가져오기 연결 메모

## 현재 구현과 통합 담당 작업

`main`의 `e0d2c32`를 병합한 뒤 구현했다. MainActivity는 수정하지 않았다.
공개 목적지는 `FreeDesignSourcesScreen(onBack: () -> Unit)`이며, 상위의
`MyembroTheme` 안에서 호출한다. 홈/도안 목록의 '무료 도안 찾기' 액션에 이 목적지를
연결하고 화면 스택의 뒤로 가기 콜백을 전달하면 된다.

화면은 검증된 JSON을 오프라인으로 읽고 Custom Tabs로 사이트와 약관을 연다.
실제 도안·사이트 이미지·로고를 새로 번들하거나 자동 다운로드하지 않는다.
Custom Tabs 미지원 브라우저에서는 일반 브라우저로 열리며, 브라우저가 없으면
스낵바를 표시한다. AndroidX Browser 1.8.0을 사용한다.

Manifest에 SEND / SEND_MULTIPLE 필터를 추가했지만, **현재 MainActivity는 VIEW만
처리한다.** 통합 담당이 `handleIntent`에 아래 경로를 연결해야 실제 공유 가져오기가
동작한다. `onCreate`와 기존 `onNewIntent` 모두 이 처리기를 통과해야 한다.

1. SEND는 `IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)`,
   SEND_MULTIPLE은 `IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)`로 URI를 받는다.
   일부 공유 앱을 위해 `intent.clipData`의 각 `getItemAt(i).uri`도 합치고 중복 URI를 제거한다.
   HTTP 문자열/EXTRA_TEXT 링크는 다운로드하지 않는다. `content://` URI만 처리한다.
2. 임시 읽기 권한이 유효한 동안 `Dispatchers.IO`에서 각 URI를
   `DesignLibrary(context).importShared(uri): List<File>`에 전달한다. 하나의 ZIP에서
   여러 파일이 나올 수 있으므로 기존 단일 반환 `importForViewing`에 넘기지 않는다.
   일반 파일 선택기에서 ZIP을 선택한 경우에도 이 새 API를 사용한다.
3. 한 URI의 성공 결과를 누적하고, 실패 URI는 사용자에게 오류를 표시한다. URI별로
   독립적인 가져오기이므로 앞선 URI의 성공을 뒤 URI의 실패로 지우지 않는다.
4. 성공 후 `LibraryViewModel.refresh()`를 호출한다. 결과가 한 파일이면 기존
   `openDesign(file)`로 뷰어를 열고, 여러 파일이면 도안 목록으로 이동한다.
   오류나 ZIP을 기존 `viewer.open(uri)` fallback으로 넘기지 않는다.

핵심 호출 예:

```kotlin
val stored: List<File> = withContext(Dispatchers.IO) {
    DesignLibrary(applicationContext).importShared(uri)
}
library.refresh()
// 한 개: openDesign(stored.single()); 여러 개: LIBRARY 목적지
```

## 가져오기 계약

Android 어댑터 `DesignLibrary.importShared`는 display name/MIME을 조회하고 스트림을
열어 순수 JVM `DesignFileImporter(directory).import(input, fileName, mimeType)`에 전달한다.
테스트·비 Android 호출에서는 후자를 직접 쓸 수 있다. 호출자는 스트림을 `use`로 닫는다.

- ZIP magic도 검사하므로 `application/octet-stream` / 확장자 없는 ZIP도 인식한다.
- 내부 `.pes` / `.pec` / `.dst`만 읽는다(대소문자 무관). 중첩 ZIP은 재귀로 풀지 않는다.
- `/`·`\` 경로를 모두 버리고 파일명만 사용한다. 제어 문자/예약 문자를 치환하고
  파일명 길이를 제한한다. `__MACOSX`와 `._` 파일은 무시한다.
- 기본 상한: 파일당 16 MiB, 전체 해제 데이터 64 MiB, 읽은 원본 데이터 64 MiB,
  ZIP 엔트리 512개, 도안 100개. 무시한 항목도 바이트·엔트리 상한에 포함한다.
- ZIP 헤더의 크기를 신뢰하지 않고 실제 읽은 바이트를 세며, 지원하지 않는 파일도
  제한된 버퍼로 읽는다. 엔트리 CRC 오류/읽기 오류는 가져오기 실패로 처리한다.
- 모든 후보 도안을 임시 파일에 쓴 뒤 `PatternReader`로 검증하고 게시한다.
  중간 실패 시 해당 URI에서 게시한 새 파일과 임시 파일을 제거한다.
- 중복 파일명은 `name (1).pes`처럼 저장하고 기존 파일을 덮어쓰지 않는다.
- 도안이 없는 ZIP, 잘못된 파일, 상한 초과는 예외를 반환한다. UI에서 실패를 안내한다.

## 검증

`DesignFileImporterTest`: 실제 PES/PEC/DST, Unix/Windows 경로 탐색, 중복 이름,
일반 MIME ZIP, 긴 Unicode 이름, 잘못된 도안의 전체 롤백, 무시한 파일의 압축 폭탄,
입력/해제 크기 상한, 엔트리/도안 개수 상한, 빈/손상 ZIP.

`FreeDesignSourcesTest`: 디렉터리 한영 텍스트/검증일/형식/약관, 중복 ID,
안전하지 않은 URL 및 미지원 스키마 버전 거부. 네트워크 확인은 오프라인 단위 테스트에
넣지 않고 [출처 문서](free-design-sources.md)와 JSON의 evidenceUrls에 기록한다.
