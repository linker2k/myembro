// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitch_plan/stitch_plan.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.ThreadStartCleanup
import io.github.linker2k.myembro.engine.model.Stitch as MachineStitch
import kotlin.math.hypot

/** Opt-in bridge for GENERATED geometry only. Readers and writers must not invoke it on raw imports. */
object GeneratedPatternProcessor {
    const val FINISHED_KEY = "myembro.inkstitch.finished"
    const val LOCK_INDICES_KEY = "myembro.inkstitch.lock_indices"
    const val RUN_ANCHORS_KEY = "myembro.inkstitch.run_anchors"
    const val MAX_STITCH_LENGTH_MM_KEY = "myembro.inkstitch.max_stitch_length_mm"
    const val SEWING_OPTIONS_KEY = "myembro.inkstitch.sewing_options"
    private const val SOURCE_STITCHES_KEY = "myembro.inkstitch.source_stitches"
    private const val COMMAND_ANCHORS_KEY = "myembro.inkstitch.command_anchors"
    private const val VERSION = "v1"

    /** Terminal common sewout pass: no lock replanning. Internal editor/source anchors remain untouched.
     * Removed index provenance cannot be reused after pruning, so only export copies lose it. */
    fun forSewout(pattern: EmbPattern): EmbPattern {
        val cleaned = ThreadStartCleanup.clean(pattern.stitches)
        if (cleaned.stitches == pattern.stitches) return pattern
        return pattern.copy(stitches = cleaned.stitches, metadata = pattern.metadata -
            setOf(LOCK_INDICES_KEY, RUN_ANCHORS_KEY, COMMAND_ANCHORS_KEY, SOURCE_STITCHES_KEY))
    }

    fun fromGroups(
        groups: List<StitchGroup>,
        collapse_len: Double? = null,
        min_stitch_len: Double? = 0.1,
        disable_ties: Boolean = false,
    ): EmbPattern {
        val processed = adaptAndDeduplicate(StitchPlanBuilder.tracedFromGroups(groups, collapse_len, min_stitch_len, disable_ties))
        return processed.pattern.copy(metadata = markedMetadata(processed))
    }

    /** Rebuild removes only locks created by this bridge, retaining original commands and thread indices. */
    fun finish(pattern: EmbPattern, rebuild: Boolean = false, maxStitchLengthMm: Double? = null, options: SewingOptions? = null): EmbPattern {
        // Application defaults always follow the source; old saved experimental options never opt in.
        val sewing = options ?: SewingOptions()
        val maximum = maxStitchLengthMm ?: pattern.metadata[MAX_STITCH_LENGTH_MM_KEY]?.toDoubleOrNull()
        require(maximum == null || maximum.isFinite() && maximum >= 0.1) { "Maximum stitch length must be at least 0.1 mm" }
        if (!rebuild && pattern.metadata[FINISHED_KEY] == VERSION && (maxStitchLengthMm == null || pattern.metadata[MAX_STITCH_LENGTH_MM_KEY]?.toDoubleOrNull() == maxStitchLengthMm) && pattern.metadata[SEWING_OPTIONS_KEY] == sewing.toJson()) return pattern
        val original = stripGeneratedLocks(pattern)
        val output = mutableListOf<MachineStitch>()
        val locks = mutableSetOf<Int>()
        val anchors = mutableMapOf<Int, NativeAnchor>()
        val run = mutableListOf<MachineStitch>()
        var threadIndex = 0
        var summary = ProcessingSummary()
        var previousRunEnd: MachineStitch? = null
        var boundaryBreak = true
        val configuredLocks = LockStitches.fromParameters(sewing.locks)

        fun flushRun(boundaryIndex: Int) {
            if (run.isEmpty()) return
            val next = (boundaryIndex until original.stitches.size).firstOrNull { original.stitches[it].command == Command.STITCH }
            val endsWithBreak = next == null || (boundaryIndex until next).any { original.stitches[it].command in setOf(Command.TRIM, Command.STOP, Command.COLOR_CHANGE, Command.END) }
            val forced = sewing.locks.force_lock_stitches
            val threshold = sewing.min_jump_stitch_length_mm * 10.0
            fun distant(a: MachineStitch, b: MachineStitch) = hypot(a.x.toDouble() - b.x, a.y.toDouble() - b.y) > threshold
            val nextIsDistant = next?.let { distant(run.last(), original.stitches[it]) } ?: false
            val startLock = configuredLocks.start?.takeIf { previousRunEnd == null || boundaryBreak || forced || distant(run.first(), previousRunEnd!!) }
            val endLock = configuredLocks.end?.takeIf { endsWithBreak || forced || nextIsDistant }
            val addTrim = sewing.trim_after && next == null && (boundaryIndex until original.stitches.size).none { original.stitches[it].command == Command.TRIM }
            val group = StitchGroup(
                color = original.threadAt(threadIndex),
                stitches = run.map { Stitch(Units.nativeToPixels(it.x), Units.nativeToPixels(it.y)) },
                lockStart = startLock,
                lockEnd = endLock,
                trimAfter = addTrim,
                // Explicit generated jumps remain boundaries even below the source collapse threshold.
                forceLockStitches = true,
            )
            val processed = adaptAndDeduplicate(StitchPlanBuilder.tracedFromGroups(listOf(group), collapse_len = sewing.min_jump_stitch_length_mm, min_stitch_len = sewing.min_stitch_length_mm))
            summary += processed.summary
            val native = processed.pattern.stitches
            for ((index, stitch) in native.withIndex()) {
                if (index == native.lastIndex && stitch.command == Command.END) continue // Adapter's synthetic END.
                if (index == 0 && stitch.command == Command.JUMP && output.lastOrNull() == stitch) {
                    processed.runAnchors[index]?.let { anchors[output.lastIndex] = it }
                    continue
                }
                processed.runAnchors[index]?.let { anchors[output.size] = it }
                if (index in processed.lockIndices) locks += output.size
                output += stitch
            }
            previousRunEnd = run.last()
            boundaryBreak = false
            run.clear()
        }

        for ((index, stitch) in original.stitches.withIndex()) {
            if (stitch.command == Command.STITCH) run += stitch
            else {
                flushRun(index) // A single tie-off precedes each original terminator or needle-up boundary.
                output += stitch
                if (stitch.command == Command.COLOR_CHANGE) threadIndex++
                if (stitch.command in setOf(Command.TRIM, Command.STOP, Command.COLOR_CHANGE, Command.END)) boundaryBreak = true
            }
        }
        flushRun(original.stitches.size)
        if (output.isNotEmpty() && output.last().command != Command.END) output += output.last().copy(command = Command.END)
        var deduplicated = deduplicate(original.copy(stitches = output), locks, anchors, emptyMap(), summary)
        if (maximum != null) deduplicated = splitToMaximum(deduplicated, maximum * 10.0)
        var metadata = markedMetadata(deduplicated).let { if (maximum != null) it + (MAX_STITCH_LENGTH_MM_KEY to maximum.toString()) else it }
        metadata = metadata + (SOURCE_STITCHES_KEY to encodeSource(original))
        metadata = metadata + (SEWING_OPTIONS_KEY to sewing.toJson())
        return deduplicated.pattern.copy(metadata = metadata)
    }

    /** Preserve index provenance during exact grid transforms; arbitrary rotations should strip/replan. */
    fun mapCoordinates(pattern: EmbPattern, transform: (Int, Int) -> Pair<Int, Int>): EmbPattern {
        val stitches = pattern.stitches.map { s -> transform(s.x, s.y).let { s.copy(x = it.first, y = it.second) } }
        var metadata = pattern.metadata
        if (RUN_ANCHORS_KEY in metadata) {
            val anchors = parseAnchors(metadata[RUN_ANCHORS_KEY]).mapValues { (_, a) ->
                val start = transform(a.startX, a.startY); val end = transform(a.endX, a.endY)
                NativeAnchor(start.first, start.second, end.first, end.second)
            }
            metadata = metadata + (RUN_ANCHORS_KEY to encodeAnchors(anchors))
        }
        if (COMMAND_ANCHORS_KEY in metadata) metadata = metadata + (COMMAND_ANCHORS_KEY to encodeCommands(parseCommandAnchors(metadata[COMMAND_ANCHORS_KEY]).mapValues { (_, p) -> transform(p.first, p.second) }))
        if (SOURCE_STITCHES_KEY in metadata) {
            val baseline = decodeSource(metadata.getValue(SOURCE_STITCHES_KEY), pattern)
            metadata = metadata + (SOURCE_STITCHES_KEY to encodeSource(baseline.copy(stitches = baseline.stitches.map { s -> transform(s.x, s.y).let { s.copy(x = it.first, y = it.second) } })))
        }
        return pattern.copy(stitches = stitches, metadata = metadata)
    }

    private fun splitToMaximum(processed: Processed, maximumNative: Double): Processed {
        val output = mutableListOf<MachineStitch>()
        val locks = mutableSetOf<Int>()
        val anchors = mutableMapOf<Int, NativeAnchor>()
        val commands = mutableMapOf<Int, Pair<Int, Int>>()
        fun emit(stitch: MachineStitch, lock: Boolean) {
            require(output.size < 1_000_000) { "Generated pattern exceeds 1000000 commands" }
            if (lock) locks += output.size
            output += stitch
        }
        fun split(start: MachineStitch, end: MachineStitch, lock: Boolean) {
            if (hypot(end.x.toDouble() - start.x, end.y.toDouble() - start.y) <= maximumNative) { emit(end, lock); return }
            var middle = MachineStitch(Math.round((start.x.toDouble() + end.x) / 2.0).toInt(), Math.round((start.y.toDouble() + end.y) / 2.0).toInt(), Command.STITCH)
            if (middle == start || middle == end) {
                // At a 1-unit maximum a diagonal needs two legal axis moves on the machine grid.
                middle = if (start.x != end.x) MachineStitch(start.x + if (end.x > start.x) 1 else -1, start.y, Command.STITCH)
                    else MachineStitch(start.x, start.y + if (end.y > start.y) 1 else -1, Command.STITCH)
            }
            split(start, middle, lock)
            split(middle, end, lock)
        }
        for ((index, stitch) in processed.pattern.stitches.withIndex()) {
            val previous = output.lastOrNull()
            if (stitch.command == Command.STITCH && previous?.command == Command.STITCH && hypot(stitch.x.toDouble() - previous.x, stitch.y.toDouble() - previous.y) > maximumNative) {
                split(previous, stitch, index in processed.lockIndices)
            } else {
                processed.runAnchors[index]?.let { anchors[output.size] = it }
                processed.commandAnchors[index]?.let { commands[output.size] = it }
                emit(stitch, index in processed.lockIndices)
            }
        }
        return Processed(processed.pattern.copy(stitches = output), locks, anchors, commands, processed.summary)
    }

    /** Removes our tagged native STITCH positions; foreign/unmarked patterns are returned untouched. */
    fun stripGeneratedLocks(pattern: EmbPattern): EmbPattern {
        if (pattern.metadata[FINISHED_KEY] != VERSION) return pattern
        val strippedMetadata = pattern.metadata - FINISHED_KEY - LOCK_INDICES_KEY - RUN_ANCHORS_KEY - COMMAND_ANCHORS_KEY - SOURCE_STITCHES_KEY - SEWING_OPTIONS_KEY - ProcessingSummary().metadata().keys
        // Explicit options keep their pre-filter body, so lowering the minimum regenerates lost points.
        pattern.metadata[SOURCE_STITCHES_KEY]?.let { return decodeSource(it, pattern.copy(metadata = strippedMetadata)) }
        val locks = pattern.metadata[LOCK_INDICES_KEY].orEmpty().split(',').mapNotNull(String::toIntOrNull)
            .filter { it >= 0 && pattern.stitches.getOrNull(it)?.command == Command.STITCH }.toSet()
        val anchors = parseAnchors(pattern.metadata[RUN_ANCHORS_KEY])
        val commandAnchors = parseCommandAnchors(pattern.metadata[COMMAND_ANCHORS_KEY])
        val output = mutableListOf<MachineStitch>()
        var active: NativeAnchor? = null
        var bodyCount = 0
        var removedLocks = false
        fun body(stitch: MachineStitch) {
            if (bodyCount == 0) active?.let {
                if (stitch.x != it.startX || stitch.y != it.startY) {
                    output += MachineStitch(it.startX, it.startY, Command.STITCH)
                    bodyCount++
                }
            }
            output += stitch
            bodyCount++
        }
        fun finishRun() {
            active?.let { anchor ->
                if (bodyCount == 0) { output += MachineStitch(anchor.startX, anchor.startY, Command.STITCH); bodyCount++ }
                val last = output.last()
                if (removedLocks && (last.x != anchor.endX || last.y != anchor.endY || bodyCount == 1))
                    output += MachineStitch(anchor.endX, anchor.endY, Command.STITCH)
            }
            active = null
            bodyCount = 0
            removedLocks = false
        }
        for ((index, stitch) in pattern.stitches.withIndex()) {
            if (index in locks) { removedLocks = true; continue }
            if (stitch.command == Command.STITCH) { body(stitch); continue }
            finishRun()
            if (stitch.command == Command.JUMP) {
                active = anchors[index]
                output += active?.let { stitch.copy(x = it.startX, y = it.startY) } ?: stitch
            } else output += commandAnchors[index]?.let { stitch.copy(x = it.first, y = it.second) } ?: stitch
        }
        finishRun()
        return pattern.copy(stitches = output, metadata = strippedMetadata)
    }

    private data class NativeAnchor(val startX: Int, val startY: Int, val endX: Int, val endY: Int)
    private data class Processed(val pattern: EmbPattern, val lockIndices: Set<Int>, val runAnchors: Map<Int, NativeAnchor>, val commandAnchors: Map<Int, Pair<Int, Int>>, val summary: ProcessingSummary)

    private fun adaptAndDeduplicate(traced: StitchPlanBuilder.TracedPlan): Processed {
        val plan = traced.plan
        val native = EmbPatternAdapter.toEmbPattern(plan)
        val imported = plan.originalThreadPalette != null
        val blocks = if (imported) plan.colorBlocks else plan.colorBlocks.filter { it.stitches.isNotEmpty() }
        var index = 0
        var previous: Command? = null
        val locks = mutableSetOf<Int>()
        val anchors = mutableMapOf<Int, NativeAnchor>()
        val commandAnchors = mutableMapOf<Int, Pair<Int, Int>>()
        var active: NativeAnchor? = null
        var afterTerminator = false
        for ((blockIndex, block) in blocks.withIndex()) {
            if (!imported && blockIndex > 0 && previous != Command.COLOR_CHANGE) {
                active?.let { commandAnchors[index] = it.endX to it.endY }
                index++
                previous = Command.COLOR_CHANGE
            }
            for (stitch in block.stitches) {
                val commands = buildList {
                    if (stitch.isJump) add(Command.JUMP)
                    if (stitch.isTrim) add(Command.TRIM)
                    if (stitch.isStop) add(Command.STOP)
                    if (stitch.isColorChange) add(Command.COLOR_CHANGE)
                    if (stitch.isEnd) add(Command.END)
                    if (isEmpty()) add(Command.STITCH)
                }
                for (command in commands) {
                    if (command == Command.STITCH && afterTerminator) active = null
                    if (command == Command.JUMP) traced.anchors[stitch]?.let {
                        active = NativeAnchor(Units.pixelsToNative(it.start.x), Units.pixelsToNative(it.start.y), Units.pixelsToNative(it.end.x), Units.pixelsToNative(it.end.y))
                        anchors[index] = active!!
                        afterTerminator = false
                    }
                    if (command != Command.JUMP && command != Command.STITCH) {
                        active?.let { commandAnchors[index] = it.endX to it.endY }
                        afterTerminator = true
                    }
                    if (stitch.isLock && command == Command.STITCH) locks += index
                    index++
                    previous = command
                }
            }
        }
        if (!imported && index > 0 && previous != Command.END) {
            active?.let { commandAnchors[index] = it.endX to it.endY }
            index++
        }
        check(index == native.stitches.size) { "Native adapter command/tag correspondence changed" }
        return deduplicate(native, locks, anchors, commandAnchors, traced.summary)
    }

    /** Quantization can collapse distinct SVG points; remove only consecutive native needle penetrations. */
    private fun deduplicate(pattern: EmbPattern, lockIndices: Set<Int>, runAnchors: Map<Int, NativeAnchor>, commandAnchors: Map<Int, Pair<Int, Int>>, summary: ProcessingSummary): Processed {
        val output = mutableListOf<MachineStitch>()
        val locks = mutableSetOf<Int>()
        val anchors = mutableMapOf<Int, NativeAnchor>()
        val commands = mutableMapOf<Int, Pair<Int, Int>>()
        var removed = 0
        for ((index, stitch) in pattern.stitches.withIndex()) {
            val previous = output.lastOrNull()
            if (stitch.command == Command.STITCH && previous?.command == Command.STITCH && stitch.x == previous.x && stitch.y == previous.y) { removed++; continue }
            if (index in lockIndices) locks += output.size
            runAnchors[index]?.let { anchors[output.size] = it }
            commandAnchors[index]?.let { commands[output.size] = it }
            output += stitch
        }
        return Processed(pattern.copy(stitches = output), locks, anchors, commands, summary + ProcessingSummary(shortStitchesRemoved = removed))
    }

    private fun markedMetadata(processed: Processed): Map<String, String> = processed.pattern.metadata + processed.summary.metadata() + mapOf(
        FINISHED_KEY to VERSION, LOCK_INDICES_KEY to processed.lockIndices.sorted().joinToString(","),
        RUN_ANCHORS_KEY to encodeAnchors(processed.runAnchors),
        COMMAND_ANCHORS_KEY to encodeCommands(processed.commandAnchors),
    )
    private fun encodeAnchors(anchors: Map<Int, NativeAnchor>): String = anchors.toSortedMap().entries.joinToString(";") { (i, a) -> "$i:${a.startX}:${a.startY}:${a.endX}:${a.endY}" }
    private fun encodeCommands(anchors: Map<Int, Pair<Int, Int>>): String = anchors.toSortedMap().entries.joinToString(";") { (i, a) -> "$i:${a.first}:${a.second}" }
    private fun encodeSource(pattern: EmbPattern): String = pattern.stitches.joinToString(";") { "${it.x}:${it.y}:${it.command.ordinal}" }
    private fun decodeSource(text: String, pattern: EmbPattern): EmbPattern = pattern.copy(stitches = if (text.isEmpty()) emptyList() else text.split(';').map {
        val parts = it.split(':'); require(parts.size == 3) { "Invalid generated source stream" }
        MachineStitch(parts[0].toInt(), parts[1].toInt(), Command.entries[parts[2].toInt()])
    })

    private fun parseAnchors(value: String?): Map<Int, NativeAnchor> = value.orEmpty().split(';').mapNotNull { part ->
        val n = part.split(':').map { it.toIntOrNull() }
        if (n.size == 5 && n.all { it != null }) n[0]!! to NativeAnchor(n[1]!!, n[2]!!, n[3]!!, n[4]!!) else null
    }.toMap()
    private fun parseCommandAnchors(value: String?): Map<Int, Pair<Int, Int>> = value.orEmpty().split(';').mapNotNull { part ->
        val n = part.split(':').map { it.toIntOrNull() }
        if (n.size == 3 && n.all { it != null }) n[0]!! to (n[1]!! to n[2]!!) else null
    }.toMap()

    private fun EmbPattern.copy(stitches: List<MachineStitch> = this.stitches, metadata: Map<String, String> = this.metadata): EmbPattern =
        EmbPattern(stitches.toList(), threads.toList(), metadata.toMap(), warnings.toList())
}
