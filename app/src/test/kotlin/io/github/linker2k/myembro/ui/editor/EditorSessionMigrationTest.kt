// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

/** Characterization tests added and run against the old session before engine delegation. */
class EditorSessionMigrationTest {
    private fun project(id: String = "a") = Project(objects=listOf(StitchObject(id,id,
        EmbPattern(listOf(Stitch(-10,-10,Command.STITCH),Stitch(10,10,Command.STITCH)),emptyList()))))

    @Test fun `all preview frames create exactly one step only on end and repeated end is a no-op`() {
        val initial=project();val session=EditorSession(initial)
        session.beginGesture()
        repeat(60) { i ->
            session.updateGesture(initial.update("a") { it.withTransform(Transform(x=(i+1).toDouble())) })
            assertEquals(0,session.history.undoCount);assertEquals(0,session.history.redoCount)
            assertFalse(session.canUndo)
        }
        val preview=session.project
        assertFalse(session.stepHistory(redo=false));assertTrue(session.isGesturing)
        session.endGesture();session.endGesture()
        assertSame(preview,session.project);assertEquals(1,session.history.undoCount);assertTrue(session.canUndo)
        assertTrue(session.stepHistory(redo=false));assertSame(initial,session.project)
        assertEquals(0,session.history.undoCount);assertEquals(1,session.history.redoCount)
        assertTrue(session.stepHistory(redo=true));assertSame(preview,session.project)
        assertEquals(1,session.history.undoCount);assertEquals(0,session.history.redoCount)
    }
    @Test fun `gesture that returns to equal baseline preserves redo counts and preview identity`() {
        val initial=project();val session=EditorSession(initial)
        session.commit(initial.rotateBy("a",90.0));session.undo()
        assertEquals(1,session.history.redoCount)
        session.beginGesture()
        assertTrue(session.canRedo) // UI independently disables interaction while gesturing.
        session.updateGesture(initial.rotateBy("a",45.0))
        val equalPreview=initial.copy(objects=initial.objects.toList())
        session.updateGesture(equalPreview)
        assertSame(equalPreview,session.project)
        assertFalse(session.stepHistory(redo=true));assertTrue(session.isGesturing)
        session.endGesture()
        assertSame(equalPreview,session.project)
        assertEquals(0,session.history.undoCount);assertEquals(1,session.history.redoCount)
        assertFalse(session.canUndo);assertTrue(session.canRedo)
        session.beginGesture();session.endGesture() // Empty gesture also preserves redo.
        assertEquals(0,session.history.undoCount);assertEquals(1,session.history.redoCount)
    }
    @Test fun `successful document replacement creates fresh history and clears redo and dirty draft`() {
        val documents=EditorDocuments();val initial=project()
        documents.replaceAfterRead(true) {initial}
        val old=documents.session
        old.commit(initial.rotateBy("a",90.0));old.undo()
        assertEquals(1,old.history.redoCount);assertFalse(documents.dirty);assertNull(documents.draft)
        val loaded=project("new")
        documents.replaceAfterRead(true) {loaded}
        assertNotSame(old,documents.session);assertSame(loaded,documents.session.project)
        assertEquals(0,documents.session.history.undoCount);assertEquals(0,documents.session.history.redoCount)
        assertFalse(documents.session.canUndo);assertFalse(documents.session.canRedo)
        assertFalse(documents.dirty);assertNull(documents.draft)
        assertEquals(1,old.history.redoCount) // Old state was replaced, not destructively cleared.
    }
    @Test fun `failed document read retains redo while recovery replacement starts dirty without history`() {
        val documents=EditorDocuments();val initial=project()
        documents.replaceAfterRead(true) {initial}
        val old=documents.session
        old.commit(initial.rotateBy("a",90.0));old.undo()
        assertFailsWith<IllegalArgumentException> {documents.replaceAfterRead(true) {errorRead()}}
        assertSame(old,documents.session);assertSame(initial,old.project)
        assertEquals(0,old.history.undoCount);assertEquals(1,old.history.redoCount)
        assertFalse(documents.dirty);assertNull(documents.draft)
        val recovered=project("recovered")
        documents.replaceAfterRead(false,restoredDraft=true) {recovered}
        assertSame(recovered,documents.session.project);assertTrue(documents.dirty);assertSame(recovered,documents.draft)
        assertEquals(0,documents.session.history.undoCount);assertEquals(0,documents.session.history.redoCount)
    }
    private fun errorRead(): Project = throw IllegalArgumentException("bad project")
}
