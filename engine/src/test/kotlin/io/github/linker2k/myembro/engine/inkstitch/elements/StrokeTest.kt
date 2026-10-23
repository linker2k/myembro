// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/stroke.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import kotlin.test.*

class StrokeTest {
    private val line = listOf(Point(0.0, 0.0), Point(10.0, 0.0))
    private val lock = LockStitch { stitches, _ -> listOf(stitches.first().copy(tags = setOf("lock_stitch"))) }

    @Test fun defaultsAndSvgAttributeUnitsMatchUpstream() {
        val p = StrokeParameters()
        assertEquals("running_stitch", p.stroke_method)
        val locks = Stroke(listOf(line)).toStitchGroups(null).single()
        assertIs<InkLockStitch>(locks.lockStart)
        assertIs<InkLockStitch>(locks.lockEnd)
        assertEquals(listOf(2.5), p.running_stitch_length_mm)
        assertEquals(Units.mmToPixels(2.5), p.runningStitchLength.first())
        assertEquals(Units.mmToPixels(0.2), p.runningStitchTolerance)
        assertEquals(Units.mmToPixels(0.4), p.zigzagSpacing.first())
        val parsed = StrokeParameters.fromAttributes(mapOf("inkstitch:running_stitch_length_mm" to "2 3", "repeats" to "0", "zigzag_angle" to "100", "stroke_pull_compensation_mm" to "0.1 0.3", "enable_random_stitch_length" to "yes"))
        assertEquals(listOf(2.0,3.0), parsed.running_stitch_length_mm)
        assertEquals(1, parsed.repeatCount)
        assertEquals(89.0, parsed.zigzagAngle)
        assertEquals(0.1 to 0.3, parsed.stroke_pull_compensation_mm)
        assertTrue(parsed.enable_random_stitch_length)
        assertEquals(0.01, StrokeParameters(running_stitch_length_mm = listOf(-1.0), running_stitch_tolerance_mm = -1.0).runningStitchLength.first())
        assertEquals(0.01, StrokeParameters(running_stitch_tolerance_mm = -1.0).runningStitchTolerance)
    }

    @Test fun invalidSvgNumbersFallBackWithoutChangingNamesOrDefaults() {
        val parsed = StrokeParameters.fromAttributes(mapOf("running_stitch_length_mm" to "2 broken", "running_stitch_tolerance_mm" to "NaN", "bean_stitch_repeats" to "x", "max_stitch_length_mm" to "-1", "stroke_pull_compensation_mm" to "0.2"))
        assertEquals(listOf(2.5), parsed.running_stitch_length_mm)
        assertEquals(0.2, parsed.running_stitch_tolerance_mm)
        assertEquals(listOf(0), parsed.bean_stitch_repeats)
        assertNull(parsed.maxStitchLength)
        assertEquals(0.2 to 0.2, parsed.stroke_pull_compensation_mm)
    }

    @Test fun runningRepeatsAlternateAndRetainSourceJunctionUntilPlanning() {
        val parameters = StrokeParameters(running_stitch_length_mm = listOf(Units.pixelsToMm(5.0)), repeats = 2)
        val stitches = Stroke(listOf(line), parameters = parameters).toStitchGroups(null).single().stitches
        assertEquals(6, stitches.size)
        assertEquals(stitches[2], stitches[3])
        assertEquals(0.0, stitches.last().x)
    }

    @Test fun manualPreservesVerticesAndDisablesLocksUnlessForced() {
        val normal = Stroke(listOf(line), parameters = StrokeParameters(stroke_method = "manual_stitch", repeats = 3), lockStart = lock, lockEnd = lock).toStitchGroups(null).single()
        assertEquals(line.map(::Stitch), normal.stitches)
        assertNull(normal.lockStart)
        assertNull(normal.lockEnd)
        val forced = Stroke(listOf(line), parameters = StrokeParameters(stroke_method = "manual_stitch", force_lock_stitches = true), lockStart = lock, lockEnd = lock).toStitchGroups(null).single()
        assertSame(lock, forced.lockStart)
        assertSame(lock, forced.lockEnd)
    }

    @Test fun manualSplitsLongSegmentsBeforeApplyingBean() {
        val p = StrokeParameters(stroke_method = "manual_stitch", max_stitch_length_mm = Units.pixelsToMm(3.0))
        val group = Stroke(listOf(line), parameters = p).toStitchGroups(null).single()
        assertEquals(listOf(0.0,2.5,5.0,7.5,10.0,10.0), group.stitches.map { it.x })
        val bean = Stroke(listOf(line), parameters = p.copy(bean_stitch_repeats = listOf(1))).toStitchGroups(null).single()
        assertEquals(16, bean.stitches.size)
    }

    @Test fun zigzagRepeatsReuseOffsetsAndSkipJunction() {
        val p = StrokeParameters(stroke_method = "zigzag_stitch", zigzag_spacing_mm = listOf(Units.pixelsToMm(2.0)), repeats = 2)
        val single = Stroke(listOf(line), parameters = p.copy(repeats = 1), strokeWidth = 2.0).toStitchGroups(null).single().stitches
        val repeated = Stroke(listOf(line), parameters = p, strokeWidth = 2.0).toStitchGroups(null).single().stitches
        assertEquals(single + single.asReversed().drop(1), repeated)
        assertTrue(repeated.zipWithNext().all { (a, b) -> a.point != b.point })
        val closed = Stroke(listOf(line + line.first()), parameters = p.copy(repeats = 1), closed = true, strokeWidth = 2.0).toStitchGroups(null).single().stitches
        assertEquals(closed.first().point, closed.last().point)
    }

    @Test fun distinctPathsRemainGroupsAndCarryCommonPlanFlags() {
        val p = StrokeParameters(trim_after = true, stop_after = true, min_stitch_length_mm = 0.1, min_jump_stitch_length_mm = 0.3)
        val groups = Stroke(listOf(line, emptyList(), line.map { Point(it.x, 20.0) }), parameters = p).toStitchGroups(null)
        assertEquals(2, groups.size)
        assertFalse(groups.first().trimAfter)
        assertFalse(groups.first().stopAfter)
        assertTrue(groups.last().trimAfter)
        assertTrue(groups.last().stopAfter)
        assertTrue(groups.all { it.minStitchLengthMm == 0.1 && it.minJumpLengthMm == 0.3 && it.lockStart != null && it.lockEnd != null })
        assertTrue(groups.flatMap { it.stitches }.all { it.minStitchLengthMm == 0.1 })
        val plan = StitchPlanBuilder.fromGroups(groups)
        val stitches = plan.colorBlocks.flatMap { it.stitches }
        assertEquals(1, stitches.count { it.isTrim })
        assertEquals(1, stitches.count { it.isStop })
        val finalPoint = groups.last().stitches.last().point
        assertEquals(finalPoint, stitches.single { it.isTrim }.point)
        assertEquals(finalPoint, stitches.single { it.isStop }.point)
    }

    @Test fun resolvedCommandsIgnoreBeforeGenerationAndDoNotInventGeometryRouting() {
        var generated = false
        val ignored = Stroke(listOf(line), parameters = StrokeParameters(stroke_method = "ripple_stitch"),
            commands = ElementCommands(ignore_object = true), rippleGenerator = { _, _ -> generated = true; error("Ignored elements must not generate") })
        assertEquals(emptyList(), ignored.toStitchGroups(null))
        assertFalse(generated)
        assertEquals(emptyList(), Stroke(listOf(line), commands = ElementCommands(ignore_layer = true)).toStitchGroups(null))
        val requestedStart = Point(8.0, 8.0)
        val stroke = Stroke(listOf(line), commands = ElementCommands(starting_point = requestedStart, ending_point = Point(9.0, 9.0)))
        assertEquals(requestedStart, stroke.commands.startingPoint)
        assertEquals(line.first(), stroke.toStitchGroups(null).single().stitches.first().point)
        assertEquals(line.last(), stroke.toStitchGroups(null).single().stitches.last().point)
    }

    @Test fun rippleTerminalCommandsApplyOnceAndPreserveExistingCloneFlags() {
        val first = StitchGroup(stitches = listOf(Stitch(0.0, 0.0), Stitch(10.0, 0.0)), trimAfter = true)
        val second = StitchGroup(stitches = listOf(Stitch(0.0, 20.0), Stitch(10.0, 20.0)))
        val stroke = Stroke(listOf(line), parameters = StrokeParameters(stroke_method = "ripple_stitch"),
            commands = ElementCommands(trim = true, stop = true),
            rippleGenerator = { _, _ -> listOf(first, second, StitchGroup()) })
        val groups = stroke.toStitchGroups(null)
        assertTrue(groups[0].trimAfter) // Upstream tests/test_clone.py: inherited flags are ORed, never erased.
        assertFalse(groups[0].stopAfter)
        assertTrue(groups[1].trimAfter)
        assertTrue(groups[1].stopAfter)
        assertFalse(groups[2].trimAfter)
        assertFalse(groups[2].stopAfter)
        assertEquals(first.stitches, groups[0].stitches)
        assertEquals(second.stitches, groups[1].stitches)
    }

    @Test fun cumulativeOutputBudgetRejectsManyRepeatedPathsAndCallbacksBeforeCopying() {
        val shortPath = listOf(Point(0.0, 0.0), Point(1.0, 0.0))
        val repeated = Stroke(List(4) { shortPath }, parameters = StrokeParameters(repeats = 200_000))
        val repeatedError = assertFailsWith<IllegalArgumentException> { repeated.toStitchGroups(null) }
        assertTrue(repeatedError.message.orEmpty().contains("Stroke output exceeds"))
        // Lazy lists prove the shared check counts every group before accessing/copying any stitch.
        val large = object : AbstractList<Stitch>() {
            override val size = 600_000
            override fun get(index: Int): Stitch = error("Oversized callback output must not be traversed")
        }
        val custom = Stroke(listOf(shortPath), parameters = StrokeParameters(stroke_method = "ripple_stitch"),
            rippleGenerator = { _, _ -> listOf(StitchGroup(stitches = large), StitchGroup(stitches = large)) })
        val callbackError = assertFailsWith<IllegalArgumentException> { custom.toStitchGroups(null) }
        assertTrue(callbackError.message.orEmpty().contains("Stroke output exceeds"))
    }

    @Test fun emptySeedUsesElementIdAndOneNodePathMatchesSourceFallback() {
        val p = StrokeParameters(enable_random_stitch_length = true)
        val byId = Stroke(listOf(line), parameters = p, elementId = "path42").toStitchGroups(null)
        val explicit = Stroke(listOf(line), parameters = p.copy(random_seed = "path42")).toStitchGroups(null)
        assertEquals(byId.map { it.stitches }, explicit.map { it.stitches })
        val single = Stroke(listOf(listOf(Point(4.0, 5.0))), parameters = StrokeParameters(stroke_method = "manual_stitch")).toStitchGroups(null).single()
        assertEquals(listOf(Stitch(4.0,5.0), Stitch(5.0,5.0)), single.stitches)
    }

    @Test fun rippleHasDefaultTargetGeneratorAndCustomHookReceivesPreviousStitch() {
        val previous = Stitch(3.0, 4.0)
        assertTrue(Stroke(listOf(line), parameters = StrokeParameters(stroke_method = "ripple_stitch")).toStitchGroups(previous).isNotEmpty())
        val stroke = Stroke(listOf(line), parameters = StrokeParameters(stroke_method = "ripple_stitch"), rippleGenerator = { _, last -> listOf(StitchGroup(stitches = listOf(last!!))) })
        assertEquals(previous, stroke.toStitchGroups(previous).single().stitches.single())
    }
}
