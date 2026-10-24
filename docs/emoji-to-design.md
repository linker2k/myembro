# 그림·이모티콘 → 도안 객체 (계획, 2026-10-24)

코드 착수 전 계획이다. 측정값은 아래 명령으로 다시 만들 수 있고, 근거 없는 숫자는 **미확인**으로 표시한다.

## A. 공개 이모지 세트 비교

라이선스는 고정한 커밋의 원본 저장소 LICENSE 파일로 확인했다. 개수·용량은 git tree API 전수, 색 분포는 세트마다 120개 무작위 표본(seed 7)이다.

| 세트 (저장소 @ 커밋) | LICENSE 파일 (확인 내용) | GPL-3.0 앱 번들 / 고지 의무 | 평면 SVG | 개수 · 용량 | 색 수 (중앙값 / p90 / ≤12색) | 그라디언트 · 선 · 투명도 |
|---|---|---|---|---|---|---|
| **Fluent Emoji Flat** (microsoft/fluentui-emoji @ 1ffb34c) | `LICENSE`: MIT, © Microsoft | 가능. 저작권·허가 문구 동봉 | 예 (`assets/*/Flat/*.svg`) | 3,145파일(이모지 1,595종 + 피부색 변형) · 17.2MB, 중앙값 5.0KB | 5 / 14 / 81% | 0% · 0% · 2% |
| Twemoji (jdecked/twemoji @ b6b55fe; twitter/twemoji 후속) | `LICENSE-GRAPHICS`: CC BY 4.0 (그래픽), `LICENSE`: MIT (코드) | 가능 (FSF: CC BY 4.0은 GPLv3 호환). **저작자 표시 + 변경 사실 표시**가 앱과 사용자가 배포하는 파생 도안에도 따라간다 | 예 (`assets/svg`) | 4,009 · 10.1MB, 중앙값 2.0KB | 5 / 10 / 96% | 0% · 0% · 0% |
| OpenMoji (hfg-gmuend/openmoji @ aeb8bb3) | `LICENSE.txt`: CC BY-SA 4.0 | 앱 번들은 가능 (CC BY-SA 4.0 → GPLv3 단방향 호환). **동일조건(SA)**이 사용자가 만든 파생 도안에도 걸린다 | 평면이지만 검은 외곽선 | 4,565 · 14.4MB, 중앙값 2.8KB | 4 / 7 / 99% | 0% · **96%** · 0% |
| Noto Emoji (googlefonts/noto-emoji @ e20cbc2) | 루트 `LICENSE`: **OFL-1.1**(2024-07-29 "Update LICENSE to OFL"). 그런데 README는 이미지가 "Apache 2.0 (./LICENSE)"라고 쓰고, `2D/svg/LICENSE`는 Apache-2.0이다 | 원본 저장소 안에서 라이선스 표기가 **엇갈린다 → 확인 필요** | 아니오 | 2D/svg 3,750 · 41.7MB, 중앙값 7.7KB | 12 / 24 / 53% | **53%** · 9% · 50% (mask/clip 12%) |

### 추천: Fluent Emoji Flat

1. **사용자 도안에 따라붙는 의무가 가장 가볍다.** 앱에는 MIT 고지 한 번이면 되고, 사용자가 수놓아 배포하는 도안에 저작자 표시(CC BY)나 동일조건(CC BY-SA)이 붙지 않는다.
2. **평면이라 변환이 단순하다.** 그라디언트·선·마스크가 0%이고 투명도는 2%뿐이다. 칠한 영역 → 땀으로 바로 이어진다.
3. **색 수가 적다.** 중앙값 5색, 81%가 12색 이하라 실 교체 횟수가 감당할 만하다.
4. **차선은 Twemoji다.** 수치(96% ≤12색, 2.0KB)는 더 좋지만 CC BY 표시가 파생 도안 배포까지 따라간다. 표시 문구를 도안 메타데이터와 내보내기 화면에 넣는 정책을 정하면 대체로 쓸 수 있다.
5. **제외:** OpenMoji(외곽선 96% + SA 의무), Noto(그라디언트 53% + 라이선스 표기 불일치).

번들 범위는 **미정**이다. 전량(17.2MB, 압축 전)은 APK 2MB 원칙을 넘는다. 자주 쓰는 일부만 번들하고 나머지는 Ink/Stitch 글꼴처럼 고정 커밋 + sha256으로 내려받는 방식을 제안한다.

재현:

```sh
gh api "repos/microsoft/fluentui-emoji/git/trees/1ffb34c752ecf5d402f04cfb4b392c77f57c54bc?recursive=1" > fluent.tree   # 다른 세트도 같은 방식
# 표본 120개를 빈 디렉터리에 받은 뒤:
python3 -I tools/emoji_svg_stats.py <dl>
```

## B. 변환 경로 설계 메모

```
SVG → (1) 도형 평탄화 → (2) 가림 처리 → (3) 실 색 매핑·병합 → (4) 작은 조각 제거 → (5) 땀 종류 자동 결정 → (6) 객체화·PES 검사
```

1. **파싱 — 재사용.** 보관 브랜치 `claude/svg-import-archive`의 `SvgTree.kt`·`SvgGeometry.kt`를 쓴다. viewBox, transform 누적, style 상속, path·basic shape를 다루고, `InkStitchFont`와 같은 `ViewBoxTransform`·`SvgPath.flatten`·`Affine2D` 위에 서 있다. Fluent Flat이 쓰는 기능은 `path`·`circle`·`ellipse`·`rect`, `fill`, `fill-opacity`(2%), `fill-rule`뿐이다. 그라디언트·mask는 미지원으로 두고, 들어 있으면 거부한다.
2. **가림 처리 — 새로 필요 (Ink/Stitch에 없음).** SVG는 뒤에 그린 도형이 앞 도형을 덮는다. 그대로 겹쳐 수놓으면 같은 자리에 땀이 쌓여 P7(0.5mm 칸당 바늘 15회)을 넘을 수 있다. 각 도형에서 그 위에 그려진 도형들의 합집합을 빼는 JTS `difference`로 처리한다(JTS 1.20은 이미 의존성에 있음). 기하 연산일 뿐 땀 알고리즘은 아니다.
3. **실 색 — 재사용.** SVG 색 → Brother 64색 중 `PecThreads.distance`(pyembroidery red-mean)로 가장 가까운 색. 같은 실로 바뀐 이웃 영역은 JTS `union`으로 합쳐 실 교체와 땀 겹침을 줄인다. 투명도는 흰 바탕과 합성한 색으로 매핑한다.
4. **작은 조각 제거 — 기준은 스윕으로.** 면적·최소 폭이 기준 미만인 조각은 버린다. 기준값은 **미확인**이다. 사진 크기 스윕(`PhotoSizeSweep`)처럼 표본 이모지 × 크기 × 땀 종류를 `SewoutCheck`로 돌려, 생성 실패와 게이트 차단이 0이 되는 최소값을 계산해 정한다.
5. **땀 종류 — 기준값 미확인, 스윕으로 확정.** 각 영역의 최대 내접원 지름(JTS `MaximumInscribedCircle`)을 폭으로 쓴다.
   - 폭 > 상한 → 채우기 (Ink/Stitch auto_fill 기본값, 기존 `InkAutoFill`)
   - 하한 ≤ 폭 ≤ 상한 → 새틴 (중심선은 기존 autotrace 중심선 포트, 새틴은 기존 `SatinColumn`·`StrokeToSatin` 기본값)
   - 폭 < 하한 → 러닝 (`InkRunning` 기본값)
   - 총괄이 제시한 0.9–6mm는 **출처 미확인**이다. Ink/Stitch에는 폭으로 땀 종류를 고르는 기능이 없어서 upstream 값이 없다. 그래서 새틴 폭 × 표본 × 크기를 `SewoutCheck`(P5·P7·P8)로 스윕해, 새틴이 통과하는 최소·최대 폭을 계산값으로 쓴다. 판정 규칙 자체는 이 단계(분류)가 유일한 새 로직이라 테스트로 고정한다.
6. **객체화와 검사 — 재사용.** 실 색별 `StitchObject`로 만들고, 실 순서는 SVG 그리기 순서를 따른다. `PesWriter` → `PecViewportCheck`·`SewoutCheck`(P1–P8) 경로는 사진·글자와 같다. 크기 UI는 사진의 "실제 자수 크기" 슬라이더를 재사용한다.

다른 방법으로, SVG를 래스터로 그려 사진 파이프라인(Wu 양자화 + potrace)에 태울 수도 있다. 검증된 코드라는 장점이 있지만, 지금 사진 파이프라인은 한 색 전용이고 벡터 원본의 정확도를 잃는다. 다색 확장이 필요한 시점에 다시 검토한다.

### 다음 단위 제안 (각 10–20분)

1. Fluent Flat 표본 20개를 테스트 픽스처로 (MIT 고지 포함) + 보관 파서 복원 + 가림 처리 → 색 영역 테스트.
2. 실 색 매핑·병합 + 폭 측정 → 영역별 분류표 출력.
3. 새틴 폭·최소 조각 스윕 → 기준값 확정 (`SewoutCheck` 출력 첨부).
4. 객체화 + PES 불변식 회귀 + 화면 연결.
