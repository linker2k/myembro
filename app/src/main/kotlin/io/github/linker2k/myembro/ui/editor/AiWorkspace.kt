// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * The AI work project: the editor's ViewModel (its EditSession and undo history) kept for the whole
 * process instead of one Activity. The AI connection (RelayService, a foreground service that keeps the
 * process alive) reads and edits it while the editor screen, or the whole Activity, is gone because the
 * AI app is in front; opening the editor shows the same project and history. Unsaved changes are kept
 * by the editor's own autosave (EditorDraftStore) as before, so a process restart recovers them.
 */
object AiWorkspace : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    /** MainActivity is started (on screen, split screen too): it shows AI changes itself instead of a notification. */
    @Volatile var uiStarted = false
    /** AI changes for a started MainActivity while its editor is not on screen: the headline to show. */
    val changes = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** MainActivity opens the editor on this project as it is (the AI connection notification's tap). */
    const val ACTION_OPEN = "io.github.linker2k.myembro.action.OPEN_AI_PROJECT"

    /** The one editor for this process; call on the main thread. */
    fun editor(app: Application): EditorViewModel =
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[EditorViewModel::class.java]

    /** What every AI connection call works on (RelayService): the editor, ready for a call with nobody looking. */
    suspend fun relayDocument(app: Application): io.github.linker2k.myembro.relay.RelayDocument =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { editor(app).also { it.relayReady() }.relayDocument }
}
