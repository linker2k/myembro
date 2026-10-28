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

/** A photo result sent to the editor while the recovery dialog is open must survive either answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class EditorRecoveryPictureTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun stitches(id: String) = StitchObject(id, "Synthetic $id", EmbPattern(listOf(
        Stitch(0,0,Command.STITCH),Stitch(30,20,Command.STITCH),Stitch(30,20,Command.END)),listOf(EmbThread(0xff0000ff.toInt()))))

    private fun editorWithDraft(): EditorViewModel {
        EditorDraftStore(File(app.filesDir,"drafts")).write(Project(objects=listOf(stitches("draft"))))
        val vm = EditorViewModel(app)
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.recoveryProject == null && System.currentTimeMillis() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertNotNull(vm.recoveryProject, "the draft opens the recovery dialog")
        return vm
    }

    @Test fun discardOpensThePictureThatArrivedDuringTheDialog() {
        val vm = editorWithDraft()
        vm.addPicture(stitches("picture"))
        vm.discardDraft()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("picture"), vm.project.objects.map { it.id })
    }

    @Test fun restoreKeepsTheDraftAndAddsThePicture() {
        val vm = editorWithDraft()
        vm.addPicture(stitches("picture"))
        vm.restoreDraft()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("draft","picture"), vm.project.objects.map { it.id })
    }
}
