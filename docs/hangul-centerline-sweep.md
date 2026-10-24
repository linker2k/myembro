# 한글 새틴 공개 글꼴 스윕

| 글꼴 Regular400 | 건수 | 게이트 PASS | 생성 거부(10/15/20mm) | 엔진 시간(초) |
|---|---:|---:|---|---:|
| gowun-dodum | 90 | 90 | 0 / 0 / 0 | 2.364 |
| nanum-gothic | 90 | 90 | 0 / 0 / 0 | 1.340 |
| nanum-myeongjo | 90 | 72 | 8 / 6 / 4 | 1.241 |
| gothic-a1 | 90 | 90 | 0 / 0 / 0 | 0.969 |
| orbit | 90 | 90 | 0 / 0 / 0 | 0.901 |

합계450건: 생성432 / 생성 거부18 / 외부 게이트 BLOCK 0.

실패 사유:
- java.lang.IllegalStateException: No satin section for fitted path: 18건

거부18건 모두 최소 경로 <1 SVG px (0.317607–0.952822px). convert-to-satin 원본의 endpoint>=1px 조건에서 섹션 없음; 기둥 생성 불가 시 전체 글자를 거부하며 경로를 버려 성공으로 바꾸지 않음.

검사 범위: 출력된432건은 외부 게이트 PASS, 0길이 바늘/JUMP=0, 원점 min(0,0)·헤더/후프 검사 PASS. 거부18건은 PES가 없어 게이트·PES 0길이/원점 검사는 미실행.

최대 0.5mm 셀 바늘 수 10; 엔진 루프 9.957381596초; 폭 실측 9.743949678959325초; 래스터 준비 0.48675092007033527초. 표 시간은 글자별 엔진 호출 합(첫 JVM/JIT·JSON/파일 쓰기 포함, Gradle 빌드·폭 실측·래스터·외부 게이트 실행 제외). 게이트 wall time은 별도 미계측.

폰트·OFL·SHA·폭 측정 출처: fonts.json / width-provenance.json. 공개 문자30자: 뻘뿔뻤뼈뼛뺏빽뽐뽕뾰쀼쐐쐤쒔쫄쫓쭘쭝쯤쯧짧찮많닭값삶앉읽핥읊

폭은 기존 model GlyphStrokeWidths의 글자별15mm 중앙값×height/15, 비례 샘플링(15mm 기준0.1mm). 동일 글자 실제 ink 높이 정규화. 이 계산은 앱 자동 폭 규칙·폰트 권장 높이/실기 안전 범위가 아님. 기존 원본 기본값을 변경하지 않음.

원본 fit→convert-to-satin→SatinColumn→S1→기본 PES writer 경로. 앱 연결 없음. 사용자 봉제 승인은 별도.

## 재현과 범위

엔진 `95ce672` 기반. 계산 도구만 추가했으며 새틴/후처리 알고리즘·게이트를
변경하지 않았다. 모든 입력·폰트·출력은 `/tmp/myembro-port-out/letter/hangul-sweep-v1/`에
있다. 앱에 연결하지 않았고 기존 KRSAT1도 바꾸지 않았다. 개인 파일을 읽지 않았다.
외부 게이트 v7, threshold SHA256
`18bf3e7cc70e872680368efdb3319e8d1794587337eefdb4e9c7cc97a116b95d`.

```sh
# 새 디렉터리를 사용한다. 기존 PES 이름은 덮어쓰지 않는다.
python3 tools/hangul_satin/prepare-sweep.py /tmp/myembro-port-out/letter/hangul-sweep-NEW
# 기존 model GlyphStrokeWidths 컴파일 결과를 /tmp로 복사해 고정·실측한다.
python3 tools/hangul_satin/measure-sweep-widths.py /tmp/myembro-port-out/letter/hangul-sweep-NEW --model-root /home/gosh2/workspace/hoonyjung/myembro-model --model-classpath /tmp/myembro-ttf-fill-sweep-reduced/classpath.txt
python3 tools/hangul_satin/prepare-sweep.py /tmp/myembro-port-out/letter/hangul-sweep-NEW --prepare
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:hangulSatinSweep -PhangulSweepRoot=/tmp/myembro-port-out/letter/hangul-sweep-NEW
/tmp/myembro-quality-gate/quality-gate check --text --style satin --json /tmp/myembro-port-out/letter/hangul-sweep-NEW/gate.json /tmp/myembro-port-out/letter/hangul-sweep-NEW/outputs
python3 tools/hangul_satin/summarize-sweep.py /tmp/myembro-port-out/letter/hangul-sweep-NEW
```

OFL 텍스트를 공개 Google Fonts 고정 커밋에서 받아 확인했으며 원격 코드는
실행하지 않았다. regular400 다섯 글꼴을 가는 획 후보로 택했으며 모든 한글 글꼴의
최소 획 두께나 사용 가능 등급을 판정한 스윕은 아니다. 래스터 폰트 크기128px,
글자별 ink height를 물리 높이에 정규화했다. 해상도별 수렴 검증은 범위 밖이다.
나눔명조 실패는 뿔·뻤·뼛·뽐·뽕·뾰·쀼·값에서 발생했다.
단일 경로 하나라도 변환이 안 되면 전체 글자를 실패 처리하므로 일부 생성된
새틴을 완성된 글자로 내보내지 않는다. 짧은 serif 경로를 버리거나 임의로 러닝으로
대체하는 변경은 하지 않았다. 실패 상세는 `summary.json`과 글자별 `.log`에 있다.

검증: `./gradlew check` BUILD SUCCESSFUL in1m1s(엔진650·앱 Debug/Release 각205,
실패0·Lint 통과). `/tmp/myembro-port-out/letter/hangul-sweep-v1/check.log`.
`SWEEP_ASSERT 450 inputs / 432 gate PASS / 18 generation rejected / 432 zero-motion=0 / 432 origin=(0,0)`.
