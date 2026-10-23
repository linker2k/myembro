// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class EditorAppearanceTest {
    private val original = EmbThread(0xFF123456.toInt(), "Original", "1", "brand")
    private val replacement = EmbThread(0xFF654321.toInt(), "New", "2", "brand")
    private fun obj() = StitchObject("a", "sample", EmbPattern(listOf(
        Stitch(0, 0, Command.STITCH), Stitch(200, 100, Command.STITCH),
        Stitch(200, 100, Command.END)), listOf(original)),
        transform = Transform(scaleX = -1.0), sourceBytes = byteArrayOf(1, 2, 3))

    @Test fun `millimeter fields preserve mirror and compute absolute size independently of rotation`() {
        val t = inspectorTransform(obj(), "40", "5", "-3,5", "2.75", "90")!!
        assertEquals(-2.0, t.scaleX)
        assertEquals(0.5, t.scaleY)
        assertEquals(-35.0, t.x)
        assertEquals(27.5, t.y)
        assertEquals(90.0, t.rotationDeg)
    }

    @Test fun `invalid and excessive dimensions never create zero or nonfinite transforms`() {
        for (bad in listOf("0", "-1", "NaN", "Infinity", "", "1000000"))
            assertNull(inspectorTransform(obj(), bad, "10", "0", "0", "0"))
    }

    @Test fun `thread edits preserve commands placement and metadata and are undoable`() {
        val a = obj()
        val recolored = a.recolored(0, replacement) as StitchObject
        assertEquals(listOf(replacement), recolored.source.threads)
        assertEquals(a.source.stitches, recolored.source.stitches)
        assertEquals(a.transform, recolored.transform)
        assertNull(recolored.sourceBytes)
        assertTrue(recolored.sourceThreadsEdited)
        assertContentEquals(byteArrayOf(1, 2, 3), a.sourceBytes)
        val session = EditorSession(Project(objects = listOf(a)))
        session.commit(session.project.replace(recolored))
        session.undo()
        assertSame(a, session.project.find("a"))
        session.redo()
        val restored = ProjectJson.decode(ProjectJson.encode(session.project))
        assertEquals(replacement, restored.flatten().threads.single())
    }

    @Test fun `text colors remain editable text rather than becoming imported stitches`() {
        val text = TextObject(id = "t", text = "A", thread = original)
        val changed = text.recolored(0, replacement) as TextObject
        assertEquals(text.text, changed.text)
        assertEquals(text.fontId, changed.fontId)
        assertEquals(replacement, changed.thread)
        assertSame(text, text.recolored(1, replacement))
    }

    @Test fun `invalid thread index leaves the design intact`() {
        val a = obj()
        assertSame(a, a.recolored(0, original))
        assertSame(a, a.recolored(-1, replacement))
        assertSame(a, a.recolored(2, replacement))
    }

    @Test fun `custom RGB input is validated and made opaque`() {
        assertEquals(0xFFB5492A.toInt(), parseThreadHex(" #b5492a "))
        for (bad in listOf("12345", "1234567", "GG0011", "", "FF123456")) assertNull(parseThreadHex(bad))
    }
}
