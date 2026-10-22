// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro

import android.content.ClipData
import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.ui.LibraryScreen
import io.github.linker2k.myembro.ui.LibraryViewModel
import io.github.linker2k.myembro.ui.ViewerScreen
import io.github.linker2k.myembro.ui.ViewerViewModel
import io.github.linker2k.myembro.ui.editor.EditorScreen
import io.github.linker2k.myembro.ui.editor.EditorViewModel
import io.github.linker2k.myembro.ui.home.HomeScreen
import io.github.linker2k.myembro.ui.settings.SettingsScreen
import io.github.linker2k.myembro.ui.settings.SettingsViewModel
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import io.github.linker2k.myembro.ui.theme.installMyembroSplashScreen
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewer: ViewerViewModel by viewModels()
    private val library: LibraryViewModel by viewModels()
    private val editor: EditorViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()

    private enum class Screen { LIBRARY, VIEWER, EDITOR, SETTINGS }

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
                uri?.let(::importAndView)
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
                        onNewProject = { hoop -> newProject(HoopSpec(hoop.label, hoop.widthMm * 10, hoop.heightMm * 10)) },
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
                        // Lettering lives in the editor; until it has a text tool this opens an empty project.
                        onLettering = { newProject(HoopSpec.H100) },
                        onOpenLibrary = { stack += Screen.LIBRARY },
                        onSettings = { stack += Screen.SETTINGS },
                    )
                    Screen.LIBRARY -> LibraryScreen(
                        library,
                        onOpen = ::openDesign,
                        onSettings = { stack += Screen.SETTINGS },
                    )
                    Screen.SETTINGS -> SettingsScreen(settings, onBack = back)
                    Screen.EDITOR -> EditorScreen(editor, onBack = back)
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
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(::importAndView)
    }

    private fun showViewer() {
        if (stack.lastOrNull() != Screen.VIEWER) stack += Screen.VIEWER
    }

    private fun openDesign(file: File) {
        openPath = file.path
        viewer.openFile(file)
        showViewer()
    }

    /** Files opened from outside go into the library first, so they can be exported later. */
    private fun importAndView(uri: android.net.Uri) {
        lifecycleScope.launch {
            runCatching { library.importForViewing(uri) }
                .onSuccess(::openDesign)
                .onFailure {
                    openPath = null
                    viewer.open(uri)
                    showViewer()
                }
        }
    }

    private companion object {
        const val KEY_OPEN = "open_path"
        const val KEY_STACK = "screen_stack"
    }
}
