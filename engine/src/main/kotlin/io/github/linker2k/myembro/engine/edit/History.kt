// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

/**
 * Bounded snapshot history. Values must be immutable. At most 100 undo steps are retained.
 * Start a drag with commit(firstPosition), then replaceTop(nextPosition) for each motion;
 * the complete drag is one undo step. A new commit or replacement clears redo.
 */
class History<T>(initial: T, val maxSteps: Int = 100) {
    init { require(maxSteps in 1..100) }
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    var current: T = initial
        private set
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val undoCount: Int get() = past.size
    val redoCount: Int get() = future.size

    fun commit(value: T): T {
        if (value == current) return current
        past.addLast(current)
        if (past.size > maxSteps) past.removeFirst()
        return replaceTop(value)
    }

    /** Replaces the current snapshot without adding an undo step. */
    fun replaceTop(value: T): T {
        if (value == current) return current
        current = value
        future.clear()
        return current
    }

    fun undo(): T {
        if (canUndo) {
            future.addLast(current)
            current = past.removeLast()
        }
        return current
    }

    fun redo(): T {
        if (canRedo) {
            past.addLast(current)
            current = future.removeLast()
        }
        return current
    }
}
