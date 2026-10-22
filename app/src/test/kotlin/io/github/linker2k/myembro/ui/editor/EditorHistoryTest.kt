// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.engine.edit.Transform
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.Stitch
import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.io.PesWriter
import kotlin.test.*

class EditorHistoryTest {
    private fun obj(id: String = "a") = StitchObject(id, id, EmbPattern(listOf(
        Stitch(-100, -50, Command.STITCH), Stitch(100, 50, Command.STITCH),
        Stitch(100, 50, Command.END)), emptyList()))

    @Test fun `one undo restores all frames in a gesture and redo restores the final frame`() {
        val initial = Project(objects = listOf(obj()))
        val h = EditorHistory(initial)
        h.beginGesture()
        repeat(60) { frame ->
            h.updateGesture(initial.update("a") { it.withTransform(Transform(x = frame.toDouble())) })
        }
        h.endGesture()
        val final = h.project
        assertTrue(h.canUndo)
        h.undo()
        assertEquals(initial, h.project)
        assertFalse(h.canUndo)
        h.redo()
        assertEquals(final, h.project)
    }

    @Test fun `new edit after undo clears redo but tap and no-op do not`() {
        val h = EditorHistory()
        val added = Project(objects = listOf(obj()))
        h.commit(added)
        h.undo()
        h.beginGesture(); h.endGesture()
        h.commit(h.project)
        assertTrue(h.canRedo)
        h.commit(Project(hoop = HoopSpec.H130x180))
        assertFalse(h.canRedo)
    }

    @Test fun `history is bounded and layer reorder is one transaction`() {
        val initial = Project(objects = listOf(obj("a"), obj("b"), obj("c")))
        val h = EditorHistory(initial, limit = 2)
        h.beginGesture()
        h.updateGesture(h.project.reorder("a", 1))
        h.updateGesture(h.project.reorder("a", 2))
        h.endGesture()
        assertEquals(listOf("b", "c", "a"), h.project.objects.map { it.id })
        h.undo()
        assertEquals(initial, h.project)
        repeat(3) { h.commit(h.project.copy(hoop = HoopSpec("$it", 1000 + it, 1000))) }
        h.undo(); h.undo()
        assertFalse(h.canUndo)
    }

    @Test fun `hit testing follows rotation and mirror and hoop checks use position`() {
        val rotated = obj().withTransform(Transform(x = 200.0, y = 100.0, rotationDeg = 90.0, scaleX = -2.0))
        assertTrue(rotated.contains(200.0, 250.0, 0.0))
        assertFalse(rotated.contains(350.0, 100.0, 0.0))
        assertFalse(rotated.placedPattern().bounds!!.outside(HoopSpec.H100))
        val moved = rotated.withTransform(rotated.transform.copy(x = 500.0))
        assertTrue(moved.placedPattern().bounds!!.outside(HoopSpec.H100))
    }

    @Test fun `scaling preserves mirroring clamps extremes and ignores invalid factors`() {
        val t = Transform(scaleX = -1.0, scaleY = 2.0)
        assertEquals(t, t.scaled(Double.NaN))
        assertEquals(t, t.scaled(-1.0))
        assertEquals(-10.0, t.scaled(1e8).scaleX)
        assertEquals(20.0, t.scaled(1e8).scaleY)
        assertEquals(-0.05, t.scaled(1e-8).scaleX)
    }

    @Test fun `visible transformed composition exports as a readable PES`() {
        val project = Project(objects = listOf(obj(),
            obj("b").withTransform(Transform(x = 300.0)), obj("hidden").withVisible(false)))
        val roundTrip = PatternReader.read(PesWriter.write(project.flatten()), "composition.pes")
        assertTrue(roundTrip.stitchCount >= 4)
        assertEquals(500, roundTrip.bounds!!.width)
    }
}
