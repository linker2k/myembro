// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.MyThreads
import io.github.linker2k.myembro.engine.io.PecThreads

/** The sheet owns this history; Cancel never touches the editor or its undo/redo stack. */
internal class EditorColorsDraft(val initial: Project) {
    private val history=History(initial)
    val project get()=history.current
    val canUndo get()=history.canUndo
    val canRedo get()=history.canRedo
    fun update(next: Project): Project=history.commit(next)
    fun undo(): Project=history.undo()
    fun redo(): Project=history.redo()
}

internal fun colorPanelThreads(owned: MyThreads) = PecThreads.SET.filterNotNull().distinctBy { it.color }
    .sortedBy { thread -> if(owned.active && owned.owned.any { it.sameColor(thread) }) 0 else 1 }
