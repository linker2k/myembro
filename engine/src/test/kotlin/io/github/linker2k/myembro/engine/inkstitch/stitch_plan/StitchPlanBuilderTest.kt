// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitch_plan/stitch_plan.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.test.*

/** Analytical fixtures: upstream has no stitch_plan.py unit tests and was not executed. */
class StitchPlanBuilderTest {
    private val red = EmbThread(0xFFCC0000.toInt())
    private val blue = EmbThread(0xFF0000CC.toInt())
    private val p = Units.PIXELS_PER_MM
    private fun stitch(mm: Double) = Stitch(mm * p, 0.0)
    private fun group(start: Double, end: Double, color: EmbThread = red, locks: Boolean = false) = StitchGroup(
        color = color, stitches = listOf(stitch(start), stitch(end)),
        lockStart = if (locks) InkLockStitch() else null, lockEnd = if (locks) InkLockStitch() else null,
    )

    @Test fun addsJumpTieInBodyAndTieOffInSourceOrder() {
        val output = StitchPlanBuilder.fromGroups(listOf(group(0.0, 2.0, locks = true))).colorBlocks.single().stitches
        val expected = listOf(0.0, 0.0, 0.75, 1.5, 0.75, 0.0, 2.0, 1.25, 0.5, 1.25, 2.0)
        assertEquals(expected.size, output.size)
        expected.zip(output).forEach { (x, s) -> assertEquals(x * p, s.x, 1e-9) }
        assertTrue(output.first().isJump)
        assertTrue(output.drop(1).filterIndexed { i, _ -> i != 5 }.all { it.isLock })
        assertFalse(output[6].isLock)
        // Original preserves the zero-distance lock immediately after the initial jump.
        assertEquals(output[0].point, output[1].point)
    }

    @Test fun rgbEqualityIgnoresThreadMetadataWhileDifferentColorCreatesTerminator() {
        val renamed = red.copy(description = "Different catalogue metadata")
        val same = StitchPlanBuilder.fromGroups(listOf(group(0.0, 1.0), group(1.0, 2.0, renamed)))
        assertEquals(1, same.colorBlocks.size)
        assertEquals(red, same.colorBlocks.single().color)
        val changed = StitchPlanBuilder.fromGroups(listOf(group(0.0, 1.0, locks = true), group(1.0, 2.0, blue, locks = true)))
        assertEquals(listOf(red, blue), changed.colorBlocks.map { it.color })
        assertTrue(changed.colorBlocks.first().stitches.last().isColorChange)
        assertTrue(changed.colorBlocks.last().stitches.first().isJump)
        assertEquals(1, changed.colorBlocks.flatMap { it.stitches }.count { it.isColorChange })
    }

    @Test fun collapseThresholdIsStrictAndPreviousGroupOverrideTakesPrecedence() {
        fun jumps(gap: Double, overrideMm: Double? = null, collapseMm: Double? = null, force: Boolean = false) =
            StitchPlanBuilder.fromGroups(listOf(group(0.0, 1.0).copy(minJumpLengthMm = overrideMm, forceLockStitches = force), group(1.0 + gap, 6.0)), collapse_len = collapseMm)
                .colorBlocks.single().stitches.count { it.isJump }
        assertEquals(1, jumps(3.0))
        assertEquals(2, jumps(3.001))
        assertEquals(2, jumps(2.0, overrideMm = 1.0))
        assertEquals(1, jumps(2.0, overrideMm = 4.0, collapseMm = 1.0))
        assertEquals(1, jumps(2.0, overrideMm = 0.0))
        // With no lock definition the forced jump at an identical coordinate is filtered upstream.
        assertEquals(1, jumps(0.0, force = true))
        val forcedLocks = StitchPlanBuilder.fromGroups(listOf(group(0.0, 2.0, locks = true).copy(forceLockStitches = true), group(2.0, 4.0, locks = true)))
        assertEquals(2, forcedLocks.colorBlocks.single().stitches.count { it.isJump })
    }

    @Test fun trimAndStopFollowOneTieOffAndRequireNewTieInWithoutChangingColorBlock() {
        val first = group(0.0, 2.0, locks = true).copy(trimAfter = true, stopAfter = true)
        val output = StitchPlanBuilder.fromGroups(listOf(first, group(2.0, 4.0, locks = true))).colorBlocks.single().stitches
        val trim = output.indexOfFirst { it.isTrim }
        assertTrue(output[trim - 1].isLock)
        assertTrue(output[trim + 1].isStop)
        assertTrue(output[trim + 2].isJump)
        assertEquals(1, output.count { it.isTrim })
        assertEquals(1, output.count { it.isStop })
        assertFalse(output[trim].isLock)
        assertFalse(output[trim + 1].isLock)
        assertEquals(2, output.count { it.isJump })
    }

    @Test fun duplicateFilterUsesLastKeptStitchAndRemovesEqualThreshold() {
        val input = listOf(0.0, 1.0, 1.01, 2.0, 3.1).map(::stitch)
        val output = StitchPlanBuilder.fromGroups(listOf(StitchGroup(red, input)), min_stitch_len = 1.0).colorBlocks.single().stitches
        assertEquals(listOf(0.0, 0.0, 1.01 * p, 3.1 * p), output.map { it.x })
        assertTrue(output.first().isJump)
    }

    @Test fun groupMinimumFillsMissingValuesAndPreservesPerStitchOverride() {
        val input = listOf(stitch(0.0), stitch(0.1).copy(minStitchLengthMm = 0.05), stitch(0.2), stitch(0.4))
        val output = StitchPlanBuilder.fromGroups(listOf(StitchGroup(red, input, minStitchLengthMm = 0.2))).colorBlocks.single().stitches
        assertEquals(listOf(0.0, 0.0, 0.1 * p, 0.4 * p), output.map { it.x })
        assertEquals(0.05, output[2].minStitchLengthMm)
        assertEquals(0.2, output.last().minStitchLengthMm)
        val zero = StitchPlanBuilder.fromGroups(listOf(StitchGroup(red, listOf(stitch(0.0), stitch(0.05).copy(minStitchLengthMm = 0.0))))).colorBlocks.single().stitches
        assertEquals(2, zero.size)
    }

    @Test fun commandAndLockExceptionsSurviveButCurrentJumpIsNotAnException() {
        val input = listOf(
            Stitch(0.0, 0.0), Stitch(5.0, 0.0),
            Stitch(5.0, 0.0, jump = true), // Source considers this a duplicate and removes it.
            Stitch(5.0, 0.0, tags = setOf("lock_stitch")),
            Stitch(5.0, 0.0, trim = true), Stitch(5.0, 0.0, stop = true),
            Stitch(5.0, 0.0, colorChange = true), Stitch(5.0, 0.0, end = true),
        )
        val output = StitchPlanBuilder.fromGroups(listOf(StitchGroup(red, input))).colorBlocks.single().stitches
        assertEquals(8, output.size)
        assertEquals(1, output.count { it.isJump })
        assertEquals(listOf(true, true, true, true, true), output.takeLast(5).map { it.isLock || it.isTerminator })
    }

    @Test fun disableTiesOverridesForceButRetainsJumpTrimAndStop() {
        val input = group(0.0, 2.0, locks = true).copy(forceLockStitches = true, trimAfter = true, stopAfter = true)
        val output = StitchPlanBuilder.fromGroups(listOf(input), disable_ties = true).colorBlocks.single().stitches
        assertFalse(output.any { it.isLock })
        assertEquals(listOf(true, true, true), listOf(output.first().isJump, output[output.size - 2].isTrim, output.last().isStop))
    }

    @Test fun emptyGroupsDoNotLoseFinalTieOffOrInventColors() {
        val emptyBlue = StitchGroup(color = blue)
        assertEquals(StitchPlan(), StitchPlanBuilder.fromGroups(emptyList()))
        assertEquals(StitchPlan(), StitchPlanBuilder.fromGroups(listOf(emptyBlue)))
        val body = group(0.0, 2.0, locks = true)
        assertEquals(StitchPlanBuilder.fromGroups(listOf(body)), StitchPlanBuilder.fromGroups(listOf(emptyBlue, body, emptyBlue)))
    }

    @Test fun explicitNullDefaultsMatchOmittedDefaultsAndNonFiniteValuesFail() {
        val input = listOf(group(0.0, 1.0), group(5.0, 7.0))
        assertEquals(StitchPlanBuilder.fromGroups(input), StitchPlanBuilder.fromGroups(input, collapse_len = null, min_stitch_len = null))
        assertFailsWith<IllegalArgumentException> { StitchPlanBuilder.fromGroups(input, collapse_len = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { StitchPlanBuilder.fromGroups(input, min_stitch_len = Double.POSITIVE_INFINITY) }
    }
}
