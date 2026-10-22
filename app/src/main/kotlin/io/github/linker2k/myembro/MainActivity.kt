// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.library.DesignLibrary
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.LibraryScreen
import io.github.linker2k.myembro.ui.LibraryViewModel
import io.github.linker2k.myembro.ui.ViewerScreen
import io.github.linker2k.myembro.ui.ViewerViewModel
import io.github.linker2k.myembro.ui.editor.EditorScreen
import io.github.linker2k.myembro.ui.editor.EditorViewModel
import io.github.linker2k.myembro.ui.home.HomeScreen
import io.github.linker2k.myembro.ui.lettering.LetteringScreen
import io.github.linker2k.myembro.ui.settings.SettingsScreen
import io.github.linker2k.myembro.ui.settings.SettingsViewModel
import io.github.linker2k.myembro.ui.sources.FreeDesignSourcesScreen
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import io.github.linker2k.myembro.ui.theme.installMyembroSplashScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewer: ViewerViewModel by viewModels()
    private val library: LibraryViewModel by viewModels()
    private val editor: EditorViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()

    /** [PICK_DESIGN] is the library opened from the new-project sheet: a tap starts a project. */
    private enum class Screen { LIBRARY, PICK_DESIGN, VIEWER, EDITOR, SETTINGS, LETTERING, FREE_DESIGNS }

    /** Screens above home; back pops one. Home is always the root. */
    private val stack = mutableStateListOf<Screen>()

    /** Library path of the design in the viewer, or null for a file read without importing. */
    private var openPath by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installMyembroSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openPath = savedInstanceState?.getString(KEY_OPEN)
        savedInstanceState?.getStringArrayList(KEY_STACK)?.let { names -> stack += names.map(Screen::valueOf) }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val context = LocalContext.current
            val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { importUris(listOf(it)) }
            }
            val saveDocument = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream"),
            ) { uri -> viewer.save(uri) }
            val shareUri = viewer.shareUri
            LaunchedEffect(shareUri) {
                if (shareUri != null) {
                    viewer.consumeShare(shareUri)
                    try {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            clipData = ClipData.newRawUri(context.getString(R.string.share), shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_pes)))
                    } catch (error: Exception) {
                        viewer.reportExportFailure(error)
                    }
                }
            }
            val back: () -> Unit = {
                if (stack.removeLastOrNull() == Screen.EDITOR) library.refresh()
            }
            val newProject = { hoop: HoopSpec ->
                editor.newProject(hoop)
                stack += Screen.EDITOR
            }
            MyembroTheme {
                BackHandler(enabled = stack.isNotEmpty(), onBack = back)
                when (stack.lastOrNull()) {
                    null -> HomeScreen(
                        onNewProject = { hoop -> newProject(hoop.spec()) },
                        onOpenRecent = { file ->
                            if (file.extension == "myembro") {
                                editor.openProject(file)
                                stack += Screen.EDITOR
                            } else {
                                openDesign(file)
                            }
                        },
                        // PES files have no registered MIME type, so allow any file and let the reader decide.
                        onImport = { openDocument.launch(arrayOf("*/*")) },
                        onLettering = { stack += Screen.LETTERING },
                        onOpenLibrary = { stack += Screen.LIBRARY },
                        onSettings = { stack += Screen.SETTINGS },
                        onCreateFromDesign = { stack += Screen.PICK_DESIGN },
                        letteringAvailable = true,
                        onFreeDesigns = { stack += Screen.FREE_DESIGNS },
                    )
                    Screen.LIBRARY -> LibraryScreen(library, onOpen = ::openDesign, onFreeDesigns = { stack += Screen.FREE_DESIGNS })
                    Screen.FREE_DESIGNS -> FreeDesignSourcesScreen(onBack = back)
                    Screen.PICK_DESIGN -> LibraryScreen(library, onOpen = { file ->
                        editor.editDesign(file)
                        stack[stack.lastIndex] = Screen.EDITOR
                    })
                    Screen.SETTINGS -> SettingsScreen(settings, onBack = back)
                    Screen.EDITOR -> EditorScreen(editor, onBack = back)
                    Screen.LETTERING -> {
                        val hoop = settings.settings.defaultHoop.spec()
                        LetteringScreen(
                            onBack = back,
                            onAdd = { text ->
                                editor.startWith(text, hoop)
                                stack[stack.lastIndex] = Screen.EDITOR
                            },
                            hoop = hoop,
                        )
                    }
                    Screen.VIEWER -> ViewerScreen(
                        viewer,
                        onOpenFile = { openDocument.launch(arrayOf("*/*")) },
                        onSaveFile = {
                            viewer.prepareSave()?.let { name ->
                                try {
                                    saveDocument.launch(name)
                                } catch (error: Exception) {
                                    viewer.savePickerFailed(error)
                                }
                            }
                        },
                        onShareFile = viewer::share,
                        onEdit = openPath?.let { path ->
                            {
                                editor.editDesign(File(path))
                                stack += Screen.EDITOR
                            }
                        },
                        // The USB flow (access prompt, drive choice, result) lives on the library screen.
                        onExportUsb = openPath?.let { path ->
                            {
                                library.clearSelection()
                                library.toggle(File(path))
                                stack += Screen.LIBRARY
                                library.exportToUsb()
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_OPEN, openPath)
        outState.putStringArrayList(KEY_STACK, ArrayList(stack.map { it.name }))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "content") {
                    importUris(listOf(uri))
                } else {
                    openPath = null
                    viewer.open(uri)
                    showViewer()
                }
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> importUris(sharedUris(intent))
        }
    }

    /** Streams from a share, plus clip data some apps use instead; links in text are never fetched. */
    private fun sharedUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::addAll)
        }
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        return uris.filter { it.scheme == "content" }.distinct()
    }

    private fun Hoop.spec() = HoopSpec(label, widthMm * 10, heightMm * 10)

    private fun showViewer() {
        if (stack.lastOrNull() != Screen.VIEWER) stack += Screen.VIEWER
    }

    private fun openDesign(file: File) {
        openPath = file.path
        viewer.openFile(file)
        showViewer()
    }

    /**
     * Copies designs (or ZIPs of them) into the library, each URI on its own so one failure keeps
     * the others. A single design opens in the viewer; anything else lands on the library with a
     * summary message.
     */
    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val imported = mutableListOf<File>()
            val failures = mutableListOf<String>()
            for (uri in uris) {
                try {
                    imported += withContext(Dispatchers.IO) { DesignLibrary(applicationContext).importShared(uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
                    failures += getString(R.string.library_shared_failed, name, e.message ?: e.javaClass.simpleName)
                }
            }
            library.refresh()
            if (imported.size == 1 && failures.isEmpty()) {
                openDesign(imported.single())
                return@launch
            }
            val summary = if (imported.isEmpty()) emptyList() else listOf(getString(R.string.library_shared_imported, imported.size))
            library.message = (summary + failures).joinToString("\n")
            if (stack.lastOrNull() != Screen.LIBRARY) stack += Screen.LIBRARY
        }
    }

    private companion object {
        const val KEY_OPEN = "open_path"
        const val KEY_STACK = "screen_stack"
    }
}
