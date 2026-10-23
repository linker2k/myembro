// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitch_plan/stitch_plan.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Filtering ported from lib/stitch_plan/color_block.py at the same commit.
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.model.EmbThread
import java.util.IdentityHashMap

/** Explicit Ink/Stitch postprocessing; the data contract and lossless adapter never invoke it implicitly. */
object StitchPlanBuilder {
    internal data class RunAnchor(val start: Stitch, val end: Stitch)
    internal data class TracedPlan(val plan: StitchPlan, val anchors: IdentityHashMap<Stitch, RunAnchor>)
    /** Global lengths remain mm, matching the original function arguments; stitches are SVG px. */
    fun fromGroups(
        groups: List<StitchGroup>,
        collapse_len: Double? = null,
        min_stitch_len: Double? = 0.1,
        disable_ties: Boolean = false,
    ): StitchPlan = tracedFromGroups(groups, collapse_len, min_stitch_len, disable_ties).plan

    internal fun tracedFromGroups(
        groups: List<StitchGroup>, collapse_len: Double? = null,
        min_stitch_len: Double? = 0.1, disable_ties: Boolean = false,
    ): TracedPlan {
        val collapsePixels = Units.mmToPixels(collapse_len ?: 3.0)
        val minPixels = Units.mmToPixels(min_stitch_len ?: 0.1)
        // Source's UI exit and empty-group indexing are unsuitable for a reusable pure engine.
        val nonempty = groups.filter { it.stitches.isNotEmpty() }
        val anchors = IdentityHashMap<Stitch, RunAnchor>()
        if (nonempty.isEmpty()) return TracedPlan(StitchPlan(), anchors)
        val blocks = mutableListOf<MutableBlock>()
        var block = MutableBlock(nonempty.first().color).also(blocks::add)
        var previous: StitchGroup? = null
        var needTieIn = true
        var activeJump: Stitch? = null

        for (group in nonempty) {
            if (!sameColor(block.color, group.color)) {
                if (!needTieIn) {
                    block.stitches += previous!!.lockStitches(LockPosition.END, disable_ties)
                    needTieIn = true
                }
                block.command { it.copy(colorChange = true) }
                block = MutableBlock(group.color).also(blocks::add)
            } else if (block.stitches.isNotEmpty() && !needTieIn) {
                val distance = group.stitches.first().distance(block.stitches.last())
                val previousGroup = previous!!
                val threshold = previousGroup.minJumpLengthMm?.takeIf { it != 0.0 }
                    ?.let(Units::mmToPixels) ?: collapsePixels
                if (previousGroup.forceLockStitches || distance > threshold) {
                    block.stitches += previousGroup.lockStitches(LockPosition.END, disable_ties)
                    needTieIn = true
                }
            }

            if (needTieIn) {
                val locks = group.lockStitches(LockPosition.START, disable_ties)
                val jump = (locks.firstOrNull() ?: group.stitches.first()).copy(jump = true)
                block.stitches += jump
                anchors[jump] = RunAnchor(group.stitches.first(), group.stitches.last())
                activeJump = jump
                block.stitches += locks
                needTieIn = false
            }
            block.stitches += group.stitches.map { stitch ->
                if (stitch.minStitchLengthMm == null && group.minStitchLengthMm != null)
                    stitch.copy(minStitchLengthMm = group.minStitchLengthMm) else stitch
            }
            activeJump?.let { jump -> anchors[jump] = anchors.getValue(jump).copy(end = group.stitches.last()) }

            if (group.trimAfter || group.stopAfter) {
                block.stitches += group.lockStitches(LockPosition.END, disable_ties)
                needTieIn = true
            }
            if (group.trimAfter) block.command { it.copy(trim = true) }
            if (group.stopAfter) block.command { it.copy(stop = true) }
            previous = group
        }
        if (!needTieIn) block.stitches += previous!!.lockStitches(LockPosition.END, disable_ties)
        val filtered = blocks.filter { it.stitches.isNotEmpty() }.map {
            ColorBlock(it.color, filterDuplicateStitches(it.stitches, minPixels))
        }
        // A synthetic JUMP without a lock tag can itself be removed by source filtering.
        // Its body then belongs to the preceding surviving run; trace the resulting endpoint.
        val survivors = IdentityHashMap<Stitch, Boolean>()
        filtered.flatMap { it.stitches }.forEach { survivors[it] = true }
        var survivingJump: Stitch? = null
        for (raw in blocks.flatMap { it.stitches }) {
            anchors[raw]?.let { anchor ->
                if (survivors.containsKey(raw)) survivingJump = raw
                else survivingJump?.let { jump -> anchors[jump] = anchors.getValue(jump).copy(end = anchor.end) }
            }
            if (raw.isTerminator) survivingJump = null
        }
        return TracedPlan(StitchPlan(filtered), anchors)
    }

    private fun sameColor(a: EmbThread?, b: EmbThread?): Boolean =
        if (a == null || b == null) a == b else a.sameColor(b)

    private fun filterDuplicateStitches(input: List<Stitch>, globalMinimumPixels: Double): List<Stitch> {
        if (input.isEmpty()) return input
        val kept = mutableListOf(input.first())
        for (stitch in input.drop(1)) {
            // END is our adapter extension; like other terminators it must survive coordinate filtering.
            if (!kept.last().isJump && !stitch.isStop && !stitch.isTrim && !stitch.isColorChange && !stitch.isEnd && !stitch.isLock) {
                val minimum = stitch.minStitchLengthMm?.takeIf { it != 0.0 }
                    ?.let(Units::mmToPixels) ?: globalMinimumPixels
                if (stitch.distance(kept.last()) <= minimum) continue
            }
            kept += stitch
        }
        return kept
    }

    private class MutableBlock(val color: EmbThread?, val stitches: MutableList<Stitch> = mutableListOf()) {
        fun command(transform: (Stitch) -> Stitch) {
            val last = stitches.last()
            // Commands inherit position only, not flags/tags/minimum from the preceding stitch.
            stitches += transform(Stitch(last.x, last.y))
        }
    }
}
