// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.ui.machines.MachineHoop
import io.github.linker2k.myembro.ui.machines.MachineProfiles
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import java.nio.file.Files
import kotlin.test.*

class EditorSafetyRegressionTest {
    private fun project(width: Int = 200, hoop: HoopSpec = HoopSpec.H100) = Project(hoop, listOf(
        StitchObject("a", "a", EmbPattern(listOf(
            Stitch(-width / 2, -50, Command.STITCH), Stitch(width / 2, 50, Command.STITCH),
            Stitch(width / 2, 50, Command.END)), listOf(EmbThread(0xFF123456.toInt(), "thread"))))))

    @Test fun `SE400 editor offers only profile sewing areas and not 130 by 180`() {
        val hoops = editorHoops(MachineProfiles.SE400)
        assertTrue(hoops.any { it.sameArea(HoopSpec.H100) })
        assertFalse(hoops.any { it.sameArea(HoopSpec.H130x180) })
        assertFalse(hoops.any { it.height > 1000 }) // Repositioning coverage is not a single sewing area.
    }

    @Test fun `SA431 and future profile accessories appear without changing ALL`() {
        val small = HoopSpec("SA431", 600, 200)
        val profile = MachineProfiles.SE400.copy(hoops = listOf(MachineHoop(HoopSpec.H100), MachineHoop(small)))
        assertEquals(listOf(HoopSpec.H100, small), editorHoops(profile))
        assertFalse(HoopSpec.ALL.any { it.sameArea(small) })
    }

    @Test fun `110mm design on imported large hoop is blocked by engine SE400 preflight`() {
        val p = project(width = 1100, hoop = HoopSpec.H130x180)
        assertFalse(p.exceedsHoop())
        val problems = editorExportProblems(p, EmbroiderySettings(), "design.pes")
        assertTrue("hoop_unsupported" in problems)
        assertTrue(problems.any { it.contains("100×100") }) // Actual engine Preflight, independently of canvas fit.
    }

    @Test fun `inside supported hoop passes but off-centre placement and stitch limit fail`() {
        assertTrue(editorExportProblems(project(), EmbroiderySettings(), "design.pes").isEmpty())
        val moved = project().update("a") { it.withTransform(Transform(x = 600.0)) }
        assertTrue("hoop_overflow" in editorExportProblems(moved, EmbroiderySettings(), "design.pes"))
        val dense = Project(objects = listOf(StitchObject("dense", "dense", EmbPattern(
            List(30001) { Stitch(it % 10, 0, Command.STITCH) }, emptyList()))))
        assertTrue(editorExportProblems(dense, EmbroiderySettings(), "design.pes").any { it.contains("30000") })
    }

    @Test fun `unknown machine is not treated as unlimited`() {
        assertTrue(editorHoops(null).isEmpty())
        assertTrue("machine_unknown" in editorExportProblems(project(), EmbroiderySettings(machineModel = "Custom"), "design.pes"))
    }

    @Test fun `dirty baseline follows undo redo and successful editable saves`() {
        val d = EditorDocuments()
        assertFalse(d.dirty)
        d.session.commit(project())
        assertTrue(d.dirty)
        d.session.undo()
        assertFalse(d.dirty)
        d.session.redo()
        assertTrue(d.dirty)
        d.markSaved(d.session.project)
        assertFalse(d.dirty)
        d.session.undo()
        assertTrue(d.dirty)
        d.session.redo()
        assertFalse(d.dirty)
    }

    @Test fun `imported design is clean until changed even with no file on disk`() {
        val d = EditorDocuments()
        d.replaceAfterRead(savedOnDisk = false) { project() }
        assertFalse(d.dirty)
        assertFalse(d.session.canUndo)
    }

    @Test fun `new project requires decision and cancel preserves work and undo history`() {
        val d = EditorDocuments()
        d.session.commit(project())
        val previous = d.session
        d.requestReplacement { d.replaceAfterRead(true) { Project() } }
        assertTrue(d.needsDecision)
        assertSame(previous, d.session)
        assertEquals(1, d.session.history.undoCount)
        d.cancelReplacement()
        d.continueReplacement()
        assertSame(previous, d.session)
        assertTrue(d.dirty)
    }

    @Test fun `discard then failed file read preserves exact session selection source and undo redo`() {
        val d = EditorDocuments()
        d.session.commit(project())
        d.session.commit(project(300))
        d.session.undo()
        val previous = d.session
        d.requestReplacement { d.replaceAfterRead(false) { error("Unreadable PES") } }
        assertFailsWith<IllegalStateException> { d.continueReplacement() }
        assertSame(previous, d.session)
        assertTrue(d.dirty)
        assertEquals(1, d.session.history.undoCount)
        assertEquals(1, d.session.history.redoCount)
        d.session.redo()
        assertEquals(300, d.session.project.placedBounds()!!.width)
    }

    @Test fun `failed replacement from clean session also keeps history`() {
        val d = EditorDocuments()
        d.replaceAfterRead(true) { project() }
        val previous = d.session
        assertFailsWith<IllegalArgumentException> {
            d.requestReplacement { d.replaceAfterRead(true) { ProjectJson.decode("not JSON") } }
        }
        assertSame(previous, d.session)
        assertFalse(d.dirty)
    }

    @Test fun `save then replace keeps saved snapshot and failed save never invokes continuation`() {
        val d = EditorDocuments()
        d.session.commit(project())
        var replaced = false
        d.requestReplacement { replaced = true; d.replaceAfterRead(true) { Project() } }
        val notDirectory = Files.createTempFile("not-a-directory", ".tmp").toFile()
        try { assertFails { saveProjectSnapshot(notDirectory, "project", d.session.project) } }
        finally { notDirectory.delete() }
        assertFalse(replaced)
        assertTrue(d.dirty)
        d.markSaved(d.session.project)
        d.continueReplacement()
        assertTrue(replaced)
        assertFalse(d.dirty)
        assertFalse(d.session.canUndo)
    }

    @Test fun `draft survives restart with transforms hoop hidden objects and editable data`() {
        val directory = Files.createTempDirectory("editor-drafts").toFile()
        try {
            val p = project().update("a") { it.withTransform(Transform(x = 32.5, rotationDeg = 90.0)).withVisible(false) }
            EditorDraftStore(directory).write(p)
            val recovered = EditorDraftStore(directory).read()!!
            assertEquals(p.hoop, recovered.hoop)
            assertEquals(p.find("a")!!.transform, recovered.find("a")!!.transform)
            assertFalse(recovered.find("a")!!.visible)
            val d = EditorDocuments()
            d.replaceAfterRead(false, restoredDraft = true) { recovered }
            assertTrue(d.dirty)
            assertFalse(directory.listFiles()!!.any { it.extension == "tmp" })
            EditorDraftStore(directory).write(null)
            assertNull(EditorDraftStore(directory).read())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `failed draft write retains last successful recovery snapshot`() {
        val directory = Files.createTempDirectory("editor-drafts-failure").toFile()
        try {
            val store = EditorDraftStore(directory)
            store.write(project())
            val original = directory.resolve("editor.myembro").readBytes()
            val invalidDirectory = directory.resolve("editor.myembro")
            assertFails { EditorDraftStore(invalidDirectory).write(project(300)) }
            assertContentEquals(original, directory.resolve("editor.myembro").readBytes())
            assertEquals(200, store.read()!!.placedBounds()!!.width)
        } finally { directory.deleteRecursively() }
    }
}
