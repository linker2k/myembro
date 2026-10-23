// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch as NativeStitch
import kotlin.test.*

class SewingOptionsTest {
    private val red = EmbThread(0xFFCC0000.toInt())
    private fun point(x: Int, command: Command = Command.STITCH) = NativeStitch(x, 0, command)
    private val dense = EmbPattern(listOf(point(0, Command.JUMP), point(0), point(1), point(2), point(10), point(10, Command.END)), listOf(red))
    private fun noTies(min: Double = 0.1, trim: Boolean = false) = SewingOptions(locks = LockStitchParameters(ties = 3), min_stitch_length_mm = min, trim_after = trim)

    @Test fun everySourceParameterRoundTripsIncludingCustomPathsAndSignedScales() {
        val options = SewingOptions(LockStitchParameters(
            ties = 2, force_lock_stitches = true, lock_start = "custom", lock_end = "back_forth",
            lock_custom_start = "M0 0 L.5 1 L1 0", lock_custom_end = "1  1 -1 -1",
            lock_start_scale_mm = -0.8, lock_end_scale_mm = 1.25, lock_start_scale_percent = 135.0, lock_end_scale_percent = -70.0,
        ), 0.07, 2.25, true)
        assertEquals(options, SewingOptions.fromJson(options.toJson()))
        assertEquals(SewingOptions(), SewingOptions.fromJson("{}"))
        assertFailsWith<IllegalArgumentException> { SewingOptions(min_stitch_length_mm = -1.0) }
        assertFailsWith<IllegalArgumentException> { SewingOptions(min_jump_stitch_length_mm = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { SewingOptions(locks = LockStitchParameters(ties = 4)) }
    }

    @Test fun sourceAndNativeFilteringCountsAreMeasuredAndLocksCountPatternsInsteadOfPoints() {
        assertNull(ProcessingSummary.fromPattern(dense))
        val points = listOf(0.0, 0.05, 0.09, 2.0).map { Stitch(Units.mmToPixels(it), 0.0) }
        val plain = GeneratedPatternProcessor.fromGroups(listOf(StitchGroup(red, points)))
        assertEquals(ProcessingSummary(0, 2), ProcessingSummary.fromPattern(plain))
        val tiny = LockStitch { _, _ -> listOf(Stitch(0.0, 0.0, setOf("lock_stitch")), Stitch(0.01, 0.0, setOf("lock_stitch"))) }
        val quantized = GeneratedPatternProcessor.fromGroups(listOf(StitchGroup(red, listOf(Stitch(0.0, 0.0), Stitch(Units.mmToPixels(2.0), 0.0)), lockStart = tiny)))
        assertEquals(ProcessingSummary(1, 2), ProcessingSummary.fromPattern(quantized))
        assertNull(ProcessingSummary.fromPattern(GeneratedPatternProcessor.stripGeneratedLocks(quantized)))
    }

    @Test fun changedOptionsRegenerateFromPreFilterBodyAndSameOptionsReturnSamePattern() {
        val high = GeneratedPatternProcessor.finish(dense, options = noTies(0.3, trim = true))
        assertEquals(listOf(0, 10), high.stitches.filter { it.command == Command.STITCH }.map { it.x })
        assertEquals(ProcessingSummary(0, 2), ProcessingSummary.fromPattern(high))
        assertEquals(1, high.trimCount)
        assertSame(high, GeneratedPatternProcessor.finish(high, options = noTies(0.3, trim = true)))
        val low = GeneratedPatternProcessor.finish(high, options = noTies(0.0))
        assertEquals(dense.stitches, low.stitches)
        assertEquals(ProcessingSummary(), ProcessingSummary.fromPattern(low))
        assertEquals(noTies(0.0), SewingOptions.fromJson(low.metadata.getValue(GeneratedPatternProcessor.SEWING_OPTIONS_KEY)))
        assertEquals(low.stitches, GeneratedPatternProcessor.finish(low, rebuild = true, options = noTies(0.0)).stitches)
        val repeated = EmbPattern(listOf(point(0, Command.JUMP), point(0), point(0), point(0, Command.END)), listOf(red))
        val legacy = GeneratedPatternProcessor.finish(repeated)
        // Source half-stitch applies START/END even to coincident input: two removed body
        // duplicates plus four coincident native lock points removed after quantization.
        assertEquals(ProcessingSummary(2, 6), ProcessingSummary.fromPattern(legacy))
        assertEquals(repeated.stitches, GeneratedPatternProcessor.stripGeneratedLocks(legacy).stitches)
        assertEquals(ProcessingSummary.fromPattern(legacy), ProcessingSummary.fromPattern(GeneratedPatternProcessor.finish(legacy, rebuild = true)))
    }

    @Test fun tiesForceAndMinimumJumpAffectOnlyActualLockApplication() {
        val separated = EmbPattern(listOf(point(0, Command.JUMP), point(0), point(20), point(21, Command.JUMP), point(21), point(41), point(41, Command.END)), listOf(red))
        fun summary(options: SewingOptions) = ProcessingSummary.fromPattern(GeneratedPatternProcessor.finish(separated, options = options))!!
        assertEquals(2, summary(SewingOptions()).lockLocations) // outer START/END, short gap suppressed.
        assertEquals(4, summary(SewingOptions(locks = LockStitchParameters(force_lock_stitches = true))).lockLocations)
        assertEquals(4, summary(SewingOptions(min_jump_stitch_length_mm = 0.0)).lockLocations)
        assertEquals(1, summary(SewingOptions(locks = LockStitchParameters(ties = 1))).lockLocations)
        assertEquals(1, summary(SewingOptions(locks = LockStitchParameters(ties = 2))).lockLocations)
        assertEquals(0, summary(noTies()).lockLocations)
        assertEquals(separated.stitches.filter { it.command == Command.JUMP }, GeneratedPatternProcessor.finish(separated, options = SewingOptions()).stitches.filter { it.command == Command.JUMP })
    }

    @Test fun transformedSavedBaselineRegeneratesInThePlacedCoordinateSystem() {
        val high = GeneratedPatternProcessor.finish(dense, options = noTies(0.3))
        val transformed = GeneratedPatternProcessor.mapCoordinates(high) { x, y -> -y + 50 to x + 20 }
        val low = GeneratedPatternProcessor.finish(transformed, options = noTies(0.0))
        assertEquals(GeneratedPatternProcessor.mapCoordinates(dense) { x, y -> -y + 50 to x + 20 }.stitches, low.stitches)
        val stripped = GeneratedPatternProcessor.stripGeneratedLocks(high)
        val placed = EmbPattern(stripped.stitches.map { it.copy(x = it.x * 2) }, stripped.threads, stripped.metadata)
        val finished = GeneratedPatternProcessor.finish(placed, options = noTies(0.0))
        assertEquals(dense.stitches.map { it.copy(x = it.x * 2) }, finished.stitches)
    }

    @Test fun projectAggregatesOnlyGeneratedSummariesAndLeavesRawObjectSewingUntouched() {
        val rawObject = StitchObject("raw", "raw", dense)
        val generated = TextObject("text", text = "I", transform = Transform(x = 100.0))
        val settings = noTies(0.3)
        val rawOnly = Project(objects = listOf(rawObject))
        assertEquals(rawOnly.flatten().stitches, rawOnly.flatten(settings).stitches)
        assertNull(ProcessingSummary.fromPattern(rawOnly.flatten(settings)))
        val processed = GeneratedPatternProcessor.finish(generated.placedPattern(), options = settings)
        val combined = Project(objects = listOf(rawObject, generated)).flatten(settings)
        assertEquals(ProcessingSummary.fromPattern(processed), ProcessingSummary.fromPattern(combined))
        assertFalse(combined.metadata.containsKey(GeneratedPatternProcessor.FINISHED_KEY))
        val doubled = Project(objects = listOf(generated, generated.copy(id = "text2"))).flatten(settings)
        val summary = ProcessingSummary.fromPattern(processed)!!
        assertEquals(summary + summary, ProcessingSummary.fromPattern(doubled))
    }

    @Test fun applicationDefaultsIgnoreOldSavedCustomOptionsWhileExplicitEngineApiRemainsAvailable() {
        val saved = GeneratedPatternProcessor.finish(dense, options = noTies(0.3))
        val defaults = GeneratedPatternProcessor.finish(saved)
        assertEquals(SewingOptions().toJson(), defaults.metadata[GeneratedPatternProcessor.SEWING_OPTIONS_KEY])
        assertTrue(ProcessingSummary.fromPattern(defaults)!!.lockLocations > 0)
        assertSame(defaults, GeneratedPatternProcessor.finish(defaults))
        assertEquals(0, ProcessingSummary.fromPattern(GeneratedPatternProcessor.finish(defaults, options = noTies(0.0)))!!.lockLocations)
        val generated = StitchObject("generated", "generated", saved)
        val project = Project(objects = listOf(generated))
        assertEquals(project.flatten(SewingOptions()).stitches, project.flatten().stitches)
        assertTrue(ProcessingSummary.fromPattern(project.flatten())!!.lockLocations > 0)
    }
}
