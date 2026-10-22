// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.History
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.exceedsHoop

/** Gesture previews stay outside engine History until pointer-up: one gesture, one commit. */
internal class EditorSession(initial: Project = Project(), limit: Int = 100) {
    val history = History(initial, limit)
    var project: Project = initial
        private set
    var isGesturing = false
        private set
    val showsHoopOverflow get() = !isGesturing && project.exceedsHoop()
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo
    fun beginGesture() { isGesturing = true }
    /** Pointer events can arrive after a toolbar action/navigation has ended the gesture. */
    fun updateGesture(next: Project): Boolean {
        if (!isGesturing) return false
        project = next
        return true
    }
    fun endGesture() {
        if (!isGesturing) return
        isGesturing = false
        history.commit(project)
    }
    fun commit(next: Project) { endGesture(); project = history.commit(next) }
    fun undo() { endGesture(); project = history.undo() }
    fun redo() { endGesture(); project = history.redo() }
}
