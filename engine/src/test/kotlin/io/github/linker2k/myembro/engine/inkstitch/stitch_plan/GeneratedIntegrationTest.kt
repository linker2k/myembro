// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import kotlin.math.hypot
import kotlin.test.*

class GeneratedIntegrationTest {
    private fun assertFinished(pattern: EmbPattern) {
        assertEquals("v1", pattern.metadata[GeneratedPatternProcessor.FINISHED_KEY])
        assertFalse(pattern.stitches.zipWithNext().any { (a, b) -> a.command == Command.STITCH && b.command == Command.STITCH && a.x == b.x && a.y == b.y })
        assertEquals(Command.END, pattern.stitches.last().command)
        assertTrue(pattern.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY].orEmpty().isNotEmpty())
        assertSame(pattern, GeneratedPatternProcessor.finish(pattern))
    }

    @Test fun existingLetteringRunningBeanAndFilledOutlinesShareTheProcessor() {
        for (style in listOf(StitchStyle.RUNNING, StitchStyle.BEAN, StitchStyle.SATIN)) {
            assertFinished(LetteringEngine.generate("AB", heightMm = 12.0, stitchStyle = style))
            assertFinished(LetteringEngine.fromContours(listOf(listOf(0.0 to 0.0, 8.0 to 0.0, 8.0 to 8.0, 0.0 to 8.0, 0.0 to 0.0)),
                heightMm = 8.0, sourceHeight = 8.0, stitchStyle = style))
        }
        assertFinished(OutlineFill.generate(listOf(listOf(0.0 to 0.0, 8.0 to 0.0, 8.0 to 8.0, 0.0 to 8.0, 0.0 to 0.0)),
            FillOptions(underlay = false)))
        assertFinished(SatinColumn.generate(listOf(0.0 to 0.0, 0.0 to 8.0), listOf(2.0 to 0.0, 2.0 to 8.0)))
    }

    @Test fun legacyDigitizingAndProjectCompositionKeepExplicitJumpsAndDoNotAccumulateLocks() {
        val digitized = io.github.linker2k.myembro.engine.digitizing.RunningStitch.generate(listOf(
            Polyline(listOf(Point(0.0, 0.0), Point(100.0, 0.0))), Polyline(listOf(Point(0.0, 50.0), Point(100.0, 50.0)))))
        assertFinished(digitized)
        assertEquals(2, digitized.jumpCount)
        assertEquals(1, digitized.trimCount)
        val project = Project(objects = listOf(TextObject("a", text = "A"), TextObject("b", text = "B", transform = Transform(x = 200.0))))
        val first = project.flatten()
        // Composition preserves each already finished object, rather than locking its
        // sewing again. An unmarked imported object in the same project stays unchanged.
        assertEquals(project.objects.sumOf { it.placedPattern().stitchCount }, first.stitchCount)
        assertEquals(project.objects.flatMap { it.placedPattern().stitches.filter { s -> s.command == Command.STITCH } },
            first.stitches.filter { it.command == Command.STITCH })
        assertEquals(first.stitches, project.flatten().stitches)
    }

    @Test fun transformedTextRebuildsPhysicalLocksAfterScalingInsteadOfScalingOldLocks() {
        val text = TextObject("text", text = "I", heightMm = 10.0, stitchStyle = StitchStyle.RUNNING,
            transform = Transform(scaleX = 3.0, scaleY = 3.0))
        val placed = text.placedPattern()
        assertFinished(placed)
        val indices = placed.metadata.getValue(GeneratedPatternProcessor.LOCK_INDICES_KEY).split(',').map(String::toInt)
        val first = placed.stitches[indices[0]]
        val second = placed.stitches[indices[1]]
        val lengthMm = hypot((second.x - first.x).toDouble(), (second.y - first.y).toDouble()) / 10
        assertTrue(lengthMm in 0.5..1.0, "Lock length after 3x scaling: $lengthMm mm")
    }
}
