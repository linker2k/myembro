// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

/** One save dialog: default format, suggested (Hangul) name, replace-or-save-as for opened app files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorSaveFlowTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val designs get() = File(app.filesDir, "designs").apply { mkdirs() }
    private fun stitches(id: String, name: String) = StitchObject(id, name, EmbPattern(listOf(
        Stitch(0, 0, Command.STITCH), Stitch(30, 20, Command.STITCH), Stitch(30, 20, Command.END)), listOf(EmbThread(0xff0000ff.toInt()))))

    private fun settle(vm: EditorViewModel, done: () -> Boolean = { true }) {
        val deadline = System.currentTimeMillis() + 10_000
        do { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        while ((vm.busy || !done()) && System.currentTimeMillis() < deadline)
        assertTrue(done())
    }
    private fun editor() = EditorViewModel(app).also { vm -> settle(vm) }

    @Test fun newDesignDefaultsToProjectAndSuggestsTheVisibleHangulName() {
        val vm = editor()
        vm.startWith(stitches("a", "사진외곽선"), HoopSpec.H100)
        settle(vm) { vm.project.objects.isNotEmpty() }
        vm.requestSave()
        settle(vm) { vm.savePrompt != null }
        assertNull(vm.saveChoice)
        assertEquals(EditorSavePrompt(EditorSaveKind.PROJECT, "사진외곽선_2"), vm.savePrompt)
        vm.selectSaveKind(EditorSaveKind.PES)
        assertEquals(EditorSaveKind.PES, vm.savePrompt?.kind)
        assertEquals("사진외곽선_2", vm.savePrompt?.suggestedName)
    }

    @Test fun leavingForgetsTheOldFileNameBeforeAPhotoIsAdded() {
        val old = saveProjectSnapshot(designs, "wolf-line", Project(objects = listOf(stitches("w", "wolf"))))
        val vm = editor()
        vm.openProject(old)
        settle(vm) { vm.project.objects.isNotEmpty() }
        vm.leave {}
        settle(vm) { vm.project.objects.isEmpty() }
        vm.addPicture(stitches("p", "사진외곽선"))
        vm.requestSave()
        settle(vm) { vm.savePrompt != null }
        assertNull(vm.saveChoice, "the photo design was never opened from a file")
        assertEquals("사진외곽선_2", vm.savePrompt?.suggestedName)
    }

    @Test fun openedProjectOffersReplaceWithConfirmationOrSaveAs() {
        val file = saveProjectSnapshot(designs, "내 꽃", Project(objects = listOf(stitches("f", "꽃"))))
        val before = file.readText()
        val vm = editor()
        vm.openProject(file)
        settle(vm) { vm.project.objects.isNotEmpty() }
        vm.addPicture(stitches("g", "잎"))
        vm.requestSave()
        assertEquals(EditorSaveChoice(file, EditorSaveKind.PROJECT), vm.saveChoice)
        assertTrue(vm.interactionBlocked)

        // Save as: the normal dialog with the opened name + suffix; nothing is replaced.
        vm.saveOpenedFileAs()
        settle(vm) { vm.savePrompt != null }
        assertEquals(EditorSavePrompt(EditorSaveKind.PROJECT, "내 꽃_2"), vm.savePrompt)
        vm.cancelNamedSave()

        // Save (replace): only the confirmation; cancelling it leaves the file untouched.
        vm.requestSave()
        vm.replaceOpenedFile()
        assertEquals(EditorSavePrompt(EditorSaveKind.PROJECT, "내 꽃", form = false), vm.savePrompt)
        assertEquals("내 꽃", vm.overwriteName)
        vm.cancelOverwrite()
        assertNull(vm.savePrompt); assertNull(vm.overwriteName)
        assertEquals(before, file.readText())

        vm.requestSave(); vm.replaceOpenedFile()
        vm.submitSaveName("내 꽃", overwrite = true)
        settle(vm) { !vm.dirty }
        assertEquals(listOf("f", "g"), ProjectJson.decode(file.readText()).objects.map { it.id })
        assertEquals(1, designs.listFiles()!!.count { it.name.startsWith("내 꽃") })
    }

    @Test fun openedPesDefaultsToPesAndSaveAsSuggestsItsName() {
        val pes = File(designs, "레몬 자수.pes").apply { writeBytes(File("../engine/src/test/resources/fixtures/stops.v1.pes").readBytes()) }
        val vm = editor()
        vm.editDesign(pes)
        settle(vm) { vm.project.objects.isNotEmpty() }
        assertEquals(EditorSaveKind.PES, vm.documentKind)
        vm.requestSave()
        assertEquals(EditorSaveChoice(pes, EditorSaveKind.PES), vm.saveChoice)
        vm.saveOpenedFileAs()
        settle(vm) { vm.savePrompt != null }
        assertEquals(EditorSavePrompt(EditorSaveKind.PES, "레몬 자수_2"), vm.savePrompt)
    }

    @Test fun unsavedExitSaveStillOpensTheProjectDialogWithoutTheReplaceChoice() {
        val file = saveProjectSnapshot(designs, "정원", Project(objects = listOf(stitches("f", "꽃"))))
        val vm = editor()
        vm.openProject(file)
        settle(vm) { vm.project.objects.isNotEmpty() }
        vm.addPicture(stitches("g", "잎"))
        vm.leave {}
        assertTrue(vm.replacementRequested)
        vm.saveAndContinue()
        settle(vm) { vm.savePrompt != null }
        assertNull(vm.saveChoice)
        assertEquals(EditorSavePrompt(EditorSaveKind.PROJECT, "정원_2"), vm.savePrompt)
    }

    @Test fun aSampleOutsideTheDesignFolderIsNeverReplaced() {
        val sample = File(app.cacheDir, "sample.pes").apply { writeBytes(File("../engine/src/test/resources/fixtures/stops.v1.pes").readBytes()) }
        val vm = editor()
        vm.editDesign(sample, "늑대")
        settle(vm) { vm.project.objects.isNotEmpty() }
        vm.requestSave()
        settle(vm) { vm.savePrompt != null }
        assertNull(vm.saveChoice)
        assertEquals(EditorSavePrompt(EditorSaveKind.PES, "늑대_2"), vm.savePrompt)
    }

    @Test fun oneDialogChoosesNameFormatAndLocationAndPesCanGoToAPickedFile() {
        val sample = File(app.cacheDir, "sample.pes").apply { writeBytes(File("../engine/src/test/resources/fixtures/stops.v1.pes").readBytes()) }
        val vm = editor()
        vm.editDesign(sample, "봄날")
        settle(vm) { vm.project.objects.isNotEmpty() }
        vm.requestSave()
        settle(vm) { vm.savePrompt != null }
        assertFalse(vm.savePrompt!!.toFile)
        vm.selectSaveLocation(true)
        assertEquals(EditorSavePrompt(EditorSaveKind.PES, "봄날_2", toFile = true), vm.savePrompt)
        vm.selectSaveKind(EditorSaveKind.PROJECT) // both formats can go to a file
        assertEquals(EditorSavePrompt(EditorSaveKind.PROJECT, "봄날_2", toFile = true), vm.savePrompt)
        vm.selectSaveKind(EditorSaveKind.PES)
        vm.hideSavePrompt() // the screen opens the system file picker here
        val picked = File(app.cacheDir, "picked.pes").apply { delete() }
        vm.exportPes("봄날_2", android.net.Uri.fromFile(picked))
        settle(vm) { picked.isFile || vm.exportProblems != null || vm.message != null }
        assertNull(vm.exportProblems)
        assertEquals("#PES", picked.readBytes().copyOf(4).toString(Charsets.ISO_8859_1))
        assertFalse(File(designs, "봄날_2.pes").exists(), "a file export does not also save in the app")
    }
}
