// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.*
import kotlin.test.*

class EditorCommandSessionTest {
    private fun initial() = Project(objects=listOf(TextObject(id="t",text="A")))
    @Test fun `toolbar commands share reducer and undo restores original`() {
        val p=initial();val session=EditorSession(p)
        val command=ProjectCommand.SetTransforms("t",Transform(x=40.0,scaleX=-1.0))
        val expected=assertIs<CommandResult.Applied>(apply(p,command)).project
        assertIs<CommandResult.Applied>(session.commit(command))
        assertEquals(expected,session.project);assertEquals(1,session.history.undoCount)
        session.undo();assertSame(p,session.project)
        session.redo();assertEquals(expected,session.project)
    }
    @Test fun `rejected and unchanged commands retain redo and project`() {
        val p=initial();val session=EditorSession(p)
        session.commit(ProjectCommand.Remove(setOf("t")));session.undo()
        val before=session.history
        assertIs<CommandResult.Rejected>(session.commit(ProjectCommand.Remove(setOf("missing"))))
        assertEquals(before,session.history);assertSame(p,session.project)
        assertFalse(assertIs<CommandResult.Applied>(session.commit(ProjectCommand.SetVisible("t",true))).changed)
        assertEquals(before,session.history)
    }
    @Test fun `invalid command batch is rejected by same validation as tools`() {
        val p=initial();val session=EditorSession(p)
        val command=ProjectCommand.Batch(listOf(ProjectCommand.SetVisible("t",false),
            ProjectCommand.SetTransforms(mapOf("t" to TransformValues(scaleX=0.0)))))
        val result=assertIs<CommandResult.Rejected>(session.commit(command))
        assertEquals(CommandError.INVALID_TRANSFORM,result.code);assertEquals(listOf(1),result.path)
        assertSame(p,session.project);assertEquals(0,session.history.undoCount)
    }
    @Test fun `mixed reorder transform preview commits one shared command batch`() {
        val p=initial().add(TextObject(id="u",text="B"));val session=EditorSession(p)
        val preview=p.reorder("t",1).rotateBy("u",90.0)
        session.beginGesture();session.updateGesture(preview);session.endGesture()
        assertSame(preview,session.project);assertEquals(1,session.history.undoCount)
        session.undo();assertSame(p,session.project)
    }
}
