// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.io.PecWriter
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class FlattenTest {
    private fun empty(id: String) = StitchObject(id, id, EmbPattern(listOf(Stitch(0, 0, Command.END)), listOf(red)))

    @Test fun adjacentSameThreadGetsTrimAndJumpButNoColorChange() {
        val p = Project(objects = listOf(rectangle(), empty("empty"), rectangle("b", 200.0)))
        val flat = p.flatten()
        assertEquals(4, flat.stitchCount)
        assertEquals(listOf(blue), flat.threads)
        assertEquals(0, flat.colorChangeCount)
        assertEquals(1, flat.trimCount)
        val trim = flat.stitches.indexOfFirst { it.command == Command.TRIM }
        assertEquals(Command.JUMP, flat.stitches[trim + 1].command)
        assertEquals(Command.END, flat.stitches.last().command)
        assertEquals(1, flat.stitches.count { it.command == Command.END })
        assertRoundTrip(flat)
    }

    @Test fun threadIdentityAndLastBlockControlBoundaryChange() {
        val pattern = EmbPattern(listOf(
            Stitch(-10, -10, Command.STITCH), Stitch(0, 0, Command.COLOR_CHANGE),
            Stitch(10, 10, Command.STITCH), Stitch(10, 10, Command.END),
        ), listOf(red, blue))
        val a = StitchObject("a", "two color", pattern)
        val b = rectangle("b", 200.0)
        val c = rectangle("c", -200.0).copy(source = EmbPattern(b.source.stitches, listOf(blue.copy(catalogNumber = "different"))))
        val flat = Project(objects = listOf(a, b, c)).flatten()
        assertEquals(listOf(red, blue, blue.copy(catalogNumber = "different")), flat.threads)
        assertEquals(2, flat.colorChangeCount)
        assertEquals(3, flat.trimCount)
        assertEquals(6, flat.stitchCount)
        assertRoundTrip(flat)
    }

    @Test fun emptyHiddenAndPostEndObjectsDoNotSew() {
        val jumpOnly = StitchObject("jump", "jump", EmbPattern(listOf(Stitch(5, 5, Command.JUMP)), emptyList()))
        val noObjects = listOf(Project(), Project(objects = listOf(empty("e"), jumpOnly, rectangle().copy(visible = false))))
        for (project in noObjects) {
            assertEquals(listOf(Stitch(0, 0, Command.END)), project.flatten().stitches)
            assertTrue(project.flatten().threads.isEmpty())
            val back = PesReader.read(io.github.linker2k.myembro.engine.PolicyFixtureWriter.write(project.flatten()))
            assertEquals(0, back.stitchCount)
            assertTrue(back.warnings.isEmpty())
        }
        val ended = rectangle().copy(source = EmbPattern(listOf(Stitch(0, 0, Command.END), Stitch(100, 100, Command.STITCH)), listOf(blue)))
        assertEquals(0, Project(objects = listOf(ended)).flatten().stitchCount)
    }

    @Test fun emptyColorBlocksAndStopsArePreservedCorrectly() {
        val source = EmbPattern(listOf(
            Stitch(0, 0, Command.COLOR_CHANGE), // first thread never sews
            Stitch(10, 10, Command.STITCH), Stitch(10, 10, Command.STOP),
            Stitch(20, 20, Command.STITCH), Stitch(20, 20, Command.TRIM),
            Stitch(20, 20, Command.END),
        ), listOf(red, blue))
        val flat = Project(objects = listOf(StitchObject("a", "stop", source), rectangle("b", 300.0))).flatten()
        assertEquals(listOf(blue), flat.threads)
        assertEquals(0, flat.colorChangeCount)
        assertEquals(1, flat.stopCount)
        assertRoundTrip(flat)
    }

    @Test fun transformedFixtureSurvivesPesWriterReaderRoundTrip() {
        val bytes = requireNotNull(javaClass.getResource("/fixtures/flower.v6.pes")).readBytes()
        val obj = StitchObject.fromBytes("flower", "flower", bytes, "flower.pes")
            .copy(transform = Transform(100.0, -200.0, 90.0, -0.9, 1.1))
        assertRoundTrip(Project(objects = listOf(obj, obj.copy(id = "flower2", transform = Transform(-300.0, 200.0)))).flatten())
    }

    private fun assertRoundTrip(flat: EmbPattern) {
        // PES v1 maps to the PEC palette and represents trims as trim+jump; compare the
        // documented writer normalization as well as every needle-down position and STOP.
        val normalized = PecWriter.normalizeForPes(io.github.linker2k.myembro.engine.io.PesWriter.prepareForOutput(flat))
        val back = PesReader.read(io.github.linker2k.myembro.engine.PolicyFixtureWriter.write(flat))
        assertTrue(back.warnings.isEmpty())
        assertEquals(normalized.stitches, back.stitches)
        assertEquals(normalized.threads, back.threads)
        assertEquals(io.github.linker2k.myembro.engine.io.PesWriter.prepareForOutput(flat).stitches.filter { it.command == Command.STITCH }, back.stitches.filter { it.command == Command.STITCH })
        assertEquals(normalized.colorChangeCount, back.colorChangeCount)
        assertEquals(normalized.stopCount, back.stopCount)
    }
}
