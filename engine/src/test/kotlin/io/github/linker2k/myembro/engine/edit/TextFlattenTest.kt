// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.io.PecWriter
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class TextFlattenTest {
    @Test fun importedHersheyAndOutlineWithSameThreadJoinWithoutColorChanges() {
        val imported = rectangle()
        val text = TextObject("text", text = "AB", thread = blue, transform = Transform(200.0, 100.0))
        val outline = outlineText().copy(transform = Transform(-200.0, -100.0, 45.0))
        val p = Project(objects = listOf(imported, text, outline))
        val flat = p.flatten()
        assertEquals(listOf(blue), flat.threads)
        assertEquals(0, flat.colorChangeCount)
        assertEquals(p.objects.sumOf { it.localPattern().stitchCount }, flat.stitchCount)
        assertEquals(p.objects.sumOf { it.localPattern().trimCount } + 2, flat.trimCount)
        assertEquals(p.objects.flatMap { it.placedPattern().stitches.filter { s -> s.command == Command.STITCH } },
            flat.stitches.filter { it.command == Command.STITCH })
        assertBoundary(imported, text, changesColor = false)
        assertBoundary(text, outline, changesColor = false)
        assertPesRoundTrip(flat)
    }

    @Test fun differentThreadsAndCatalogIdentitiesChangeColorAtTextBoundaries() {
        val text = TextObject("text", text = "A", thread = blue)
        val differentColor = outlineText(thread = red)
        val sameRgbDifferentCatalog = differentColor.copy(id = "catalog", thread = red.copy(catalogNumber = "other"))
        val p = Project(objects = listOf(text, differentColor, sameRgbDifferentCatalog))
        val flat = p.flatten()
        assertEquals(listOf(blue, red, red.copy(catalogNumber = "other")), flat.threads)
        assertEquals(2, flat.colorChangeCount)
        assertEquals(p.objects.sumOf { it.localPattern().trimCount } + 2, flat.trimCount)
        assertBoundary(text, differentColor, changesColor = true)
        assertBoundary(differentColor, sameRgbDifferentCatalog, changesColor = true)
        assertPesRoundTrip(flat)
    }

    @Test fun invisibleAndEmptyLetteringDoesNotInsertTrimsOrColors() {
        val a = TextObject("a", text = "I", thread = blue)
        val b = outlineText("b").copy(thread = blue, transform = Transform(100.0))
        val skipped = listOf(
            TextObject("empty", thread = red), TextObject("spaces", text = " \n\t ", thread = red),
            TextObject("hidden", text = "AB", thread = red, visible = false),
            outlineText("empty-outline").copy(contours = emptyList(), thread = red),
        )
        val expected = Project(objects = listOf(a, b)).flatten()
        val actual = Project(objects = listOf(a) + skipped + b).flatten()
        assertEquals(expected.stitches, actual.stitches)
        assertEquals(expected.threads, actual.threads)
        assertEquals(listOf(Stitch(0, 0, Command.END)), Project(objects = skipped).flatten().stitches)
        assertPesRoundTrip(actual)
    }

    @Test fun lastImportedColorDeterminesWhetherTextNeedsAThreadChange() {
        val imported = StitchObject("imported", "two colors", EmbPattern(listOf(
            Stitch(0, 0, Command.STITCH), Stitch(0, 0, Command.COLOR_CHANGE),
            Stitch(40, 40, Command.STITCH), Stitch(40, 40, Command.END),
        ), listOf(red, blue)))
        val text = TextObject("text", text = "Sew!", thread = blue)
        val p = Project(objects = listOf(imported, text, outlineText(thread = red)))
        assertEquals(listOf(red, blue, red), p.flatten().threads)
        assertEquals(2, p.flatten().colorChangeCount)
        assertBoundary(imported, text, changesColor = false)
        assertPesRoundTrip(p.flatten())
    }

    private fun assertBoundary(first: DesignObject, second: DesignObject, changesColor: Boolean) {
        val firstFlat = Project(objects = listOf(first)).flatten()
        val flat = Project(objects = listOf(first, second)).flatten()
        val boundary = firstFlat.stitches.lastIndex
        val end = firstFlat.stitches[boundary - 1]
        assertEquals(Stitch(end.x, end.y, Command.TRIM), flat.stitches[boundary])
        var index = boundary + 1
        if (changesColor) {
            assertEquals(Stitch(end.x, end.y, Command.COLOR_CHANGE), flat.stitches[index++])
        }
        assertEquals(Command.JUMP, flat.stitches[index].command)
        assertEquals(1, flat.stitches.count { it.command == Command.END })
    }

    private fun assertPesRoundTrip(flat: EmbPattern) {
        val back = PesReader.read(PesWriter.write(flat))
        val normalized = PecWriter.normalize(flat)
        assertTrue(back.warnings.isEmpty())
        assertEquals(normalized.stitches, back.stitches)
        assertEquals(normalized.threads, back.threads)
        assertEquals(flat.stitchCount, back.stitchCount)
        assertEquals(flat.colorChangeCount, back.colorChangeCount)
    }
}
