# 멀티포지션 프레임 분할 (Phase 3a)

좌표 단위는 0.1mm, +y는 아래, 원점은 프로젝트 중심입니다. 각 위치의 실제 봉제 영역은
최대 100×100mm이며, 프레임 전체 크기가 단일 봉제 가능 크기를 늘리지는 않습니다.

## 통합 담당: SA434 위치 계약 연결

`engine.edit.MultiPositionSplitter`는 `SewingPosition(id, name, originX, originY, width, height)`
목록을 받습니다. 위치의 `originX/Y`는 프로젝트 좌표계에서 해당 100×100 봉제 창의 중심입니다.
작업 시점에 `HoopSpec.positions`가 아직 이 브랜치에 없으므로 공통 타입을 임의로 변경하지
않았습니다. **확인된 SA434 위치 목록을 이 타입으로 변환해서 전달**해 주세요. 하드웨어 위치
간격을 추정한 SA434 기본값은 제공하지 않습니다. 테스트의 중심 -35/0/+35mm는 분할 검증용
합성 입력이며 SA434 사양이 아닙니다. 앱·후프 UI·기종 설정은 수정하지 않았습니다.

```kotlin
val positions: List<SewingPosition> = /* 확인된 HoopSpec.positions에서 변환 */
val result = MultiPositionSplitter.split(project, positions) // 또는 split(pattern, positions)
val errors: List<UnassignedStitch> = result.errors
val files = result.writePes("design", PesWriteSettings(PesVersion.V6))
// design_position1.pes, design_position2.pes, ... (비어 있는 위치는 생략)
// files[i].instructions, files[i].metadata, files[i].part.stitchCount, files[i].part.colors
```

Brother 파일명 권장 문자가 영문·숫자·하이픈·밑줄이므로 위치 접미사는 `position1` 등 ASCII로
씁니다. 표시용 위치 안내는 한국어입니다. 기본 이름 자체는 자동 변환하지 않으므로 전송 전
`Preflight.check`로 확인하세요. 파일 번호는 입력 위치 목록의 1부터 시작하는 번호이며, 빈 위치가
빠져도 번호를 다시 매기지 않습니다. `writePes`는 어느 위치에도 들어가지 않는 땀이 있으면
예외를 내어 누락된 도안의 부분 내보내기를 막습니다. 오류 목록은 원본 명령 인덱스와 좌표를
보존하며, `sourceStitchCount == parts.sumOf { it.stitchCount } + errors.size`입니다.

각 파일을 내보내기 전 기존 SE400 `Preflight`도 적용하세요. 분할 자체는 30,000땀 제한을
자동으로 해결하지 않습니다. 실제 SA434 설치 위치와 도안 정합은 실기 확인이 필요합니다.

## 배정과 순서

- 첫 END까지만 처리합니다. STITCH를 정확히 한 위치에 배정하며 새 바늘땀이나 겹침 봉제용
  중복 땀을 추가하지 않습니다. 창의 경계는 포함합니다.
- 동적 계획법으로 가능한 위치 중 전환 횟수가 최소인 배정을 선택합니다. 겹침에 있는 앞부분을
  다음 위치에 배정하면 연속 구간 전체를 한 위치에서 봉제할 수 있는 경우도 처리합니다.
  동률은 같은 위치 유지, 입력 위치 순서를 사용합니다. 시간 O(땀수×위치수²), 메모리 O(땀수×위치수).
- 위치별로 원본 땀 순서를 유지합니다. 위치가 바뀌거나 원본에 JUMP/TRIM이 있거나 오류 땀을
  건너뛴 경우 다음 구간은 TRIM/JUMP로 시작하고 각 파일 끝에도 TRIM을 기록합니다.
  중간 위치로 갔다 돌아온 구간을 같은 파일에서 이어 박지 않습니다.
- 실 교체는 해당 위치의 실제 봉제 실이 달라질 때 기록하고, STOP은 직전 땀의 위치에 남깁니다.
  `colors`는 색상 블록 순서의 실 목록이며 같은 색으로 돌아오면 중복 항목이 있을 수 있습니다.
- 위치별로 묶으면 서로 다른 위치 사이의 전체 겹침 순서는 달라질 수 있습니다. 결과의 한국어
  경고를 표시해야 합니다. 임의 도안의 레이어 의존성까지 해결하는 스케줄러는 아닙니다.
- 분할은 바늘 위치 배정이며 선분 클리핑이 아닙니다. 피할 수 없는 위치 경계에서 연속 선분을
  자를 수 있고, 추가 연결 땀·매듭·재봉 여유를 만들지 않습니다. 프레임 재고정 정밀도를 보장하지 않습니다.

각 부분의 `sourceStitchIndices`는 STITCH 순서에 대응합니다. `position.toProject(localStitch)`로
원본 좌표를 정확히 복원할 수 있습니다. 파일의 좌표는 해당 위치 중심 기준입니다. END/점프/트림은
통계의 땀수에 포함하지 않습니다. 입력 제한은 200,000 STITCH, 최대 16개 위치입니다.

## 메타데이터와 검증

반환 `pattern.metadata`에는 `multiposition.index/id/name/originX/originY/width/height`가 있습니다.
PES v6에는 표준 `name`/`comments` 필드로 위치 번호·이름·원점을 추가 저장합니다. 임의 키는
PES에 저장되지 않으며 v1은 PEC의 짧은 라벨만 저장하므로 결과 객체의 안내 정보도 함께 보관하세요.

```bash
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test
/path/to/pyembroidery-1.5.1-venv/bin/python tools/check_writer.py engine/build/multiposition-check
```

테스트는 100×170 합성 도안의 각 부분이 100×100 이내인지, 땀 총합·원본 인덱스·좌표 왕복,
겹침 선배정, 점프/트림/실 교체/STOP, 영역 밖 오류와 출력 차단, 빈 위치와 잘못된 입력을 검사합니다.
각 위치를 v1/v6로 출력해 자체 리더와 pyembroidery 1.5.1로 검증합니다.
