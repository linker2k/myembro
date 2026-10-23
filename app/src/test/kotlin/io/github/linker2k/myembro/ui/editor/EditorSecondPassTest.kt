// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import java.nio.file.Files
import kotlin.test.*

class EditorSecondPassTest {
    private fun obj(id: String, x: Double = 0.0, y: Double = 0.0, visible: Boolean = true) = StitchObject(
        id, id, EmbPattern(listOf(Stitch(-10, -10, Command.STITCH), Stitch(10, 10, Command.STITCH),
            Stitch(10, 10, Command.END)), listOf(EmbThread(0xFF123456.toInt(), "Custom"))),
        Transform(x = x, y = y), visible)

    @Test fun `closed gesture ignores late pointer moves after save undo and redo`() {
        val initial = Project(objects = listOf(obj("a")))
        val moved = initial.transformGroup(setOf("a"), 100.0, 0.0, 1.0, 0.0)
        val stale = moved.transformGroup(setOf("a"), 50.0, 0.0, 1.0, 0.0)
        val session = EditorSession(initial)
        session.beginGesture()
        assertTrue(session.updateGesture(moved))
        session.endGesture() // Equivalent to finishing a snapshot before saving.
        assertFalse(session.isGesturing)
        assertFalse(session.updateGesture(stale))
        assertEquals(moved, session.project)
        assertEquals(1, session.history.undoCount)
        session.undo()
        assertFalse(session.updateGesture(stale))
        assertEquals(initial, session.project)
        session.redo()
        assertFalse(session.updateGesture(stale))
        assertEquals(moved, session.project)
    }

    @Test fun `undo interrupting active drag rejects subsequent pointer event`() {
        val initial = Project(objects = listOf(obj("a")))
        val session = EditorSession(initial)
        val moved = initial.transformGroup(setOf("a"), 30.0, 10.0, 1.0, 0.0)
        session.beginGesture()
        session.updateGesture(moved)
        session.undo()
        assertFalse(session.updateGesture(moved))
        assertEquals(initial, session.project)
        assertTrue(session.canRedo)
        session.redo()
        assertEquals(moved, session.project)
    }

    @Test fun `no-op preview preserves redo and previews do not consume undo steps`() {
        val session = EditorSession()
        session.commit(Project(objects = listOf(obj("a"))))
        session.undo()
        session.beginGesture()
        repeat(100) { session.updateGesture(Project()) }
        assertEquals(0, session.history.undoCount)
        session.endGesture()
        assertTrue(session.canRedo)
    }

    @Test fun `group rotation and scaling use group center and preserve mirror sewing order and unselected objects`() {
        val a = obj("a", -100.0).copy(transform = Transform(x = -100.0, scaleX = -1.0))
        val untouched = obj("other", 400.0)
        val p = Project(objects = listOf(a, obj("b", 100.0), untouched))
        val transformed = p.transformGroup(setOf("a", "b"), 5.0, 10.0, 2.0, 90.0)
        assertEquals(listOf("a", "b", "other"), transformed.objects.map { it.id })
        assertEquals(5.0, transformed.find("a")!!.transform.x, 0.001)
        assertEquals(-190.0, transformed.find("a")!!.transform.y, 0.001)
        assertEquals(210.0, transformed.find("b")!!.transform.y, 0.001)
        assertEquals(-2.0, transformed.find("a")!!.transform.scaleX)
        assertSame(untouched, transformed.find("other"))
    }

    @Test fun `group snap moves all selected objects by same correction and ignores hidden objects`() {
        val p = Project(objects = listOf(obj("a", -27.0, 80.0), obj("b", 33.0, 80.0),
            obj("hidden", 999.0, visible = false)))
        val ids = setOf("a", "b")
        val targets = p.snapTargets(ids)
        assertEquals(listOf(0.0), targets.xs)
        val snapped = p.snapSelection(ids, targets, 5.0)
        assertEquals(0.0, snapped.guides.x)
        assertEquals(-30.0, snapped.project.find("a")!!.transform.x)
        assertEquals(30.0, snapped.project.find("b")!!.transform.x)
        assertNull(snapped.guides.y)
    }

    @Test fun `snap aligns object edge to another edge and releases outside screen tolerance`() {
        val p = Project(objects = listOf(obj("moving", 84.0, 80.0), obj("fixed", 105.0, 250.0)))
        val ids = setOf("moving")
        val targets = p.snapTargets(ids)
        val snapped = p.snapSelection(ids, targets, 2.0)
        assertEquals(95.0, snapped.guides.x)
        assertEquals(85.0, snapped.project.find("moving")!!.transform.x)
        val far = p.transformGroup(ids, -10.0, 0.0, 1.0, 0.0).snapSelection(ids, targets, 2.0)
        assertNull(far.guides.x)
    }

    @Test fun `small unsnapped moves accumulate enough to leave magnet`() {
        var raw = Project(objects = listOf(obj("a", y = 80.0)))
        val ids = setOf("a")
        val targets = raw.snapTargets(ids)
        repeat(3) {
            raw = raw.transformGroup(ids, 1.0, 0.0, 1.0, 0.0)
            assertEquals(0.0, raw.snapSelection(ids, targets, 3.0).project.find("a")!!.transform.x)
        }
        raw = raw.transformGroup(ids, 1.0, 0.0, 1.0, 0.0)
        val released = raw.snapSelection(ids, targets, 3.0)
        assertEquals(4.0, released.project.find("a")!!.transform.x)
        assertNull(released.guides.x)
    }

    @Test fun `numeric properties use mm accept decimal comma preserve mirrors and warn beyond twenty percent`() {
        val original = Transform(scaleX = -1.0)
        val t = parseEditorTransform("-12,5", "3.75", "450", "120", "80", original)!!
        assertEquals(-125.0, t.x)
        assertEquals(37.5, t.y)
        assertEquals(90.0, t.rotationDeg)
        assertEquals(-1.2, t.scaleX)
        assertEquals(0.8, t.scaleY)
        assertFalse(obj("a").withTransform(t).densityWarning)
        assertTrue(obj("a").withTransform(t.copy(scaleY = 0.79)).densityWarning)
    }

    @Test fun `invalid numeric properties cannot create nonfinite or zero transforms`() {
        for (bad in listOf("", "NaN", "Infinity", "1e999", "hello"))
            assertNull(parseEditorTransform(bad, "0", "0", "100", "100", Transform()))
        for (bad in listOf("0", "-5", "2001", "NaN"))
            assertNull(parseEditorTransform("0", "0", "0", bad, "100", Transform()))
    }

    @Test fun `model alignment and distribution are committed as one undo step`() {
        val p = Project(objects = listOf(obj("a", -100.0, 30.0), obj("b", -70.0, 10.0), obj("c", 100.0, 70.0)))
        val ids = setOf("a", "b", "c")
        val session = EditorSession(p)
        session.commit(p.align(ids, Alignment.TOP).distribute(ids, Axis.HORIZONTAL))
        assertEquals(0.0, session.project.find("b")!!.transform.x)
        assertEquals(0.0, session.project.find("b")!!.placedBounds()!!.minY.toDouble())
        session.undo()
        assertEquals(p, session.project)
        assertFalse(session.canUndo)
    }

    @Test fun `project file save is lossless unique and roundtrip can be undone`() {
        val directory = Files.createTempDirectory("editor-project-test").toFile()
        try {
            val p = Project(HoopSpec.H130x180, listOf(obj("a", -70.0), obj("hidden", visible = false)))
            val first = saveProjectSnapshot(directory, "내 작업/one.myembro", p)
            val second = saveProjectSnapshot(directory, suggestDesignName("내 작업/one.myembro") { designSaveTarget(directory, it, "myembro").exists() }, p)
            assertNotEquals(first.name, second.name)
            assertEquals(directory, first.parentFile)
            assertEquals(2, listProjectFiles(directory).size)
            assertTrue(directory.listFiles()!!.none { it.extension == "tmp" })
            val restored = ProjectJson.decode(first.readText())
            assertEquals(p.hoop, restored.hoop)
            assertEquals(p.objects.map { it.transform }, restored.objects.map { it.transform })
            assertEquals(p.objects.map { it.visible }, restored.objects.map { it.visible })
            assertEquals(p.flatten().stitches, restored.flatten().stitches)
            assertEquals(p.flatten().threads, restored.flatten().threads)
            val session = EditorSession(Project())
            session.commit(restored)
            session.undo()
            assertEquals(Project(), session.project)
        } finally { directory.deleteRecursively() }
    }
}
