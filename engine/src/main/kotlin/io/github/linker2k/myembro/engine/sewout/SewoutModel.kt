// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.EmbThread

/** Null means unconfirmed, never unlimited or unsupported. Sources include the printed page. */
data class Specification<T>(val value: T? = null, val source: String? = null) {
    val confirmed: Boolean get() = value != null
    val statusLabel: String get() = if (confirmed) "확인" else "미확인"
}

enum class Severity { BLOCKER, WARNING, INFO }
enum class FindingCode {
    HOOP_EXCEEDED, MACHINE_AREA_EXCEEDED, HOOP_COMPATIBILITY_UNKNOWN,
    LONG_STITCH, SHORT_STITCH, MACHINE_STITCH_LENGTH_EXCEEDED, PEC_DELTA_EXCEEDED,
    DENSITY_HOTSPOT, REPEATED_PENETRATION, OVERLAP_ACCUMULATION, MISSING_LOCK_STITCH,
    EMPTY_DESIGN, EMPTY_COLOR_STAGE, READER_WARNING, INVALID_PES, MISSING_THUMBNAIL, ANALYSIS_LIMIT,
    STITCH_LIMIT_EXCEEDED, COLOR_LIMIT_EXCEEDED, FILE_SIZE_EXCEEDED,
    PES_VERSION_UNCONFIRMED, COLOR_LIMIT_UNCONFIRMED, STITCH_LIMIT_UNCONFIRMED,
    FILE_SIZE_UNCONFIRMED, THUMBNAIL_REQUIREMENT_UNCONFIRMED, MACHINE_SPEED_UNCONFIRMED,
    JUMP_TRIM_UNCONFIRMED, MACHINE_STITCH_LENGTH_UNCONFIRMED, ESTIMATE_ASSUMPTIONS, SPEED_ABOVE_MAXIMUM,
    BOBBIN_CAPACITY_UNCONFIRMED, BOBBIN_INSUFFICIENT,
}

/** Absolute input coordinates, in 0.1 mm (+y down). No display recentering is applied to highlights. */
data class NeedlePoint(val x: Double, val y: Double)
data class CheckArea(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val widthMm: Double get() = (maxX - minX) / 10
    val heightMm: Double get() = (maxY - minY) / 10
    fun contains(p: NeedlePoint): Boolean = p.x >= minX && p.x <= maxX && p.y >= minY && p.y <= maxY
}
/** Command indices are zero based and inclusive; null denotes a global finding. */
data class Highlight(val commandIndex: Int? = null, val endCommandIndex: Int? = commandIndex,
    val from: NeedlePoint? = null, val to: NeedlePoint? = from, val area: CheckArea? = null)
data class Finding(val code: FindingCode, val severity: Severity, val message: String,
    val highlight: Highlight? = null, val measured: Double? = null, val threshold: Double? = null)

enum class Placement { CENTER_DESIGN, HOOP_COORDINATES }
enum class Destination { TRANSFER, INTERNAL_MEMORY }
enum class SwitchSetting { ENABLED, DISABLED, UNKNOWN }
enum class CutRequirement { REQUIRED, POSSIBLE, AUTOMATIC }
enum class TaskKind { JUMP_THREAD_CUT, TRIM, COLOR_CHANGE, PROGRAM_STOP, FINISH }

/** A task is a planning instruction, not an inserted machine command or an automatic pause. */
data class OperatorTask(
    val ordinal: Int, val kind: TaskKind, val commandIndex: Int, val endCommandIndex: Int,
    val stageIndex: Int, val from: NeedlePoint, val to: NeedlePoint,
    val cutRequirement: CutRequirement? = null,
    val machineStops: Boolean, val operatorMustPause: Boolean,
    val atEstimatedSeconds: Double, val durationSeconds: Double, val message: String,
)

data class DensityCell(val column: Long, val row: Long, val area: CheckArea,
    val penetrations: Int, val segmentPasses: Int, val sewnLengthMm: Double,
    val stageIndices: Set<Int>, val firstCommandIndex: Int)
data class SewoutStatistics(val stitchCount: Int, val jumpCount: Int, val trimCount: Int,
    val colorChangeCount: Int, val stopCount: Int, val colorStageCount: Int,
    val distinctThreadCount: Int, val bounds: CheckArea?, val movementBounds: CheckArea?,
    val sewnLengthMm: Double, val jumpLengthMm: Double,
    val requiredManualCuts: Int, val possibleManualCuts: Int,
    val densityHotspotCount: Int, val overlapCellCount: Int)

enum class SpeedBasis { USER_SETTING, PROFILE_MAXIMUM_DERIVED, ASSUMED }
data class SpeedEstimate(val effectiveSpm: Double, val basis: SpeedBasis,
    val machineMaximumSpm: Specification<Int>)
data class TimeEstimate(val needleSeconds: Double, val movementSeconds: Double,
    val automaticCutSeconds: Double, val operatorSeconds: Double, val conditionalOperatorSeconds: Double) {
    val totalSeconds: Double get() = needleSeconds + movementSeconds + automaticCutSeconds + operatorSeconds
    /** Excludes conditional cuts only. This is not a statistical confidence interval. */
    val withoutConditionalCutsSeconds: Double get() = totalSeconds - conditionalOperatorSeconds
}
enum class Sufficiency { ENOUGH, INSUFFICIENT, UNKNOWN }
data class ThreadEstimate(val upperMeters: Double, val bobbinMeters: Double,
    val availableBobbinMeters: Double?, val oneBobbin: Sufficiency)
data class ColorStage(val index: Int, val thread: EmbThread, val startCommandIndex: Int,
    val endCommandIndex: Int, val stitchCount: Int, val jumpCount: Int, val trimCount: Int,
    val sewnLengthMm: Double, val uncutJumpLengthMm: Double,
    val time: TimeEstimate, val threadUsage: ThreadEstimate)

enum class CompatibilityStatus { PASS, FAIL, UNKNOWN }
enum class CompatibilityRule { FORMAT, PES_VERSION, DESIGN_SIZE, SELECTED_HOOP, STITCH_COUNT, COLOR_COUNT, FILE_SIZE, THUMBNAIL }
data class CompatibilityCheck(val rule: CompatibilityRule, val status: CompatibilityStatus,
    val message: String, val source: String? = null)
enum class Acceptance { REJECTED, UNVERIFIED, WITHIN_CONFIRMED_LIMITS }
data class CompatibilityReport(val acceptance: Acceptance, val checks: List<CompatibilityCheck>)
data class PesFileInfo(val version: String, val byteCount: Int, val pecThumbnailsPresent: Boolean? = null)

/** Heuristics and operator timings are configurable assumptions, not manufacturer limits. */
data class SewoutOptions(
    val hoop: HoopSpec = HoopSpec.H100,
    val placement: Placement = Placement.CENTER_DESIGN,
    val destination: Destination = Destination.TRANSFER,
    val longStitchMm: Double = 12.1, val shortStitchMm: Double = 0.3,
    /** Inclusive blocking thresholds: defaults mean >15 punctures per cell and >3 at one point. */
    val gridMm: Double = 0.5, val hotspotPenetrations: Int = 16,
    val repeatedPointThreshold: Int = 4, val overlapPassThreshold: Int = 6,
    val plannedSpm: Double? = null, val assumedSpm: Double = 400.0,
    val profileSpeedFraction: Double = 0.8,
    val jumpMmPerSecond: Double = 50.0, val jumpOverheadSeconds: Double = 0.2,
    val manualCutSeconds: Double = 8.0, val automaticCutSeconds: Double = 1.0,
    val colorChangeSeconds: Double = 30.0, val programStopSeconds: Double = 10.0,
    val upperThreadFactor: Double = 1.25, val bobbinThreadFactor: Double = 0.5,
    val upperTailMmPerCut: Double = 50.0, val bobbinTailMmPerCut: Double = 20.0,
    val availableBobbinMeters: Double? = null,
    val jumpTrimSetting: SwitchSetting = SwitchSetting.UNKNOWN,
    val colorCutSetting: SwitchSetting = SwitchSetting.UNKNOWN,
    val maxCommands: Int = 250_000, val maxGridCells: Int = 200_000,
    val maxTracedCells: Int = 1_000_000, val maxFindings: Int = 1_000,
) {
    init {
        require(listOf(longStitchMm, shortStitchMm, gridMm, assumedSpm, profileSpeedFraction,
            jumpMmPerSecond, upperThreadFactor, bobbinThreadFactor).all { it.isFinite() && it > 0 })
        require(shortStitchMm <= longStitchMm && profileSpeedFraction <= 1 && gridMm in 0.01..1_000_000.0)
        require(listOf(jumpOverheadSeconds, manualCutSeconds, automaticCutSeconds, colorChangeSeconds,
            programStopSeconds, upperTailMmPerCut, bobbinTailMmPerCut).all { it.isFinite() && it >= 0 })
        require(plannedSpm == null || plannedSpm.isFinite() && plannedSpm > 0)
        require(availableBobbinMeters == null || availableBobbinMeters.isFinite() && availableBobbinMeters >= 0)
        require(hotspotPenetrations > 0 && repeatedPointThreshold > 1 && overlapPassThreshold > 1)
        require(maxCommands > 0 && maxGridCells > 0 && maxTracedCells > 0 && maxFindings > 0)
    }
}

data class SewoutReport(val machine: SewoutMachine, val options: SewoutOptions,
    val statistics: SewoutStatistics, val stages: List<ColorStage>, val tasks: List<OperatorTask>,
    val density: List<DensityCell>, val findings: List<Finding>,
    val findingCounts: Map<FindingCode, Int>, val severityCounts: Map<Severity, Int>,
    val suppressedFindings: Int, val analysisComplete: Boolean, val analyzedCommands: Int,
    val inputCommands: Int, val hoopWindow: CheckArea, val speed: SpeedEstimate,
    val time: TimeEstimate, val threadUsage: ThreadEstimate,
    val compatibility: CompatibilityReport, val pesFile: PesFileInfo?) {
    val hasBlockingIssues: Boolean get() = (severityCounts[Severity.BLOCKER] ?: 0) > 0
}
