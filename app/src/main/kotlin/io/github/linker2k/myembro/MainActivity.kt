// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import io.github.linker2k.myembro.ui.LocalGoHome
import io.github.linker2k.myembro.ui.popToHome
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.export.PreflightDialog
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.LibraryScreen
import io.github.linker2k.myembro.ui.LibraryViewModel
import io.github.linker2k.myembro.ui.DesignImportDialogs
import io.github.linker2k.myembro.ui.rememberDesignImporter
import io.github.linker2k.myembro.ui.ViewerScreen
import io.github.linker2k.myembro.ui.ViewerViewModel
import io.github.linker2k.myembro.ui.editor.EditorRecoveryDialog
import io.github.linker2k.myembro.ui.editor.EditorScreen
import io.github.linker2k.myembro.ui.editor.EditorViewModel
import io.github.linker2k.myembro.ui.fonts.FontLibraryScreen
import io.github.linker2k.myembro.ui.home.DesignPickerScreen
import io.github.linker2k.myembro.ui.home.HomeScreen
import io.github.linker2k.myembro.ui.lettering.LetteringScreen
import io.github.linker2k.myembro.ui.settings.SettingsScreen
import io.github.linker2k.myembro.ui.settings.SettingsViewModel
import io.github.linker2k.myembro.ui.sources.FreeDesignSourcesScreen
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import io.github.linker2k.myembro.ui.theme.installMyembroSplashScreen
import io.github.linker2k.myembro.ui.photo.PhotoScreen
import io.github.linker2k.myembro.ui.photo.PhotoViewModel
import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.engine.edit.Transform
import java.util.UUID
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewer: ViewerViewModel by viewModels()
    private val library: LibraryViewModel by viewModels()
    private val editor: EditorViewModel by viewModels()
    private val photo: PhotoViewModel by viewModels()
    private val aiPictureDraft: io.github.linker2k.myembro.ui.photo.AiPictureDraft by viewModels()
    private val emoji: io.github.linker2k.myembro.ui.emoji.EmojiViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()

    /** [PICK_DESIGN] is "start from my design" in the new-project sheet: a pick starts a project. */
    private enum class Screen { LIBRARY, PICK_DESIGN, VIEWER, EDITOR, SETTINGS, LETTERING, FREE_DESIGNS, PHOTO, EMOJI }

    /** Screens above home; back pops one. Home is always the root. */
    private val stack = mutableStateListOf<Screen>()

    /** Library path of the design in the viewer, or null for a file read without importing. */
    private var openPath by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installMyembroSplashScreen()
        super.onCreate(savedInstanceState)
        // Saved Ink/Stitch text objects resolve their installed font by id.
        io.github.linker2k.myembro.fonts.FontRepository.installInkStitchLoader(this)
        // AI connection (experimental) resumes when it was left on.
        io.github.linker2k.myembro.relay.RelayService.sync(this)
        enableEdgeToEdge()
        openPath = savedInstanceState?.getString(KEY_OPEN)
        savedInstanceState?.getStringArrayList(KEY_STACK)?.let { names -> stack += names.map(Screen::valueOf) }
        handleIntent(intent, dispatchCurrent = savedInstanceState == null)
        setContent {
            val context = LocalContext.current
            val openDocument = rememberDesignImporter(onFiles = ::importUris,
                onFolder = { library.import(emptyList(), tree = it, onComplete = ::afterImport) })
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
            // Save/share run only after the machine check; warnings let the user continue or cancel.
            var preflight by remember { mutableStateOf<Pair<List<String>, () -> Unit>?>(null) }
            val checked = { action: () -> Unit ->
                val warnings = viewer.preflight()
                if (warnings.isEmpty()) action() else preflight = warnings to action
            }
            val back: () -> Unit = {
                if (stack.removeLastOrNull() == Screen.EDITOR) library.refresh()
            }
            // Each screen calls this through its own leave path, so unsaved work is handled as on back.
            val goHome: () -> Unit = {
                if (Screen.EDITOR in stack.popToHome()) library.refresh()
            }
            val newProject = { hoop: HoopSpec ->
                editor.newProject(hoop)
                stack += Screen.EDITOR
            }
            // Font picked in the font library; the lettering screen starts from it.
            var letteringFontId by rememberSaveable { mutableStateOf("gowun-batang") }
            // Settings opened from the home version label scroll to the version information.
            var settingsAtVersion by rememberSaveable { mutableStateOf(false) }
            var showAiPicture by remember { mutableStateOf(false) }
            MyembroTheme {
                BackHandler(enabled = stack.isNotEmpty(), onBack = back)
                // One back already reaches Home from the first level; deeper screens get a Home button.
                CompositionLocalProvider(LocalGoHome provides goHome.takeIf { stack.size >= 2 }) {
                if (showAiPicture) {
                    val hoop = if (stack.lastOrNull() == Screen.EDITOR) editor.project.hoop else settings.settings.defaultFrame.spec
                    io.github.linker2k.myembro.ui.photo.AiPictureDialog(hoop,
                        io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoPreparationPolicy.photoMaxWidthMm(hoop),
                        settings.settings.myThreads, draft = aiPictureDraft, onDismiss = { showAiPicture = false })
                }
                when (stack.lastOrNull()) {
                    null -> HomeScreen(
                        onNewProject = { hoop -> newProject(hoop.spec()) },
                        onOpenRecent = ::openDesign,
                        onImport = openDocument,
                        onLettering = { stack += Screen.LETTERING },
                        onOpenLibrary = { stack += Screen.LIBRARY },
                        onSettings = { settingsAtVersion = false; stack += Screen.SETTINGS },
                        onVersion = { settingsAtVersion = true; stack += Screen.SETTINGS },
                        onCreateFromDesign = { stack += Screen.PICK_DESIGN },
                        letteringAvailable = true,
                        onFreeDesigns = { stack += Screen.FREE_DESIGNS },
                        onNewProjectFrame = { spec -> newProject(spec) },
                        onPhoto = { aiPictureDraft.cancelExpectedImage(); photo.begin(); stack += Screen.PHOTO },
                        onAiPicture = { showAiPicture = true },
                        onEmoji = { emoji.close(); stack += Screen.EMOJI },
                    )
                    Screen.LIBRARY -> LibraryScreen(library, onOpen = ::openDesign, onFreeDesigns = { stack += Screen.FREE_DESIGNS })
                    Screen.FREE_DESIGNS -> FreeDesignSourcesScreen(onBack = back)
                    Screen.PICK_DESIGN -> DesignPickerScreen(
                        onSelect = { file ->
                            editor.editDesign(file)
                            stack[stack.lastIndex] = Screen.EDITOR
                        },
                        onBack = back,
                    )
                    Screen.PHOTO -> PhotoScreen(photo, settings.settings, onBack = back,
                        onEdit = {
                            photo.render?.pattern?.let { pattern ->
                                val b = pattern.bounds
                                val transform = if (b == null) Transform() else Transform(
                                    x = ((b.minX.toLong() + b.maxX) / 2).toDouble(),
                                    y = ((b.minY.toLong() + b.maxY) / 2).toDouble())
                                editor.startWith(StitchObject(UUID.randomUUID().toString(), context.getString(R.string.photo_name), pattern,
                                    transform = transform), photo.hoop)
                                stack += Screen.EDITOR
                            }
                        }, onSend = { file ->
                            library.refresh(); library.clearSelection(); library.toggle(file)
                            stack += Screen.LIBRARY; library.exportToUsb()
                        })
                    Screen.EMOJI -> io.github.linker2k.myembro.ui.emoji.EmojiScreen(emoji, settings.settings, onBack = back, onEdit = {
                        emoji.render?.pattern?.let { pattern ->
                            val b = pattern.bounds
                            val transform = if (b == null) Transform() else Transform(
                                x = ((b.minX.toLong() + b.maxX) / 2).toDouble(), y = ((b.minY.toLong() + b.maxY) / 2).toDouble())
                            editor.startWith(StitchObject(UUID.randomUUID().toString(), emoji.selected?.name ?: "emoji", pattern,
                                transform = transform), emoji.hoop)
                            stack += Screen.EDITOR
                        }
                    })
                    Screen.SETTINGS -> SettingsScreen(settings, onBack = back, showVersion = settingsAtVersion)
                    Screen.EDITOR -> EditorScreen(editor, onBack = back, onAiPicture = { showAiPicture = true })
                    Screen.LETTERING -> {
                        // Exact selected frame, including custom sizes (not just the integer-mm presets).
                        val hoop = settings.settings.defaultFrame.spec
                        // The font library is drawn over the lettering screen, which keeps what was typed.
                        var showFonts by rememberSaveable { mutableStateOf(false) }
                        var fontsText by rememberSaveable { mutableStateOf("") }
                        Box {
                            LetteringScreen(
                                onBack = back,
                                onAdd = { text ->
                                    editor.startWith(text, hoop)
                                    stack[stack.lastIndex] = Screen.EDITOR
                                },
                                hoop = hoop,
                                initialFontId = letteringFontId,
                                onOpenFonts = { text -> fontsText = text; showFonts = true },
                            )
                            if (showFonts) {
                                BackHandler { showFonts = false }
                                FontLibraryScreen(
                                    onBack = { showFonts = false },
                                    onPick = { id -> letteringFontId = id; showFonts = false },
                                    text = fontsText,
                                )
                            }
                        }
                    }
                    Screen.VIEWER -> ViewerScreen(
                        viewer,
                        onOpenFile = openDocument,
                        onSaveFile = {
                            checked {
                                viewer.prepareSave()?.let { name ->
                                    try {
                                        saveDocument.launch(name)
                                    } catch (error: Exception) {
                                        viewer.savePickerFailed(error)
                                    }
                                }
                            }
                        },
                        onShareFile = { checked(viewer::share) },
                        onEdit = openPath?.let { path ->
                            {
                                val file = File(path)
                                if (file.extension.equals("myembro", true)) editor.openProject(file) else editor.editDesign(file)
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
                val importProgress by library.importProgress.collectAsState()
                // LibraryScreen also works standalone and owns these dialogs while it is visible.
                if (stack.lastOrNull() != Screen.LIBRARY) {
                    DesignImportDialogs(importProgress, library.importReport, library::dismissImportReport, library.importDecisions, library::cancelImport)
                }
                // After a process restart, offer the editor's autosaved draft on Home once per session;
                // dismissing keeps the draft (the editor offers it again on entry).
                var recoveryDismissed by rememberSaveable { mutableStateOf(false) }
                if (stack.isEmpty() && !recoveryDismissed) {
                    EditorRecoveryDialog(editor, onRestored = { stack += Screen.EDITOR }, onDismiss = { recoveryDismissed = true })
                }
                val machine = settings.settings.machineModel
                preflight?.let { (warnings, action) ->
                    PreflightDialog(machine, warnings, onContinue = { preflight = null; action() }, onCancel = { preflight = null })
                }
                library.preflightWarnings?.let { warnings ->
                    PreflightDialog(machine, warnings, onContinue = library::confirmPreflight, onCancel = library::cancelPreflight)
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

    private fun handleIntent(intent: Intent?, dispatchCurrent: Boolean = true) {
        try {
            routeLaunchIntent(intent?.action, intent?.flags ?: 0, dispatchCurrent,
                onUsbHistoryLaunch = {
                    // Fresh launcher intent: do not copy/unparcel extras from a possibly detached device.
                    setIntent(Intent.makeMainActivity(componentName))
                },
                onCurrentLaunch = {
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
                        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> if (intent.action == Intent.ACTION_SEND && (intent.type?.startsWith("image/") == true || sharedUris(intent).firstOrNull()?.let { io.github.linker2k.myembro.ui.photo.isSvgPicture(this, it) } == true)) {
                            sharedUris(intent).firstOrNull()?.let { uri ->
                                val requestedColors = aiPictureDraft.consumeExpectedColors()
                                if (requestedColors == null) photo.begin() else photo.beginAiPicture(requestedColors)
                                photo.configure(settings.settings); photo.load(uri)
                                if (stack.lastOrNull() != Screen.PHOTO) stack += Screen.PHOTO
                            }
                        } else agentReply(intent)?.let { text ->
                            // A chatbot reply shared back: it goes to the editor's paste box, never to the library.
                            editor.agent.openReply(text)
                            if (stack.lastOrNull() != Screen.EDITOR) stack += Screen.EDITOR
                        } ?: importUris(sharedUris(intent))
                    }
                },
            )
        } catch (error: Exception) {
            // A stale external launch must not close the existing task or prevent normal startup.
            Log.w("MyembroLaunch", "Ignoring failed launch intent", error)
        }
    }

    /** Plain text shared to myembro (no stream attached) is a chatbot reply for "Ask AI". */
    private fun agentReply(intent: Intent): String? =
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true &&
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) == null)
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        else null

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
        library.import(uris, onComplete = ::afterImport)
    }

    private fun afterImport(report: io.github.linker2k.myembro.library.DesignImportReport) {
        if (report.imported.size == 1) openDesign(report.imported.single())
        else if (stack.lastOrNull() != Screen.LIBRARY) stack += Screen.LIBRARY
    }

    private companion object {
        const val KEY_OPEN = "open_path"
        const val KEY_STACK = "screen_stack"
    }
}
