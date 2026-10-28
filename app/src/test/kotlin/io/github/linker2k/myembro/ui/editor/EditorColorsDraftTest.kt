// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.MyThreads
import io.github.linker2k.myembro.engine.io.PecThreads
import kotlin.test.*

class EditorColorsDraftTest {
    @Test fun cancelHasNoEffectAndApplyIsOneUndoStep() {
        val initial=Project()
        val editor=EditorSession(initial)
        val draft=EditorColorsDraft(initial)
        draft.update(initial.copy(fabricColorArgb=0xff111111.toInt()))
        draft.update(initial.copy(fabricColorArgb=0xff222222.toInt()))
        assertEquals(0,editor.history.undoCount);assertEquals(initial,editor.project)
        assertEquals(0xff111111.toInt(),draft.undo().fabricColorArgb)
        assertEquals(0xff222222.toInt(),draft.redo().fabricColorArgb)
        // Cancel means discarding the draft. Applying commits just its final value.
        editor.commit(draft.project)
        assertEquals(1,editor.history.undoCount)
        editor.undo();assertEquals(initial,editor.project)
        editor.redo();assertEquals(draft.project,editor.project)
    }
    @Test fun ownedThreadsComeFirstWithoutHidingTheBrotherCatalog() {
        val list=colorPanelThreads(MyThreads(true,setOf(5,8)))
        assertEquals(setOf(PecThreads.forIndex(5).color,PecThreads.forIndex(8).color),list.take(2).map { it.color }.toSet())
        assertEquals(colorPanelThreads(MyThreads()).map { it.color }.toSet(),list.map { it.color }.toSet())
    }
}
