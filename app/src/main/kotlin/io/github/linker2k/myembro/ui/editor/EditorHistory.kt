// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.Project

/** Temporary bounded snapshot history. A complete pointer gesture is a single undo step. */
internal class EditorHistory(initial: Project = Project(), private val limit: Int = 100) {
    var project: Project = initial
        private set
    private val past = ArrayDeque<Project>()
    private val future = ArrayDeque<Project>()
    private var gestureStart: Project? = null
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    fun beginGesture() {
        if (gestureStart == null) gestureStart = project
    }

    fun updateGesture(next: Project) {
        check(gestureStart != null)
        project = next
    }

    fun endGesture() {
        val before = gestureStart ?: return
        gestureStart = null
        if (before != project) remember(before)
    }

    fun commit(next: Project) {
        endGesture()
        if (project == next) return
        remember(project)
        project = next
    }

    private fun remember(before: Project) {
        past.addLast(before)
        while (past.size > limit) past.removeFirst()
        future.clear()
    }

    fun undo() {
        endGesture()
        if (past.isEmpty()) return
        future.addLast(project)
        project = past.removeLast()
    }

    fun redo() {
        endGesture()
        if (future.isEmpty()) return
        past.addLast(project)
        project = future.removeLast()
    }
}
