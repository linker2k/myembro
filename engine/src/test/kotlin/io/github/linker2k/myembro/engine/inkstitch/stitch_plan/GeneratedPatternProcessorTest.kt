// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch as MachineStitch
import kotlin.test.*

class GeneratedPatternProcessorTest {
    private val red = EmbThread(0xFFCC0000.toInt(), "Red")
    private fun machine(x: Int, y: Int = 0, command: Command = Command.STITCH) = MachineStitch(x, y, command)
    private val raw = EmbPattern(listOf(machine(0, command = Command.JUMP), machine(0), machine(20), machine(40), machine(40, command = Command.END)), listOf(red), mapOf("name" to "fixture"), listOf("source warning"))
    private fun assertNoConsecutiveDuplicateNeedles(pattern: EmbPattern) {
        assertFalse(pattern.stitches.zipWithNext().any { (a, b) -> a.command == Command.STITCH && b.command == Command.STITCH && a.x == b.x && a.y == b.y })
    }
    private fun assertPatternEquals(expected: EmbPattern, actual: EmbPattern) {
        assertEquals(expected.stitches, actual.stitches)
        assertEquals(expected.threads, actual.threads)
        assertEquals(expected.metadata, actual.metadata)
        assertEquals(expected.warnings, actual.warnings)
    }
    private fun EmbPattern.copy(stitches: List<MachineStitch> = this.stitches, metadata: Map<String, String> = this.metadata) =
        EmbPattern(stitches, threads, metadata, warnings)

    @Test fun generatedBridgeAddsDefaultLocksPreservesMetadataAndIsIdempotent() {
        val finished = GeneratedPatternProcessor.finish(raw)
        assertTrue(finished.stitchCount > raw.stitchCount)
        assertEquals(raw.threads, finished.threads)
        assertEquals(raw.warnings, finished.warnings)
        assertEquals("fixture", finished.metadata["name"])
        assertEquals("v1", finished.metadata[GeneratedPatternProcessor.FINISHED_KEY])
        assertTrue(finished.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY]!!.isNotEmpty())
        assertSame(finished, GeneratedPatternProcessor.finish(finished))
        assertPatternEquals(finished, GeneratedPatternProcessor.finish(finished, rebuild = true))
        assertNoConsecutiveDuplicateNeedles(finished)
    }

    @Test fun strippingOwnLocksRestoresBodyAnchorAndOriginalCommands() {
        val finished = GeneratedPatternProcessor.finish(raw)
        val stripped = GeneratedPatternProcessor.stripGeneratedLocks(finished)
        assertPatternEquals(raw, stripped)
        assertPatternEquals(finished, GeneratedPatternProcessor.finish(stripped))
        assertFalse(stripped.metadata.containsKey(GeneratedPatternProcessor.FINISHED_KEY))
        assertFalse(stripped.metadata.containsKey(GeneratedPatternProcessor.LOCK_INDICES_KEY))
    }

    @Test fun commandsIncludingSameRgbColorChangesAndAllEndsSurviveInOrder() {
        val thread2 = red.copy(description = "Separate catalogue spool")
        val source = EmbPattern(listOf(
            machine(0, command = Command.JUMP), machine(0), machine(20),
            machine(20, command = Command.TRIM), machine(20, command = Command.STOP),
            machine(20, command = Command.COLOR_CHANGE), machine(21, command = Command.JUMP),
            machine(21), machine(41), machine(41, command = Command.END), machine(41, command = Command.END),
        ), listOf(red, thread2))
        val finished = GeneratedPatternProcessor.finish(source)
        assertEquals(source.threads, finished.threads)
        assertEquals(source.stitches.filter { it.command != Command.STITCH }, finished.stitches.filter { it.command != Command.STITCH })
        assertEquals(1, finished.trimCount)
        assertEquals(1, finished.stopCount)
        assertEquals(1, finished.colorChangeCount)
        assertEquals(2, finished.stitches.count { it.command == Command.END })
        assertPatternEquals(source, GeneratedPatternProcessor.stripGeneratedLocks(finished))
    }

    @Test fun explicitShortJumpRemainsNeedleUpAndBothSidesGetLocks() {
        val source = EmbPattern(listOf(machine(0, command = Command.JUMP), machine(0), machine(20), machine(21, command = Command.JUMP), machine(21), machine(41)), listOf(red))
        val finished = GeneratedPatternProcessor.finish(source)
        assertEquals(listOf(0, 21), finished.stitches.filter { it.command == Command.JUMP }.map { it.x })
        val boundary = finished.stitches.indexOf(machine(21, command = Command.JUMP))
        val locks = finished.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY]!!.split(',').map(String::toInt).toSet()
        assertTrue(boundary - 1 in locks)
        assertTrue(boundary + 1 in locks)
        assertEquals(Command.END, finished.stitches.last().command)
        assertPatternEquals(finished, GeneratedPatternProcessor.finish(finished, rebuild = true))
    }

    @Test fun nativeQuantizationDropsDuplicateLocksWithoutDroppingJumpAnchorsOrCommands() {
        val tinyLock = LockStitch { _, _ -> listOf(Stitch(0.0, 0.0, setOf("lock_stitch")), Stitch(0.01, 0.0, setOf("lock_stitch"))) }
        val group = StitchGroup(red, listOf(Stitch(0.0, 0.0), Stitch(Units.mmToPixels(2.0), 0.0)), trimAfter = true, stopAfter = true, lockStart = tinyLock)
        val finished = GeneratedPatternProcessor.fromGroups(listOf(group))
        assertNoConsecutiveDuplicateNeedles(finished)
        assertEquals(Command.JUMP, finished.stitches[0].command)
        assertEquals(Command.STITCH, finished.stitches[1].command)
        assertEquals(finished.stitches[0].x, finished.stitches[1].x)
        assertEquals(listOf(Command.TRIM, Command.STOP, Command.END), finished.stitches.takeLast(3).map { it.command })
        val locks = finished.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY]!!.split(',').map(String::toInt)
        assertEquals(listOf(1), locks)
    }

    @Test fun transformedAndComposedObjectsAreStrippedBeforeReplanning() {
        val originalFinished = GeneratedPatternProcessor.finish(raw)
        fun transform(pattern: EmbPattern) = pattern.copy(stitches = pattern.stitches.map { it.copy(x = -it.y + 100, y = it.x + 50) })
        val transformedClean = transform(GeneratedPatternProcessor.stripGeneratedLocks(originalFinished))
        val expected = GeneratedPatternProcessor.finish(transform(raw))
        assertPatternEquals(expected, GeneratedPatternProcessor.finish(transformedClean))
        val stripped = GeneratedPatternProcessor.stripGeneratedLocks(originalFinished)
        val shifted = stripped.copy(stitches = stripped.stitches.map { it.copy(x = it.x + 100) })
        val composed = raw.copy(stitches = stripped.stitches.dropLast(1) + shifted.stitches)
        val combined = GeneratedPatternProcessor.finish(composed)
        assertEquals(2, combined.jumpCount)
        assertPatternEquals(combined, GeneratedPatternProcessor.finish(combined, rebuild = true))
    }

    @Test fun unmarkedImportsAreNeverStrippedAndOnePointAndEmptyInputsAreSafe() {
        val foreign = raw.copy(metadata = raw.metadata + (GeneratedPatternProcessor.LOCK_INDICES_KEY to "1,2"))
        assertSame(foreign, GeneratedPatternProcessor.stripGeneratedLocks(foreign))
        val one = GeneratedPatternProcessor.finish(EmbPattern(listOf(machine(3)), listOf(red)))
        assertEquals(listOf(Command.JUMP, Command.STITCH, Command.END), one.stitches.map { it.command })
        assertEquals(3, one.stitches[1].x)
        assertTrue(GeneratedPatternProcessor.finish(EmbPattern(emptyList(), emptyList())).stitches.isEmpty())
        assertTrue(GeneratedPatternProcessor.fromGroups(emptyList()).stitches.isEmpty())
        for (end in listOf(0, 1)) {
            val degenerate = EmbPattern(listOf(machine(0, command = Command.JUMP), machine(0), machine(end), machine(end, command = Command.END)), listOf(red))
            val finished = GeneratedPatternProcessor.finish(degenerate)
            assertNoConsecutiveDuplicateNeedles(finished)
            assertPatternEquals(finished, GeneratedPatternProcessor.finish(finished, rebuild = true))
        }
    }

    @Test fun tracedAnchorsPreserveCustomAndBuiltinLockBodyGeometryDuringRebuild() {
        val first = Stitch(Units.mmToPixels(10.0), Units.mmToPixels(4.0))
        val last = Stitch(Units.mmToPixels(13.0), Units.mmToPixels(4.0))
        val shiftedCustom = InkLockStitch("custom", custom_path = "M 1 1 L2 1 L0 0 L1 0")
        for (lock in listOf(shiftedCustom, InkLockStitch("arrow"), InkLockStitch("bowtie"))) {
            val group = StitchGroup(red, listOf(first, last), trimAfter = true, stopAfter = true, lockStart = lock, lockEnd = lock)
            val generated = GeneratedPatternProcessor.fromGroups(listOf(group))
            assertTrue(generated.metadata[GeneratedPatternProcessor.RUN_ANCHORS_KEY]!!.isNotEmpty())
            val stripped = GeneratedPatternProcessor.stripGeneratedLocks(generated)
            val sewn = stripped.stitches.filter { it.command == Command.STITCH }
            assertEquals(listOf(machine(100, 40), machine(130, 40)), sewn)
            assertEquals(machine(100, 40, Command.JUMP), stripped.stitches.first())
            assertEquals(listOf(machine(130, 40, Command.TRIM), machine(130, 40, Command.STOP), machine(130, 40, Command.END)), stripped.stitches.takeLast(3))
            val replanned = GeneratedPatternProcessor.finish(generated, rebuild = true)
            assertPatternEquals(stripped, GeneratedPatternProcessor.stripGeneratedLocks(replanned))
        }
    }

    @Test fun customEndLockGeneratedColorChangesReturnToBodyEndButLegacyCommandPositionsStayExact() {
        val lock = InkLockStitch("custom", custom_path = "M1 1 L2 1 L0 0 L1 0")
        val groups = listOf(
            StitchGroup(red, listOf(Stitch(0.0, 0.0), Stitch(10 * Units.PIXELS_PER_MM, 0.0)), lockStart = lock, lockEnd = lock),
            StitchGroup(EmbThread(0xFF0000CC.toInt()), listOf(Stitch(12 * Units.PIXELS_PER_MM, 0.0), Stitch(14 * Units.PIXELS_PER_MM, 0.0)), lockStart = lock, lockEnd = lock),
        )
        val stripped = GeneratedPatternProcessor.stripGeneratedLocks(GeneratedPatternProcessor.fromGroups(groups))
        assertEquals(machine(100, command = Command.COLOR_CHANGE), stripped.stitches.single { it.command == Command.COLOR_CHANGE })
        val legacy = EmbPattern(listOf(machine(0, command = Command.JUMP), machine(0), machine(20), machine(28, command = Command.TRIM), machine(28, command = Command.END)), listOf(red))
        val legacyStripped = GeneratedPatternProcessor.stripGeneratedLocks(GeneratedPatternProcessor.finish(legacy))
        assertEquals(legacy.stitches.filter { it.command != Command.STITCH }, legacyStripped.stitches.filter { it.command != Command.STITCH })
        val filteredBoundary = listOf(
            StitchGroup(red, listOf(Stitch(0.0, 0.0), Stitch(10 * Units.PIXELS_PER_MM, 0.0)), forceLockStitches = true),
            StitchGroup(red, listOf(Stitch(10 * Units.PIXELS_PER_MM, 0.0), Stitch(14 * Units.PIXELS_PER_MM, 0.0))),
        )
        val merged = GeneratedPatternProcessor.stripGeneratedLocks(GeneratedPatternProcessor.fromGroups(filteredBoundary))
        assertEquals(listOf(0, 100, 140), merged.stitches.filter { it.command == Command.STITCH }.map { it.x })
        val trimmedBoundary = filteredBoundary.mapIndexed { index, group -> if (index == 0) group.copy(trimAfter = true) else group }
        val trimmed = GeneratedPatternProcessor.stripGeneratedLocks(GeneratedPatternProcessor.fromGroups(trimmedBoundary))
        assertEquals(machine(100, command = Command.TRIM), trimmed.stitches.single { it.command == Command.TRIM })
        assertEquals(machine(140, command = Command.END), trimmed.stitches.last())
        assertEquals(listOf(0, 100, 140), trimmed.stitches.filter { it.command == Command.STITCH }.map { it.x })
    }

    @Test fun legacyLocksFollowActualCornersAndShortRunsWithoutPositiveXFallback() {
        val points = listOf(machine(0, command = Command.JUMP), machine(0), machine(2), machine(2, 2), machine(2, 2, Command.END))
        val finished = GeneratedPatternProcessor.finish(EmbPattern(points, listOf(red)))
        val sewn = finished.stitches.filter { it.command == Command.STITCH }
        assertTrue(sewn.all { it.x in 0..2 && it.y in 0..2 && (it.y == 0 || it.x == 2) })
        assertTrue(finished.stitches.zipWithNext().all { (a, b) -> a.command != Command.STITCH || b.command != Command.STITCH || a.x == b.x || a.y == b.y })
        val shortBoundary = EmbPattern(listOf(machine(200, 198, Command.JUMP), machine(200, 198), machine(198, 198), machine(198, 198, Command.END)), listOf(red))
        assertTrue(GeneratedPatternProcessor.finish(shortBoundary).stitches.all { it.x in 198..200 && it.y == 198 })
        assertEquals(points, GeneratedPatternProcessor.stripGeneratedLocks(finished).stitches)
    }

    @Test fun legacyMaximumIsEnforcedAfterNativeQuantizationAndSurvivesRebuild() {
        val finished = GeneratedPatternProcessor.finish(raw, maxStitchLengthMm = 0.2)
        assertEquals("0.2", finished.metadata[GeneratedPatternProcessor.MAX_STITCH_LENGTH_MM_KEY])
        for ((a, b) in finished.stitches.zipWithNext()) if (b.command == Command.STITCH)
            assertTrue(kotlin.math.hypot(b.x.toDouble() - a.x, b.y.toDouble() - a.y) <= 2.0)
        assertNoConsecutiveDuplicateNeedles(finished)
        assertPatternEquals(finished, GeneratedPatternProcessor.finish(finished, rebuild = true))
        val tinyDiagonal = EmbPattern(listOf(machine(0, command = Command.JUMP), machine(0), machine(1, 1), machine(1, 1, Command.END)), listOf(red))
        val lattice = GeneratedPatternProcessor.finish(tinyDiagonal, maxStitchLengthMm = 0.1)
        assertTrue(lattice.stitches.zipWithNext().all { (a, b) -> b.command != Command.STITCH || kotlin.math.hypot(b.x.toDouble() - a.x, b.y.toDouble() - a.y) <= 1.0 })
        assertFailsWith<IllegalArgumentException> { GeneratedPatternProcessor.finish(raw, maxStitchLengthMm = 0.05) }
    }

    @Test fun exactGridTransformsPreserveOwnIndicesAndTransformAllAnchorMetadata() {
        val finished = GeneratedPatternProcessor.finish(raw, maxStitchLengthMm = 0.2)
        val mapped = GeneratedPatternProcessor.mapCoordinates(finished) { x, y -> -y + 100 to x - 60 }
        assertEquals(finished.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY], mapped.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY])
        assertEquals(finished.metadata[GeneratedPatternProcessor.MAX_STITCH_LENGTH_MM_KEY], mapped.metadata[GeneratedPatternProcessor.MAX_STITCH_LENGTH_MM_KEY])
        val stripped = GeneratedPatternProcessor.stripGeneratedLocks(finished)
        assertPatternEquals(GeneratedPatternProcessor.mapCoordinates(stripped) { x, y -> -y + 100 to x - 60 }, GeneratedPatternProcessor.stripGeneratedLocks(mapped))
        val lock = InkLockStitch("arrow")
        val source = GeneratedPatternProcessor.fromGroups(listOf(StitchGroup(red, listOf(Stitch(0.0, 0.0), Stitch(Units.mmToPixels(2.0), 0.0)), trimAfter = true, stopAfter = true, lockStart = lock, lockEnd = lock)))
        val mirrored = GeneratedPatternProcessor.mapCoordinates(source) { x, y -> -x + 50 to y + 20 }
        assertPatternEquals(GeneratedPatternProcessor.mapCoordinates(GeneratedPatternProcessor.stripGeneratedLocks(source)) { x, y -> -x + 50 to y + 20 }, GeneratedPatternProcessor.stripGeneratedLocks(mirrored))
    }
}
