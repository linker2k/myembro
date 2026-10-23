// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

/** Format representability and model acceptance are deliberately different questions. */
internal fun compatibility(machine: SewoutMachine, o: SewoutOptions, stats: SewoutStatistics,
    file: PesFileInfo?, fileError: String?, complete: Boolean, findings: Findings): CompatibilityReport {
    val checks = mutableListOf<CompatibilityCheck>()
    fun add(rule: CompatibilityRule, status: CompatibilityStatus, message: String, source: String? = null) {
        checks += CompatibilityCheck(rule, status, message, source)
    }
    add(CompatibilityRule.FORMAT, when { fileError != null -> CompatibilityStatus.FAIL; file != null -> CompatibilityStatus.PASS; else -> CompatibilityStatus.UNKNOWN },
        when { fileError != null -> fileError; file != null -> "PES 서명·PEC 위치를 확인했고 엔진 리더로 읽었습니다. 실기 인식 검증은 별도입니다."; else -> "실제 내보낸 PES 바이트는 아직 검사하지 않았습니다." })
    val versions = machine.acceptedPesVersions
    val versionStatus = when {
        versions.value == null || file == null -> CompatibilityStatus.UNKNOWN
        file.version in versions.value -> CompatibilityStatus.PASS
        else -> CompatibilityStatus.FAIL
    }
    add(CompatibilityRule.PES_VERSION, versionStatus, if (versionStatus == CompatibilityStatus.UNKNOWN)
        "${machine.name}에서 PES ${file?.version ?: "버전"} 지원 여부는 미확인입니다." else "기종 PES 버전 목록과 비교했습니다.", versions.source)
    if (versionStatus == CompatibilityStatus.UNKNOWN) findings.add(FindingCode.PES_VERSION_UNCONFIRMED, Severity.INFO, checks.last().message)
    if (versionStatus == CompatibilityStatus.FAIL) findings.add(FindingCode.INVALID_PES, Severity.BLOCKER, "해당 기종의 확인된 PES 버전 목록에 포함되지 않습니다.")
    val b = stats.movementBounds
    val width = machine.maxWidthMm.value; val height = machine.maxHeightMm.value
    val machineFits = if (width == null || height == null || !complete) null else b == null || b.widthMm <= width && b.heightMm <= height
    val selectedFits = if (width == null || height == null) null else o.hoop.width/10.0 <= width && o.hoop.height/10.0 <= height
    val designStatus = when (machineFits) { true -> CompatibilityStatus.PASS; false -> CompatibilityStatus.FAIL; null -> CompatibilityStatus.UNKNOWN }
    add(CompatibilityRule.DESIGN_SIZE, designStatus, "단일 봉제 영역: ${width ?: "미확인"}×${height ?: "미확인"}mm.", machine.maxWidthMm.source)
    if (machineFits == false || selectedFits == false) findings.add(FindingCode.MACHINE_AREA_EXCEEDED, Severity.BLOCKER,
        "도안 또는 선택한 후프가 기종의 단일 봉제 영역을 초과합니다. 멀티포지션은 분할 파일별로 검사하세요.", Highlight(area = b))
    val knownHoop = o.hoop.width/10.0 to o.hoop.height/10.0 in machine.knownHoopsMm
    val outsideSelected = (findings.codes[FindingCode.HOOP_EXCEEDED] ?: 0) > 0
    add(CompatibilityRule.SELECTED_HOOP, when { selectedFits == false || outsideSelected -> CompatibilityStatus.FAIL; knownHoop -> CompatibilityStatus.PASS; else -> CompatibilityStatus.UNKNOWN },
        when { outsideSelected -> "도안 배치가 선택 후프의 영역을 벗어납니다."; selectedFits == false -> "선택 후프가 기종 단일 봉제 영역보다 큽니다."; knownHoop -> "확인된 후프 치수입니다."; else -> "이 후프의 기종 장착 호환은 미확인입니다." })
    if (!knownHoop && selectedFits != false) findings.add(FindingCode.HOOP_COMPATIBILITY_UNKNOWN, Severity.WARNING, checks.last().message)
    val limit = if (o.destination == Destination.INTERNAL_MEMORY) machine.internalMemoryStitches.takeIf { it.value != null } ?: machine.maxStitches else machine.maxStitches
    fun countCheck(rule: CompatibilityRule, value: Long?, limitValue: Long?, source: String?, exceeded: FindingCode, unknown: FindingCode, label: String) {
        val status = when { value == null || limitValue == null || !complete -> CompatibilityStatus.UNKNOWN; value > limitValue -> CompatibilityStatus.FAIL; else -> CompatibilityStatus.PASS }
        val message = if (status == CompatibilityStatus.UNKNOWN) "$label 제한 또는 실제 값이 미확인입니다." else "$label: $value / $limitValue."
        add(rule, status, message, source)
        if (status == CompatibilityStatus.FAIL) findings.add(exceeded, Severity.BLOCKER, message, measured = value?.toDouble(), threshold = limitValue?.toDouble())
        if (status == CompatibilityStatus.UNKNOWN) findings.add(unknown, Severity.INFO, message)
    }
    countCheck(CompatibilityRule.STITCH_COUNT, stats.stitchCount.toLong(), limit.value?.toLong(), limit.source,
        FindingCode.STITCH_LIMIT_EXCEEDED, FindingCode.STITCH_LIMIT_UNCONFIRMED, "도안 땀수")
    // Color count is the sequence of color stages, not just unique RGBs.
    countCheck(CompatibilityRule.COLOR_COUNT, stats.colorStageCount.toLong(), machine.maxColors.value?.toLong(), machine.maxColors.source,
        FindingCode.COLOR_LIMIT_EXCEEDED, FindingCode.COLOR_LIMIT_UNCONFIRMED, "색 단계 수")
    countCheck(CompatibilityRule.FILE_SIZE, file?.byteCount?.toLong(), machine.maxFileBytes.value, machine.maxFileBytes.source,
        FindingCode.FILE_SIZE_EXCEEDED, FindingCode.FILE_SIZE_UNCONFIRMED, "파일당 바이트 수")
    val thumbnailStatus = when {
        machine.pecThumbnailRequired.value == false -> CompatibilityStatus.PASS
        machine.pecThumbnailRequired.value == null || file?.pecThumbnailsPresent == null -> CompatibilityStatus.UNKNOWN
        file.pecThumbnailsPresent -> CompatibilityStatus.PASS
        else -> CompatibilityStatus.FAIL
    }
    add(CompatibilityRule.THUMBNAIL, thumbnailStatus, when (thumbnailStatus) {
        CompatibilityStatus.UNKNOWN -> "PEC 썸네일의 기종 필수 조건/실기 수용 여부는 미확인입니다."
        CompatibilityStatus.FAIL -> "기종이 요구하는 PEC 썸네일이 없습니다."
        CompatibilityStatus.PASS -> "입력한 기종의 PEC 썸네일 조건을 만족합니다."
    }, machine.pecThumbnailRequired.source)
    if (thumbnailStatus == CompatibilityStatus.UNKNOWN) findings.add(FindingCode.THUMBNAIL_REQUIREMENT_UNCONFIRMED, Severity.INFO, checks.last().message)
    if (thumbnailStatus == CompatibilityStatus.FAIL) findings.add(FindingCode.MISSING_THUMBNAIL, Severity.BLOCKER, checks.last().message)
    val acceptance = when {
        checks.any { it.status == CompatibilityStatus.FAIL } || fileError != null -> Acceptance.REJECTED
        !complete || checks.any { it.status == CompatibilityStatus.UNKNOWN } -> Acceptance.UNVERIFIED
        else -> Acceptance.WITHIN_CONFIRMED_LIMITS
    }
    return CompatibilityReport(acceptance, checks)
}
