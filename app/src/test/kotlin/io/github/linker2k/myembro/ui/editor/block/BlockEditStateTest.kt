// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BlockEditStateTest {
    @Test fun tapPicksTheTopRegionAndMultiTapToggles() {
        val s = BlockEditState.sample()
        s.tap(0.50f, 0.33f, multi = false)   // center over petals 4/5
        assertEquals(setOf(6), s.selected)
        s.tap(0.30f, 0.35f, multi = true); assertEquals(setOf(6, 2), s.selected)
        s.tap(0.30f, 0.35f, multi = true); assertEquals(setOf(6), s.selected)
        s.tap(0.02f, 0.02f, multi = false); assertEquals(emptySet<Int>(), s.selected)
    }

    @Test fun applyToSeveralRegionsIsOneUndoStep() {
        val s = BlockEditState.sample()
        s.tap(0.30f, 0.35f, false); s.tap(0.70f, 0.35f, true)
        val before = s.regions
        s.apply(BlockStitchDraft(autoAngle = false, angleDeg = 45, density = BlockDensity.DENSE))
        assertEquals(1, s.undoSteps)
        assertEquals(listOf(45, 45), s.regions.filter { it.id in setOf(2, 3) }.map { it.stitch.angleDeg })
        s.undo(); assertEquals(before, s.regions); assertEquals(0, s.undoSteps)
    }

    @Test fun reorderMovesTheSelectionOnePlaceAsOneStep() {
        val s = BlockEditState.sample()
        s.tap(0.50f, 0.90f, false)           // stem, sewn first
        s.reorder(forward = true)
        assertEquals(listOf(2, 1), s.regions.take(2).map { it.id }); assertEquals(1, s.undoSteps)
        s.reorder(forward = false); s.reorder(forward = false)   // second one is already first: no step
        assertEquals(2, s.undoSteps); assertEquals(1, s.regions.first().id)
    }

    @Test fun angleBoundaries() {
        BlockStitchDraft(angleDeg = 0); BlockStitchDraft(angleDeg = 179)
        assertThrows(IllegalArgumentException::class.java) { BlockStitchDraft(angleDeg = 180) }
        assertThrows(IllegalArgumentException::class.java) { BlockStitchDraft(angleDeg = -1) }
    }
}
