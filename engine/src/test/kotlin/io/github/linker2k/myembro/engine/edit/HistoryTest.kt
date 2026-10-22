// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import kotlin.test.*

class HistoryTest {
    @Test fun undoRedoAndBranch() {
        val h = History(0)
        assertEquals(0, h.undo()); assertEquals(0, h.redo())
        h.commit(1); h.commit(2)
        assertEquals(1, h.undo()); assertEquals(0, h.undo()); assertFalse(h.canUndo)
        assertEquals(1, h.redo()); assertEquals(2, h.redo()); assertFalse(h.canRedo)
        h.undo(); h.commit(9)
        assertFalse(h.canRedo); assertEquals(1, h.undo())
    }

    @Test fun dragIsOneStepAndNoOpsKeepRedo() {
        val h = History("initial")
        h.commit("drag1"); h.replaceTop("drag2"); h.replaceTop("drag3")
        assertEquals(1, h.undoCount)
        assertEquals("initial", h.undo())
        h.commit("initial"); h.replaceTop("initial")
        assertTrue(h.canRedo)
        assertEquals("drag3", h.redo())
        h.undo(); h.replaceTop("replacement"); assertFalse(h.canRedo)
    }

    @Test fun boundedHistoryAndValidation() {
        val h = History(0)
        (1..150).forEach { h.commit(it) }
        assertEquals(100, h.undoCount)
        repeat(100) { h.undo() }
        assertEquals(50, h.current)
        assertEquals(100, h.redoCount)
        repeat(100) { h.redo() }
        assertEquals(150, h.current)
        val small = History(0, 2)
        (1..4).forEach { small.commit(it) }
        repeat(3) { small.undo() }
        assertEquals(2, small.current)
        assertFailsWith<IllegalArgumentException> { History(0, 0) }
        assertFailsWith<IllegalArgumentException> { History(0, 101) }
    }
}
