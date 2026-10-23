// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import java.nio.file.Files
import kotlin.test.*

class EditorReviewFlowTest {
    private val initial = Project(objects = listOf(StitchObject("lemon", "lemon", EmbPattern(
        listOf(Stitch(-50,-50,Command.STITCH), Stitch(50,50,Command.STITCH)), emptyList()))))

    @Test fun `opening imported or serialized design is clean and immediately leaves without decision or draft`() {
        for (onDisk in listOf(false, true)) {
            val documents = EditorDocuments()
            documents.replaceAfterRead(onDisk) { initial }
            var left = false
            documents.requestReplacement { left = true }
            assertTrue(left)
            assertFalse(documents.needsDecision)
            assertFalse(documents.dirty)
            assertNull(documents.draft)
            documents.session.beginGesture(); documents.session.endGesture()
            assertNull(documents.draft)
        }
    }
    @Test fun `move then undo back to opened snapshot is clean and clears recovery draft`() {
        val documents = EditorDocuments()
        documents.replaceAfterRead(false) { initial }
        val dir = Files.createTempDirectory("review-drafts").toFile()
        try {
            val store = EditorDraftStore(dir)
            documents.session.commit(initial.rotateBy("lemon", 90.0))
            assertTrue(documents.dirty)
            store.write(documents.draft)
            assertNotNull(store.read())
            documents.session.undo()
            assertFalse(documents.dirty)
            store.write(documents.draft)
            assertNull(store.read())
            documents.session.redo()
            assertTrue(documents.dirty)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `manually returning transform to baseline is clean despite history entries`() {
        val documents = EditorDocuments()
        documents.replaceAfterRead(false) { initial }
        documents.session.commit(initial.rotateBy("lemon", 90.0))
        documents.session.commit(documents.session.project.update("lemon") { it.withTransform(Transform()) })
        assertTrue(documents.session.canUndo)
        assertFalse(documents.dirty)
        assertNull(documents.draft)
    }
    @Test fun `recovered unsaved draft still requires a save decision`() {
        val documents = EditorDocuments()
        documents.replaceAfterRead(false, restoredDraft = true) { initial }
        assertTrue(documents.dirty)
        assertNotNull(documents.draft)
    }
    @Test fun `copy suggestions advance suffix and skip existing names`() {
        assertEquals("lemon_2", suggestDesignName("lemon.pes") { false })
        assertEquals("lemon_4", suggestDesignName("lemon.myembro") { it in setOf("lemon_2", "lemon_3") })
        assertEquals("lemon_3", suggestDesignName("lemon_2.PES") { false })
        assertEquals("레몬_2", suggestDesignName("레몬.myembro") { false })
        assertEquals("design_2", suggestDesignName("design") { false })
        assertFailsWith<IllegalArgumentException> { suggestDesignName("  ") { false } }
        val longName = "a".repeat(80)
        val proposed = suggestDesignName(longName) { it == longName }
        assertEquals(80, proposed.length)
        assertTrue(proposed.endsWith("_2"))
    }
    @Test fun `user names remain confined to target directory and extensions are not duplicated`() {
        val directory = Files.createTempDirectory("review-names").toFile()
        try {
            assertEquals(directory.resolve("lemon.pes"), designSaveTarget(directory, "../lemon.PES", "pes"))
            assertEquals("lemon_name", designSaveStem("lemon:name"))
            assertEquals("lemon.v2_2", suggestDesignName("lemon.v2.myembro") { false })
        } finally { directory.deleteRecursively() }
    }
    @Test fun `matching name requires explicit overwrite and cancel leaves file unchanged`() {
        val directory = Files.createTempDirectory("review-overwrite").toFile()
        try {
            val old = writeNamedDesign(directory, "lemon", "pes", false, byteArrayOf(1,2,3))
            assertFailsWith<EditorSaveConflict> { writeNamedDesign(directory, "LEMON", "pes", false, byteArrayOf(4,5)) }
            assertContentEquals(byteArrayOf(1,2,3), old.readBytes())
            assertEquals("lemon_2", suggestDesignName("lemon") { designSaveTarget(directory, it, "pes").exists() })
            val overwritten = writeNamedDesign(directory, "LEMON", "pes", true, byteArrayOf(4,5))
            assertEquals(old, overwritten)
            assertContentEquals(byteArrayOf(4,5), old.readBytes())
            assertEquals(1, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }
    @Test fun `project save conflict preserves editable project until confirmed`() {
        val directory = Files.createTempDirectory("review-project-save").toFile()
        try {
            val old = saveProjectSnapshot(directory, "lemon", initial)
            val changed = initial.rotateBy("lemon", 90.0)
            assertFailsWith<EditorSaveConflict> { saveProjectSnapshot(directory, "lemon", changed) }
            assertEquals(initial.flatten().stitches, ProjectJson.decode(old.readText()).flatten().stitches)
            saveProjectSnapshot(directory, "lemon", changed, overwrite = true)
            assertEquals(changed.flatten().stitches, ProjectJson.decode(old.readText()).flatten().stitches)
        } finally { directory.deleteRecursively() }
    }
}
