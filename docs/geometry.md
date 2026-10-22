# 기하·디지타이징 기반 (Phase 3a)

확인일 2026-10-22. 앱 연결 없이 엔진 API만 제공합니다. 공개 API는
`engine.geometry`의 `Point`, `Polyline`, `Affine2D`, `SvgPath`, `PolygonRegion`, `FillRule`과
`engine.digitizing`의 `RunningStitch`, `TatamiFill`입니다. 기존 편집기·레터링 API는 변경하지 않습니다.

## 폴리곤 연산: JTS 채택

`org.locationtech.jts:jts-core:1.20.0`을 고정 사용합니다. 교차점 노딩, 자기 교차/중첩 경로의 면 추출,
구멍, 교집합·합집합·차집합·배타합집합, 오프셋을 모두 자체 구현하면 부동소수점 퇴화 사례에
대한 부담이 큽니다. Phase 3a에서 안정된 폴리곤 기반을 먼저 확보하기 위해 JTS를 선택했습니다.
JTS 타입은 공개 API 밖으로 노출하지 않으며 지역 연산은 새 결과를 반환합니다.

[프로젝트 릴리스](https://github.com/locationtech/jts/releases/tag/1.20.0)와
[해당 버전 라이선스](https://github.com/locationtech/jts/blob/1.20.0/LICENSES.md)를 확인했습니다.
[공식 라이선스 FAQ](https://github.com/locationtech/jts/blob/1.20.0/FAQ-LICENSING.md)는
EPL-2.0과 EDL-1.0 중 사용자가 선택할 수 있다고 명시합니다. 이 프로젝트는 BSD형 **EDL-1.0**을
선택하여 GPL-3.0 프로젝트의 의존성으로 사용합니다. 원문·저작권 고지는 엔진 리소스
`licenses/jts/`에 패키징하고 [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md)에 기록했습니다.

`PolygonRegion.fromPaths`는 입력 선을 노딩한 뒤 `Polygonizer`로 면을 추출하고, 각 면의 내부점에
대해 원래 경로 전체의 winding number를 계산합니다. NON_ZERO는 0이 아닌 면, EVEN_ODD는
홀수 면을 선택해 합칩니다. 구멍 방향, 같은 방향 중첩, 반대 방향 취소, 겹친 경로, 자기 교차를
처리합니다. [JTS Polygonizer 문서](https://locationtech.github.io/jts/javadoc/org/locationtech/jts/operation/polygonize/Polygonizer.html)가
요구하는 교차점 노딩을 먼저 수행합니다. 점/선으로만 겹치는 결과는 면적이 없으므로 제거합니다.

## 좌표·SVG 경로

- 모든 기하·스티치 길이는 **0.1mm**, +y는 아래쪽입니다. SVG 입력의 user unit, px, viewBox,
  mm 등의 단위/뷰포트 변환은 호출자가 `Affine2D`로 명시해야 합니다. 자동 px→mm 추정은 하지 않습니다.
- `SvgPath.flatten(d, transform, tolerance)`는 M/L/H/V/C/S/Q/T/A/Z, 절대·상대 좌표, 반복 인자,
  지수 표기, 구분자 없는 부호/소수점/호 플래그를 처리합니다. Z와 열린 경로를 구분합니다.
  `polygons` 또는 `fromPaths`에서 채울 때 열린 서브패스도 끝과 시작을 연결합니다.
- 기본 허용 오차는 **변환 후 0.5단위=0.05mm**입니다. 베지에는 변환된 제어점을 사용한
  De Casteljau 적응 분할, 호에는 반지름 보정과 endpoint→center 변환 및 chord 오차 상한을 씁니다.
  비균일 축척·skew가 있으면 행렬 norm으로 호 분할 오차를 보수적으로 제한합니다.
- `Affine2D.parse`는 matrix/translate/scale/rotate(중심 포함)/skewX/skewY를 지원합니다.
  `parent * child`는 child를 먼저 적용합니다. SVG의 `translate(10 20) scale(2)`는 `(1,1)`을
  `(12,22)`로 바꿉니다. 계층 변환은 같은 곱셈 규칙으로 조합하세요.
- 이것은 path-data와 transform-attribute 파서입니다. XML/DTD/외부 리소스/CSS/클리핑/마스크/
  `<use>`/그라디언트/스트로크 외곽선 생성은 포함하지 않습니다. 외부 코드를 실행하지 않습니다.

구현 근거는 [W3C SVG 경로 문법](https://www.w3.org/TR/SVG/paths.html),
[호 파라미터 변환](https://www.w3.org/TR/SVG/implnote.html#ArcImplementationNotes),
[변환 행렬](https://www.w3.org/TR/SVG11/coords.html#TransformAttribute),
[fill-rule](https://www.w3.org/TR/SVG/painting.html#FillRuleProperty)입니다.
기존 SVG 폰트 전용 파서와 별도 패키지여서 레터링 동작을 바꾸지 않습니다.

```kotlin
val matrix = Affine2D.parse("translate(-100 -100) scale(2)")
val lines = SvgPath.flatten("M0 0 C0 100 100 100 100 0 Z", matrix)
val area = PolygonRegion.fromPaths(lines, FillRule.EVEN_ODD)
val inset = area.offset(-10.0) // 1mm 안쪽, 구멍은 커짐; 사라지는 작은 부분은 빈 결과
val union = area.union(otherArea)
val cut = area.difference(otherArea)
val overlap = area.intersection(otherArea)
```

## 러닝 스티치

`RunningStitch.sample`은 폴리라인을 호 길이 기준으로 나누되, 시작·끝·기본 45도 이상 꺾이는
코너를 앵커로 고정합니다. 각 앵커 사이를 균등 분할해 최대 **25단위=2.5mm** 길이로 만듭니다.
완만한 곡선을 구성하는 짧은 평탄화 선분마다 땀을 넣지 않습니다. `cornerAngleDegrees = 0.0`이면
모든 꼭짓점을 보존하며, 가장자리 언더레이에 이 설정을 사용합니다. 열린 경로는 닫지 않습니다.

`RunningStitch.generate(lines, RunningSettings(), thread)`는 분리된 경로 사이에 TRIM/JUMP,
경로 첫 점에 STITCH, 끝에 END를 기록합니다. 정수 좌표 변환은 최종 출력 단계에서만 수행하며
반올림 후 같은 위치로 이어지는 중복 바늘땀은 제거합니다. 빈 경로는 END만 반환합니다.

## 단순 타타미·언더레이

`TatamiFill.generate(area, TatamiSettings(), thread)`의 기본값은 다음과 같습니다.

| 설정 | 기본값 |
| --- | --- |
| 채움 방향 | 0도(수평), +각도는 화면에서 시계방향 |
| 행 간격 | 4단위=0.4mm |
| 최대 땀 길이 | 40단위=4mm |
| 4행 반복 오프셋 | 0, 1/4, 1/2, 3/4 땀 길이 |
| 가장자리 러닝 언더레이 | 사용, 경계에서 1mm 안쪽, 최대 2.5mm 땀 |

영역을 채움 방향의 역회전 좌표계로 옮겨 스캔라인과 교차시킵니다. 구멍과 분리 영역은 별도
구간으로 나뉩니다. 각 행은 반대 방향으로 진행하며, 전역 x격자의 오프셋으로 정렬된 바늘 구멍이
일렬로 몰리는 것을 줄입니다. 구간 양 끝은 항상 남기므로 끝부분 땀은 짧아질 수 있습니다.
아주 얇은 도형에도 최소 중앙 한 행을 시도합니다. 오목한 부분에서 행이 없거나 구멍으로 잘리면
일부 세부 영역에 땀이 생기지 않을 수 있습니다.

이전 구간 끝과 다음 시작 사이가 영역에 완전히 포함되고 4mm 이하일 때만 연결 땀을 넣습니다.
그 외에는 TRIM/JUMP로 이동합니다. 언더레이는 음수 buffer로 만든 내부 경계의 외곽·구멍을
먼저 따라가며, 좁아서 inset이 사라지면 생략합니다. 면 분할은 스캔라인 교차 구간 방식이며,
영역 인접 그래프·Euler 순회·최적 언더패스 같은 고급 경로 계획은 후속 범위입니다.

알고리즘은 일반적인 호 길이 재표본화와 스캔라인 교차를 직접 구현했습니다. Ink/Stitch 코드를
포팅하거나 외부 알고리즘 코드를 복사하지 않았으며 별도 Ink/Stitch 저작권을 추가하지 않습니다.

```kotlin
val running = RunningStitch.generate(lines, RunningSettings(stitchLength = 25.0))
val fill = TatamiFill.generate(area, TatamiSettings(angleDegrees = 35.0))
val pes = PesWriter.writeV6(fill)
```

최종 반올림 때문에 바늘 위치는 최대 √0.5≈0.707단위(0.071mm), 두 점 사이 길이는 최대
√2단위 늘어날 수 있습니다. 코너·구간 끝의 짧은 땀, 자동 매듭/타이인·타이오프, 당김 보정,
원단별 밀도 조절, 고급 세부 영역 연결은 아직 제공하지 않습니다. 실기 봉제 검증은 남아 있습니다.
생성 결과에는 기존 기종 Preflight를 별도로 적용하세요.

## 입력 제한과 검증

NaN/무한대, 잘못된 명령·호 플래그, 0 이하 간격/땀 길이는 예외로 거부합니다. 점 좌표는 절댓값
10⁹단위 이하, 평탄화/폴리곤/러닝 입력은 200,000점, 베지에 분할 깊이는 24, 채움은 최대
100,000행, 생성 결과는 최대 1,000,000명령으로 제한합니다. 기종 허용량과는 별개입니다.

```bash
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test
/path/to/pyembroidery-1.5.1-venv/bin/python tools/check_digitizing.py
/path/to/pyembroidery-1.5.1-venv/bin/python tools/check_writer.py
```

단위 테스트는 문법·행렬 순서·곡선의 조밀한 독립 표본 대비 오차·호 퇴화·fill-rule·중첩·구멍·
자기 교차·폴리곤 연산·코너 보존·행 간격·오프셋·각도·언더레이·잘못된 입력을 검사합니다.
정사각형·도넛·오목 영역·분리 영역·베지에·호 6종을 러닝/수평 타타미/37도 타타미로 만들고
v1/v6로 출력한 **36개 PES**를 자체 리더와 pyembroidery 1.5.1로 읽습니다. Python은 명령·실·
헤더·봉제 객체·썸네일을 검사하고 별도 홀짝 포함/선분 교차 코드로 채움 구멍 침범·땀 길이·
분리 경로의 TRIM도 검사합니다. 이 입력에서 발견한 PEC 썸네일의 픽셀 경계 차이는 원본
pyembroidery와 부동소수점 연산 순서를 맞춰 수정했습니다. 기존 writer 교차 검증도 다시 통과해야 합니다.

최종 검증: 엔진 테스트 **168개 통과**, 생성 PES **36개**와 기존 writer **59개**, 멀티포지션
**4개**를 pyembroidery 1.5.1로 교차 검증했습니다. `:app:assembleDebug`가 성공했으며
APK의 `licenses/jts/`에 EDL 원문·고지 3개가 포함됨을 확인했습니다. 앱 동선 연결은 하지 않았습니다.
