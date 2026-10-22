// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class EditorThirdPassTest {
    private fun text(id: String = "text") = TextObject(id = id, text = "Hello", fontId = "offline-font",
        fontKind = FontKind.OUTLINE, heightMm = 18.3, spacing = 1.7, arcDeg = -63.5,
        contours = listOf(listOf(0.0 to 0.0, 10.0 to -20.0, 20.0 to 0.0)), contourSourceHeight = 20.0,
        transform = Transform(137.5, -66.2, 37.0, -1.2, 0.8), thread = EmbThread(0xFF123456.toInt(), "Custom"))
    private fun stitch(id: String) = StitchObject(id, id, EmbPattern(listOf(Stitch(0, 0, Command.STITCH)), emptyList()))

    @Test fun `text tool adds to current composition as one undoable action`() {
        val initial = Project(HoopSpec.H130x180, listOf(stitch("background")))
        val session = EditorSession(initial)
        val added = text()
        session.commit(session.project.withLettering(added, null))
        assertEquals(listOf("background", "text"), session.project.objects.map { it.id })
        assertEquals(initial.hoop, session.project.hoop)
        assertEquals(1, session.history.undoCount)
        assertTrue(session.stepHistory(redo = false))
        assertEquals(initial, session.project)
        assertTrue(session.stepHistory(redo = true))
        assertEquals(added, session.project.find("text"))
    }

    @Test fun `text edit replaces in place preserving position rotation mirror visibility and sewing order`() {
        val original = text().copy(visible = false)
        val initial = Project(objects = listOf(stitch("first"), original, stitch("last")))
        val incoming = text("new-id").copy(text = "New words", heightMm = 22.0, spacing = 2.1,
            transform = Transform(), thread = EmbThread(0xFF654321.toInt()))
        val next = initial.withLettering(incoming, original.id)
        val edited = next.find(original.id) as TextObject
        assertEquals(listOf("first", "text", "last"), next.objects.map { it.id })
        assertEquals("New words", edited.text)
        assertEquals(22.0, edited.heightMm)
        assertEquals(2.1, edited.spacing)
        assertEquals(incoming.thread, edited.thread)
        assertEquals(original.transform, edited.transform)
        assertFalse(edited.visible)
        assertSame(original, initial.find("text"))
        val session = EditorSession(initial)
        session.commit(next)
        assertTrue(session.stepHistory(false))
        assertSame(original, session.project.find("text"))
        assertTrue(session.stepHistory(true))
        val restored = ProjectJson.decode(ProjectJson.encode(session.project)).find("text") as TextObject
        assertEquals(edited, restored)
    }

    @Test fun `stale text edit never resurrects removed objects or replaces imported stitches`() {
        val initial = Project(objects = listOf(stitch("background")))
        assertSame(initial, initial.withLettering(text(), "deleted"))
        assertSame(initial, initial.withLettering(text(), "background"))
    }

    @Test fun `new text ID collision cannot overwrite existing layer`() {
        val original = text()
        val result = Project(objects = listOf(original)).withLettering(text(), null)
        assertEquals(2, result.objects.size)
        assertEquals(2, result.objects.map { it.id }.distinct().size)
        assertSame(original, result.find(original.id))
    }

    @Test fun `history result only permits haptics for successful available actions`() {
        val session = EditorSession()
        assertFalse(session.stepHistory(false))
        assertFalse(session.stepHistory(true))
        session.commit(Project(objects = listOf(text())))
        assertFalse(session.stepHistory(false, blocked = true))
        assertEquals(1, session.history.undoCount)
        assertTrue(session.stepHistory(false))
        assertEquals(0, session.history.undoCount)
        assertEquals(1, session.history.redoCount)
        assertFalse(session.stepHistory(false))
        assertFalse(session.stepHistory(true, blocked = true))
        assertTrue(session.stepHistory(true))
        assertFalse(session.stepHistory(true))
    }

    @Test fun `undo and redo toolbar commands cannot finish an active drag`() {
        val session = EditorSession(Project(objects = listOf(stitch("a"))))
        session.commit(session.project.add(text()))
        session.beginGesture()
        val moved = session.project.transformGroup(setOf("text"), 10.0, 20.0, 1.0, 0.0)
        session.updateGesture(moved)
        assertFalse(session.stepHistory(false))
        assertFalse(session.stepHistory(true))
        assertTrue(session.isGesturing)
        assertEquals(1, session.history.undoCount)
        session.endGesture()
        assertEquals(2, session.history.undoCount)
        assertTrue(session.stepHistory(false))
        assertTrue(session.stepHistory(true))
        assertEquals(moved, session.project)
    }

    @Test fun `fold breakpoint changes layout without changing editing state`() {
        val session = EditorSession(Project(objects = listOf(text())))
        session.commit(session.project.add(stitch("a")))
        val before = session.project
        for (width in listOf(360f, 599.9f, 600f, 720f, 1000f, 360f)) {
            val layout = editorLayout(width)
            assertEquals(width >= 600, layout.expanded)
            if (layout.expanded) assertTrue(width - layout.panelWidthDp - 96 > 200)
            assertSame(before, session.project)
            assertEquals(1, session.history.undoCount)
        }
    }
}
