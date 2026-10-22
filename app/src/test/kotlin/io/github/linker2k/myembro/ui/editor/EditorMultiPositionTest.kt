// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class EditorMultiPositionTest {
    // Synthetic positions exercise UI geometry, not SA434 hardware specifications.
    private val frame = HoopSpec("test frame", 1000, 1700)
    private val positions = listOf(SewingPosition("top", "Top", 0, -350), SewingPosition("bottom", "Bottom", 0, 350))
    private fun obj(id: String, vararg coordinates: Pair<Int, Int>) = StitchObject(id, id,
        EmbPattern(coordinates.map { Stitch(it.first, it.second, Command.STITCH) }, emptyList()))

    @Test fun `confirmed windows fit coverage and duplicates outside origins or absent positions are rejected`() {
        assertTrue(validEditorPositions(frame, positions))
        assertFalse(validEditorPositions(frame, emptyList()))
        assertFalse(validEditorPositions(frame, positions + positions.first()))
        assertFalse(validEditorPositions(frame, listOf(SewingPosition("bad", "Bad", 0, 351))))
        assertFalse(validEditorPositions(frame, listOf(SewingPosition("bad", "Bad", Int.MAX_VALUE, 0))))
    }
    @Test fun `objects inside one position or overlapping positions do not warn`() {
        val p = Project(frame, listOf(obj("a", -20 to -20, 20 to 20).copy(transform = Transform(y = -600.0)),
            obj("overlap", -10 to -10, 10 to 10)))
        assertEquals(EditorPositionFit(emptySet(), emptySet()), p.positionFit(positions))
    }
    @Test fun `object spanning positions needs split while unassignable needles warn separately`() {
        val p = Project(frame, listOf(obj("span", 0 to -700, 0 to 700),
            obj("outside", -20 to 0, 20 to 0).copy(transform = Transform(x = 520.0)),
            obj("hidden", -20 to 0, 20 to 0).copy(transform = Transform(y = 900.0), visible = false)))
        val fit = p.positionFit(positions)
        assertEquals(setOf("span"), fit.spanningIds)
        assertEquals(setOf("outside"), fit.outsideIds)
    }
    @Test fun `position warning follows rounded transformed needle coordinates and ignores post END data`() {
        val ended = StitchObject("ended", "ended", EmbPattern(listOf(Stitch(0,0,Command.STITCH),
            Stitch(0,0,Command.END), Stitch(9999,9999,Command.STITCH)), emptyList()), Transform(x = 4999.0, y = 4999.0))
        val p = Project(frame, listOf(obj("edge", 0 to 0).copy(transform = Transform(x = 500.4)), ended))
        assertTrue("edge" !in p.positionFit(positions).outsideIds)
        assertTrue("ended" !in p.positionFit(positions).outsideIds)
        val moved = p.update("edge") { it.withTransform(Transform(x = 501.0)) }
        assertTrue("edge" in moved.positionFit(positions).outsideIds)
    }
    @Test fun `split preview conserves stitches and each position passes machine preflight`() {
        val p = Project(frame, listOf(obj("span", 0 to -700, 0 to 700)))
        val result = MultiPositionSplitter.split(p, positions)
        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.parts.sumOf { it.stitchCount })
        val settings = io.github.linker2k.myembro.ui.settings.EmbroiderySettings()
        result.parts.forEach { part ->
            assertTrue(io.github.linker2k.myembro.export.ExportChecks.check(part.pattern, "position${part.ordinal}.pes", settings).isEmpty())
        }
    }
}
