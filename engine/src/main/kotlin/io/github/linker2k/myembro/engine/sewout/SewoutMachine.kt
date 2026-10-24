// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

/** Facts are intentionally independent of Android UI profiles. Unknown fields remain null. */
data class SewoutMachine(
    val id: String, val name: String,
    val maxWidthMm: Specification<Double>, val maxHeightMm: Specification<Double>,
    val maxStitches: Specification<Int> = Specification(),
    val internalMemoryStitches: Specification<Int> = Specification(),
    val maxColors: Specification<Int> = Specification(),
    val maxFileBytes: Specification<Long> = Specification(),
    val acceptedPesVersions: Specification<Set<String>> = Specification(),
    val maximumEmbroiderySpm: Specification<Int> = Specification(),
    val maximumStitchMm: Specification<Double> = Specification(),
    val automaticJumpTrim: Specification<Boolean> = Specification(),
    val manualJumpCleanup: Specification<Boolean> = Specification(),
    val colorEndCutter: Specification<Boolean> = Specification(),
    val pecThumbnailRequired: Specification<Boolean> = Specification(),
    val fullBobbinMeters: Specification<Double> = Specification(),
    val knownHoopsMm: Set<Pair<Double, Double>> = setOf(100.0 to 100.0, 60.0 to 20.0),
) {
    init {
        require(listOfNotNull(maxWidthMm.value, maxHeightMm.value, maximumStitchMm.value, fullBobbinMeters.value).all { it.isFinite() && it > 0 })
        require(listOfNotNull(maxStitches.value, internalMemoryStitches.value, maxColors.value, maximumEmbroiderySpm.value).all { it > 0 })
        require(maxFileBytes.value == null || maxFileBytes.value > 0)
    }
}

object SewoutMachines {
    const val SE400_MANUAL = "https://download.brother.com/welcome/doch000184/885v31_v32_v33_om04en.pdf"
    const val SE600_MANUAL = "https://download.brother.com/welcome/doch101011/888m20_om02en.pdf"
    const val SE700_MANUAL = "https://download.brother.com/welcome/doch102582/888p10_om01en.pdf"
    const val SE400_LIMIT = "https://support.brother.ca/app/answers/detail/a_id/133968/~/design-does-not-appear-on-lcd-after-being-transferred"
    const val SE400_JUMP_TRIM_OBSERVATION = "사용자 SE400 실기 확인 (2026-10-24): 이동실 자동 절단 없음, 가위로 수동 정리"
    val SE400 = SewoutMachine("se400", "Brother SE400",
        Specification(100.0, "$SE400_MANUAL p.127"), Specification(100.0, "$SE400_MANUAL p.127"),
        maxStitches = Specification(30_000, SE400_LIMIT),
        automaticJumpTrim = Specification(false, SE400_JUMP_TRIM_OBSERVATION),
        manualJumpCleanup = Specification(true, SE400_JUMP_TRIM_OBSERVATION),
        colorEndCutter = Specification(true, "$SE400_MANUAL p.140 (when enabled)"))
    val SE600 = SewoutMachine("se600", "Brother SE600",
        Specification(100.0, "$SE600_MANUAL p.60"), Specification(100.0, "$SE600_MANUAL p.60"),
        manualJumpCleanup = Specification(true, "$SE600_MANUAL p.66"),
        colorEndCutter = Specification(true, "$SE600_MANUAL p.66 (when enabled)"))
    val SE700 = SewoutMachine("se700", "Brother SE700",
        Specification(100.0, "$SE700_MANUAL p.96"), Specification(100.0, "$SE700_MANUAL p.96"),
        internalMemoryStitches = Specification(100_000, "$SE700_MANUAL p.96 (internal memory only)"),
        maximumEmbroiderySpm = Specification(400, "$SE700_MANUAL p.96"),
        manualJumpCleanup = Specification(true, "$SE700_MANUAL p.71"),
        colorEndCutter = Specification(true, "$SE700_MANUAL p.70 (when enabled)"))
    val all: List<SewoutMachine> = listOf(SE400, SE600, SE700)
}
