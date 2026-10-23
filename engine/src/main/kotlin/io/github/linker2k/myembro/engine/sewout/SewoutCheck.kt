// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.model.*
import kotlin.math.*

/** Deterministic command-level planning simulation. Does not claim firmware or fabric simulation. */
object SewoutCheck {
    fun originOf(pattern: EmbPattern): DesignOrigin = if (
        pattern.metadata["myembro.inkstitch.finished"] == "v1" || pattern.metadata["myembro.inkstitch.composite"] == "v1"
    ) DesignOrigin.APP_GENERATED else DesignOrigin.IMPORTED

    fun check(pattern: EmbPattern, machine: SewoutMachine = SewoutMachines.SE400,
        options: SewoutOptions = SewoutOptions()): SewoutReport = run(pattern, machine, options)

    /** Project geometry is already positioned in the centered hoop; do not silently recenter it. */
    fun check(project: Project, machine: SewoutMachine = SewoutMachines.SE400,
        options: SewoutOptions = SewoutOptions()): SewoutReport =
        run(project.flatten(), machine, options.copy(hoop = project.hoop, placement = Placement.HOOP_COORDINATES))

    /** Check actual exported bytes to include writer normalization and reader diagnostics. */
    fun checkPes(bytes: ByteArray, machine: SewoutMachine = SewoutMachines.SE400,
        options: SewoutOptions = SewoutOptions()): SewoutReport {
        val signature = if (bytes.size >= 8) String(bytes, 0, 8, Charsets.US_ASCII) else ""
        val info = signature.takeIf { it.matches(Regex("#PES[0-9]{4}")) }?.let { PesFileInfo(it.substring(4), bytes.size) }
        val offset = if (bytes.size >= 12) (0..3).sumOf { (bytes[8+it].toLong() and 255) shl (it*8) } else -1
        if (info == null || offset < 12 || offset >= bytes.size) return run(EmbPattern(emptyList(), emptyList()), machine,
            options, info, "PES 서명 또는 PEC 오프셋이 유효하지 않습니다.")
        val stitchStart = offset + 512
        val thumbnails = if (stitchStart + 8 > bytes.size) null else {
            val colors = bytes[(offset+48).toInt()].toInt() and 255
            val length = (0..2).sumOf { (bytes[(stitchStart+2+it).toInt()].toLong() and 255) shl (8*it) }
            if (colors == 255 || length < 8) null else stitchStart+length+(colors+2)*228L <= bytes.size
        }
        val artifact = info.copy(pecThumbnailsPresent = thumbnails)
        val parsed = runCatching {
            val bounded = boundedPecStitches(bytes)
            val read = PesReader.read(bytes)
            val colors = read.stitches.filter { it.command == Command.COLOR_CHANGE || it.command == Command.STOP }.iterator()
            val commands = bounded.map { if (it.command == Command.COLOR_CHANGE && colors.hasNext())
                it.copy(command = colors.next().command) else it }
            EmbPattern(commands, read.threads, read.metadata, read.warnings)
        }
        return parsed.fold({ pattern -> run(pattern, machine, options, artifact,
            pattern.warnings.firstOrNull { it.contains("truncated", ignoreCase = true) }) }, {
            run(EmbPattern(emptyList(), emptyList()), machine, options, artifact, "PES를 읽을 수 없습니다: ${it.message}")
        })
    }

    private fun run(pattern: EmbPattern, machine: SewoutMachine, o: SewoutOptions,
        file: PesFileInfo? = null, fileError: String? = null): SewoutReport {
        val generated = o.origin == DesignOrigin.APP_GENERATED || o.origin == DesignOrigin.AUTO &&
            originOf(pattern) == DesignOrigin.APP_GENERATED
        val safetySeverity = if (generated) Severity.BLOCKER else Severity.WARNING
        val findings = Findings(o.maxFindings)
        if (fileError != null) findings.add(FindingCode.INVALID_PES, Severity.BLOCKER, fileError)
        val terminal = pattern.stitches.indexOfFirst { it.command == Command.END }
        val inputCount = if (terminal < 0) pattern.stitches.size else terminal+1
        val active = pattern.stitches.take(min(inputCount, o.maxCommands))
        val lockCounts = checkThreadLocks(active, findings, safetySeverity)
        var complete = inputCount <= o.maxCommands
        val maxSpm = machine.maximumEmbroiderySpm.value?.toDouble()
        val requestedSpm = o.plannedSpm ?: maxSpm?.times(o.profileSpeedFraction) ?: o.assumedSpm
        val speed = SpeedEstimate(if (maxSpm == null) requestedSpm else min(requestedSpm, maxSpm),
            if (o.plannedSpm != null) SpeedBasis.USER_SETTING else if (maxSpm != null) SpeedBasis.PROFILE_MAXIMUM_DERIVED else SpeedBasis.ASSUMED,
            machine.maximumEmbroiderySpm)
        if (maxSpm == null) findings.add(FindingCode.MACHINE_SPEED_UNCONFIRMED, Severity.INFO,
            "${machine.name}의 최대 자수 SPM은 미확인입니다. 시간 계산에는 ${speed.effectiveSpm} SPM을 가정합니다.")
        if (maxSpm != null && requestedSpm > maxSpm) findings.add(FindingCode.SPEED_ABOVE_MAXIMUM, Severity.WARNING,
            "계획 속도가 확인된 최대 ${maxSpm.toInt()} SPM을 넘어 최대값으로 계산합니다.")
        findings.add(FindingCode.ESTIMATE_ASSUMPTIONS, Severity.INFO, "시간·실 사용량은 가정값에 따른 계획 추정이며 실제 봉제 시간·원단 변형·실 끊김을 보증하지 않습니다.")
        if (machine.automaticJumpTrim.value == null) findings.add(FindingCode.JUMP_TRIM_UNCONFIRMED, Severity.INFO,
            "점프 연결실 자동 절단은 미확인입니다. 작업 순서표에서 수동 절단/확인 지점을 확인하세요.")
        if (machine.maximumStitchMm.value == null) findings.add(FindingCode.MACHINE_STITCH_LENGTH_UNCONFIRMED, Severity.INFO,
            "기종의 물리적 최대 땀 길이는 미확인입니다. ${o.longStitchMm}mm는 품질 검사 정책값입니다.")
        pattern.warnings.forEach { findings.add(FindingCode.READER_WARNING, Severity.WARNING, it) }
        val movementBounds = bounds(active.map { point(it) })
        val sewnBounds = bounds(active.filter { it.command == Command.STITCH }.map { point(it) })
        val centerX = if (o.placement == Placement.CENTER_DESIGN) movementBounds?.let { (it.minX+it.maxX)/2 } ?: 0.0 else 0.0
        val centerY = if (o.placement == Placement.CENTER_DESIGN) movementBounds?.let { (it.minY+it.maxY)/2 } ?: 0.0 else 0.0
        val window = CheckArea(centerX-o.hoop.width/2.0, centerY-o.hoop.height/2.0,
            centerX+o.hoop.width/2.0, centerY+o.hoop.height/2.0)
        val stages = mutableListOf(Stage(0, pattern.threadAt(0), 0))
        var stage = stages.last()
        val tasks = mutableListOf<OperatorTask>()
        val grid = DensityGrid(o)
        // Gate v5: use the maximum of four half-cell phases, so grid boundaries cannot hide a hotspot.
        val shiftedOptions = o.copy(maxGridCells = maxOf(1, o.maxGridCells / 4))
        val halfCell = o.gridMm * 5
        val shiftedGrids = listOf(DensityGrid(shiftedOptions, halfCell, 0.0),
            DensityGrid(shiftedOptions, 0.0, halfCell), DensityGrid(shiftedOptions, halfCell, halfCell))
        var lastNeedle: NeedlePoint? = null
        var consecutiveNeedles = 0
        val exactSegments = hashMapOf<Pair<NeedlePoint, NeedlePoint>, Int>()
        var current: NeedlePoint? = if (file != null && fileError == null) NeedlePoint(0.0, 0.0) else null
        var maximumStitchMm = 0.0
        var maximumConsecutiveNeedles = 0
        var connected = false
        var elapsed = 0.0
        var travel: Travel? = null
        var pendingTrim: Int? = null
        var lastIndex = 0
        var ended = false
        fun cutRequirement(jump: Boolean): CutRequirement {
            val capability = if (jump) machine.automaticJumpTrim.value else machine.colorEndCutter.value
            val setting = if (jump) o.jumpTrimSetting else o.colorCutSetting
            return when {
                capability == true && setting == SwitchSetting.ENABLED -> CutRequirement.AUTOMATIC
                capability == false || setting == SwitchSetting.DISABLED || jump && machine.manualJumpCleanup.value == true -> CutRequirement.REQUIRED
                else -> CutRequirement.POSSIBLE
            }
        }
        fun task(kind: TaskKind, index: Int, end: Int, from: NeedlePoint, to: NeedlePoint,
            requirement: CutRequirement?, stops: Boolean, message: String, at: Double = elapsed,
            fixedDuration: Double? = null) {
            val duration = fixedDuration ?: if (requirement == CutRequirement.AUTOMATIC) o.automaticCutSeconds else o.manualCutSeconds
            if (requirement == CutRequirement.AUTOMATIC && fixedDuration == null) stage.autoCut += duration
            else {
                stage.operator += duration
                if (requirement == CutRequirement.POSSIBLE && fixedDuration == null) stage.conditional += duration
            }
            val automaticSeconds = if (fixedDuration != null && requirement == CutRequirement.AUTOMATIC) o.automaticCutSeconds else 0.0
            stage.autoCut += automaticSeconds
            elapsed += duration + automaticSeconds
            tasks += OperatorTask(tasks.size+1, kind, index, end, stage.index, from, to, requirement, stops,
                !stops && requirement != CutRequirement.AUTOMATIC, at, duration + automaticSeconds, message)
        }
        fun flushCuts() {
            val trip = travel
            if (trip != null) {
                val requirement = cutRequirement(true)
                if (requirement != CutRequirement.AUTOMATIC) stage.uncutJump += trip.lengthMm
                stage.cuts++
                task(TaskKind.JUMP_THREAD_CUT, trip.start, trip.end, trip.from, trip.to, requirement, false,
                    when (requirement) {
                        CutRequirement.REQUIRED -> "색 내부 연결실을 손으로 잘라 주세요. 지금 자를 경우 직접 정지해야 합니다."
                        CutRequirement.POSSIBLE -> "자동 점프 절단 미확인: 연결실을 확인하고 필요하면 직접 정지 후 잘라 주세요."
                        CutRequirement.AUTOMATIC -> "입력한 점프 자동 절단 설정으로 처리할 계획입니다."
                    }, trip.at)
                travel = null; pendingTrim = null; connected = false
            } else if (pendingTrim != null) {
                val p = current ?: NeedlePoint(0.0, 0.0)
                val requirement = cutRequirement(true)
                stage.cuts++
                task(TaskKind.TRIM, pendingTrim!!, pendingTrim!!, p, p, requirement, false,
                    "TRIM 요청: 기종의 점프 절단 지원/설정을 확인하고 필요하면 직접 정지 후 잘라 주세요.")
                pendingTrim = null; connected = false
            }
        }
        fun motion(to: NeedlePoint, index: Int) {
            val from = current
            val length = from?.let { hypot(to.x-it.x, to.y-it.y)/10 } ?: 0.0
            stage.jumps++; stage.jumpLength += length
            if (length > 0) {
                val seconds = length/o.jumpMmPerSecond + o.jumpOverheadSeconds
                if (connected && from != null) {
                    val trip = travel
                    if (trip == null) travel = Travel(pendingTrim ?: index, index, from, to, length, elapsed)
                    else { trip.to = to; trip.end = index; trip.lengthMm += length }
                }
                stage.movement += seconds; elapsed += seconds
            }
            current = to
        }
        fun finish(index: Int) {
            flushCuts()
            if (stages.sumOf { it.stitches } > 0) {
                val p = current ?: NeedlePoint(0.0, 0.0)
                val requirement = if (connected) cutRequirement(false) else null
                if (connected) stage.cuts++
                task(TaskKind.FINISH, index, index, p, p, requirement, true,
                    "봉제 종료. 자동 절단 설정과 남은 연결실·실 끝을 확인하세요.", fixedDuration = if (connected) null else 0.0)
            }
        }
        for ((index, s) in active.withIndex()) {
            lastIndex = index
            val p = point(s)
            if (!window.contains(p)) findings.add(FindingCode.HOOP_EXCEEDED, Severity.BLOCKER,
                "선택 후프의 봉제 영역을 벗어납니다.", Highlight(index, from = p), threshold = min(o.hoop.width, o.hoop.height)/10.0)
            if (s.command != Command.STITCH && s.command != Command.JUMP && current != null && p != current) motion(p, index)
            when (s.command) {
                Command.STITCH -> {
                    flushCuts()
                    val from = current
                    val length = from?.let { hypot(p.x-it.x, p.y-it.y)/10 } ?: 0.0
                    maximumStitchMm = max(maximumStitchMm, length)
                    if (from != null) {
                        if (length > o.longStitchMm) findings.add(FindingCode.LONG_STITCH, Severity.BLOCKER,
                            "보수적 땀 길이 기준 ${o.longStitchMm}mm를 초과합니다.", Highlight(index, from = from, to = p), length, o.longStitchMm)
                        if (length < o.shortStitchMm && (length > 0.0 || connected)) findings.add(FindingCode.SHORT_STITCH, Severity.INFO,
                            "${o.shortStitchMm}mm 미만의 짧은 땀입니다. 실 뭉침·끊김 가능성을 확인하세요.", Highlight(index, from = from, to = p), length, o.shortStitchMm)
                        machine.maximumStitchMm.value?.let { if (length > it) findings.add(FindingCode.MACHINE_STITCH_LENGTH_EXCEEDED,
                            Severity.BLOCKER, "확인된 기계 최대 땀 길이를 초과합니다.", Highlight(index, from = from, to = p), length, it) }
                        if (abs(p.x-from.x) > 2047 || abs(p.y-from.y) > 2047) findings.add(FindingCode.PEC_DELTA_EXCEEDED,
                            Severity.WARNING, "PEC 단일 명령 변위 범위를 초과합니다. 내보내기 분할 뒤의 실제 PES도 다시 검사하세요.", Highlight(index, from = from, to = p))
                        grid.segment(from, p, stage.index, index)
                        val key = if (from.x < p.x || from.x == p.x && from.y <= p.y) from to p else p to from
                        if (from != p) {
                            val repeated = (exactSegments[key] ?: 0)+1; exactSegments[key] = repeated
                            if (repeated == 3) findings.add(FindingCode.OVERLAP_ACCUMULATION, Severity.INFO,
                                "같은 선분을 3회 이상 겹쳐 봉제합니다.", Highlight(index, from = from, to = p), repeated.toDouble(), 3.0)
                        }
                    }
                    grid.needle(p, stage.index, index)
                    shiftedGrids.forEach { it.needle(p, stage.index, index) }
                    val repeats = if (lastNeedle == p) consecutiveNeedles + 1 else 1
                    lastNeedle = p; consecutiveNeedles = repeats
                    maximumConsecutiveNeedles = max(maximumConsecutiveNeedles, repeats)
                    if (repeats == o.repeatedPointThreshold) findings.add(FindingCode.REPEATED_PENETRATION, safetySeverity,
                        "같은 좌표에 바늘이 연속 ${o.repeatedPointThreshold}회 이상 진입합니다. 실 뭉침·원단 손상 위험을 확인하세요.",
                        Highlight(index, from = p), repeats.toDouble(), o.repeatedPointThreshold.toDouble())
                    stage.stitches++; stage.sewn += length
                    val seconds = 60/speed.effectiveSpm
                    stage.needle += seconds; elapsed += seconds
                    current = p; connected = true
                }
                Command.JUMP -> motion(p, index)
                Command.TRIM -> { stage.trims++; if (connected && pendingTrim == null) pendingTrim = index }
                Command.COLOR_CHANGE -> {
                    flushCuts()
                    val at = current ?: p
                    val requirement = if (connected) cutRequirement(false) else null
                    if (connected) stage.cuts++
                    task(TaskKind.COLOR_CHANGE, index, index, at, at, requirement, true,
                        "색 단계가 끝나 기계가 멈춥니다. 다음 실로 바꾸고 재시작하세요.", fixedDuration = o.colorChangeSeconds)
                    stage.end = index
                    stages += Stage(stage.index+1, pattern.threadAt(stage.index+1), min(index+1, active.lastIndex))
                    stage = stages.last(); connected = false; current = p
                }
                Command.STOP -> {
                    flushCuts(); val at = current ?: p
                    task(TaskKind.PROGRAM_STOP, index, index, at, at, null, true, "프로그램 정지입니다. 필요한 작업 후 재시작하세요.", fixedDuration = o.programStopSeconds)
                    current = p
                }
                Command.END -> { finish(index); current = p; ended = true }
            }
        }
        if (!ended && complete) finish(lastIndex) else if (!ended) flushCuts()
        stage.end = active.lastIndex
        val cells = grid.result()
        val shiftedPeak = shiftedGrids.flatMap { it.result() }.maxByOrNull { it.penetrations }
        val densityChecks = if (shiftedPeak != null && shiftedPeak.penetrations > (cells.maxOfOrNull { it.penetrations } ?: 0))
            cells + shiftedPeak else cells
        for (cell in densityChecks) {
            val location = Highlight(cell.firstCommandIndex, area = cell.area)
            if (cell.penetrations >= o.hotspotPenetrations) findings.add(FindingCode.DENSITY_HOTSPOT, if (generated || cell.penetrations > 30) Severity.BLOCKER else Severity.WARNING,
                "${o.gridMm}mm 격자에 ${cell.penetrations}회 바늘 진입이 누적됩니다. 원단 손상·바늘 부담 위험을 확인하세요.",
                location, cell.penetrations.toDouble(), o.hotspotPenetrations.toDouble())
            if (cell.segmentPasses >= o.overlapPassThreshold && cell.stageIndices.size > 1) findings.add(FindingCode.OVERLAP_ACCUMULATION,
                Severity.INFO, "여러 색 단계의 실 경로가 같은 격자에 누적됩니다.", location, cell.segmentPasses.toDouble(), o.overlapPassThreshold.toDouble())
        }
        val resourcesComplete = complete && grid.complete && shiftedGrids.all { it.complete }
        complete = resourcesComplete && fileError == null
        if (!resourcesComplete) findings.add(FindingCode.ANALYSIS_LIMIT, Severity.BLOCKER, "검사 자원 한도를 넘어 일부 분석이 생략됐습니다. 검사 통과로 간주할 수 없습니다.")
        val usedStages = if (active.none { it.command != Command.END }) emptyList() else stages
        val resultStages = usedStages.map { st ->
            if (st.stitches == 0) findings.add(FindingCode.EMPTY_COLOR_STAGE, Severity.WARNING, "바늘땀이 없는 색 단계가 있습니다.", Highlight(st.start))
            ColorStage(st.index, st.thread, st.start, st.end, st.stitches, st.jumps, st.trims, st.sewn,
                st.uncutJump, st.time(), usage(st.sewn, st.uncutJump, st.cuts, machine, o))
        }
        val totalStitches = stages.sumOf { it.stitches }
        if (totalStitches == 0) findings.add(FindingCode.EMPTY_DESIGN, Severity.BLOCKER, "봉제할 바늘땀이 없습니다.")
        val time = TimeEstimate(stages.sumOf { it.needle }, stages.sumOf { it.movement }, stages.sumOf { it.autoCut },
            stages.sumOf { it.operator }, stages.sumOf { it.conditional })
        val threadUsage = usage(stages.sumOf { it.sewn }, stages.sumOf { it.uncutJump }, stages.sumOf { it.cuts }, machine, o)
        when (threadUsage.oneBobbin) {
            Sufficiency.UNKNOWN -> findings.add(FindingCode.BOBBIN_CAPACITY_UNCONFIRMED, Severity.INFO, "밑실 잔량/1개 용량이 미확인이라 한 개로 충분한지 판정할 수 없습니다. 측정한 잔량을 입력하세요.")
            Sufficiency.INSUFFICIENT -> findings.add(FindingCode.BOBBIN_INSUFFICIENT, Severity.WARNING, "추정 밑실 사용량이 입력한 밑실 잔량을 초과합니다.", measured = threadUsage.bobbinMeters, threshold = threadUsage.availableBobbinMeters)
            Sufficiency.ENOUGH -> Unit
        }
        val stats = SewoutStatistics(totalStitches, stages.sumOf { it.jumps }, stages.sumOf { it.trims },
            active.count { it.command == Command.COLOR_CHANGE }, active.count { it.command == Command.STOP },
            resultStages.size, resultStages.filter { it.stitchCount > 0 }.map { it.thread }.distinctBy {
                listOf(it.color and 0xFFFFFF, it.description, it.catalogNumber, it.brand, it.chart) }.size,
            sewnBounds, movementBounds, stages.sumOf { it.sewn }, stages.sumOf { it.jumpLength },
            tasks.count { it.kind in setOf(TaskKind.JUMP_THREAD_CUT, TaskKind.TRIM) && it.cutRequirement == CutRequirement.REQUIRED },
            tasks.count { it.kind in setOf(TaskKind.JUMP_THREAD_CUT, TaskKind.TRIM) && it.cutRequirement == CutRequirement.POSSIBLE },
            densityChecks.count { it.penetrations >= o.hotspotPenetrations }, cells.count { it.stageIndices.size > 1 && it.segmentPasses >= o.overlapPassThreshold },
            densityChecks.maxOfOrNull { it.penetrations } ?: 0, maximumConsecutiveNeedles, maximumStitchMm,
            lockCounts.startMissing, lockCounts.endMissing)
        val compatibility = compatibility(machine, o, stats, file, fileError, complete, findings)
        return SewoutReport(machine, o, stats, resultStages, tasks.toList(), cells, findings.items.sortedBy { it.severity.ordinal },
            findings.codes.toMap(), findings.severities.toMap(), findings.total-findings.items.size, complete,
            active.size, inputCount, window, speed, time, threadUsage, compatibility, file)
    }

    private fun usage(sewn: Double, jumps: Double, cuts: Int, machine: SewoutMachine, o: SewoutOptions): ThreadEstimate {
        val upper = (sewn*o.upperThreadFactor + jumps + cuts*o.upperTailMmPerCut)/1000
        val bobbin = (sewn*o.bobbinThreadFactor + jumps + cuts*o.bobbinTailMmPerCut)/1000
        val available = o.availableBobbinMeters ?: machine.fullBobbinMeters.value
        return ThreadEstimate(upper, bobbin, available, when {
            available == null -> Sufficiency.UNKNOWN
            bobbin > available -> Sufficiency.INSUFFICIENT
            else -> Sufficiency.ENOUGH
        })
    }
    private fun point(s: Stitch) = NeedlePoint(s.x.toDouble(), s.y.toDouble())
    private fun bounds(points: List<NeedlePoint>): CheckArea? = points.takeIf { it.isNotEmpty() }?.let {
        CheckArea(it.minOf { p -> p.x }, it.minOf { p -> p.y }, it.maxOf { p -> p.x }, it.maxOf { p -> p.y }) }
    private data class Travel(val start: Int, var end: Int, val from: NeedlePoint, var to: NeedlePoint, var lengthMm: Double, val at: Double)
    private class Stage(val index: Int, val thread: EmbThread, val start: Int) {
        var end = start; var stitches = 0; var jumps = 0; var trims = 0; var cuts = 0
        var sewn = 0.0; var jumpLength = 0.0; var uncutJump = 0.0
        var needle = 0.0; var movement = 0.0; var autoCut = 0.0; var operator = 0.0; var conditional = 0.0
        fun time() = TimeEstimate(needle, movement, autoCut, operator, conditional)
    }
}

internal class Findings(private val maximum: Int) {
    val items = mutableListOf<Finding>()
    val codes = linkedMapOf<FindingCode, Int>()
    val severities = linkedMapOf<Severity, Int>()
    var total = 0; private set
    private var worstOrdinal = Severity.INFO.ordinal
    fun add(code: FindingCode, severity: Severity, message: String, highlight: Highlight? = null,
        measured: Double? = null, threshold: Double? = null) {
        codes[code] = (codes[code] ?: 0)+1; severities[severity] = (severities[severity] ?: 0)+1; total++
        val finding = Finding(code, severity, message, highlight, measured, threshold)
        if (items.size < maximum) {
            items += finding
            if (items.size == maximum) worstOrdinal = items.maxOf { it.severity.ordinal }
        } else if (severity.ordinal < worstOrdinal) {
            val replace = items.indexOfLast { it.severity.ordinal == worstOrdinal }
            items[replace] = finding
            worstOrdinal = items.maxOf { it.severity.ordinal }
        }
    }
}
