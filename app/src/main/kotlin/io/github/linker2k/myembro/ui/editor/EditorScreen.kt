// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.SewingPosition
import io.github.linker2k.myembro.engine.edit.exceedsHoop
import io.github.linker2k.myembro.ui.fonts.FontLibraryScreen
import io.github.linker2k.myembro.ui.lettering.LetteringScreen
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import io.github.linker2k.myembro.ui.format.Dimensions
import io.github.linker2k.myembro.ui.rememberDesignImporter
import io.github.linker2k.myembro.ui.DesignImportDialogs
import io.github.linker2k.myembro.ui.agent.AgentAskButton
import io.github.linker2k.myembro.ui.agent.AgentAssistHost
import io.github.linker2k.myembro.ui.agent.AgentPendingCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Standalone entry point. Brand, dark mode, typography and surfaces come from MyembroTheme. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit,
    multiPositionPositions: List<SewingPosition> = emptyList(),
    onMultiPositionExport: ((Project, List<SewingPosition>) -> Unit)? = null,
    onAiPicture: (() -> Unit)? = null,
) {
    // Screens drawn inside the editor (lettering, fonts) get no Home of their own: leaving goes
    // through the editor's unsaved-change check, from its own top bar.
    val home = io.github.linker2k.myembro.ui.LocalGoHome.current
    androidx.compose.runtime.CompositionLocalProvider(io.github.linker2k.myembro.ui.LocalGoHome provides null) {
    MyembroTheme {
        val colors = MaterialTheme.colorScheme
        BackHandler(onBack = { vm.leave(onBack) })
        // AI connection (relay): it works on this same project (AiWorkspace) whether or not the editor is on
        // screen, so its edits show here at once; the removal question is asked from MainActivity.
        // AI connection: "Send a photo to AI" (menu) opens the picker, then asks consent for that photo.
        val relayOn = io.github.linker2k.myembro.relay.RelayService.connection.collectAsState().value != null
        var relayPhotoRequest by remember { mutableIntStateOf(0) }
        if (relayOn) io.github.linker2k.myembro.relay.RelayPhotoHandoff(vm.relayPhotos, launch = relayPhotoRequest)
        LaunchedEffect(relayOn) { if (relayOn) vm.prewarmRelayFonts() }
        LaunchedEffect(vm.project.hoop, vm.settings.machineModel, multiPositionPositions) { vm.configureSewingPositions(multiPositionPositions) }
        var splitPreview by remember { mutableStateOf<EditorSplitPreview?>(null) }
        var showDesigns by remember { mutableStateOf(false) }
        var showLayers by remember { mutableStateOf(false) }
        var hoopMenu by remember { mutableStateOf(false) }
        var projectMenu by remember { mutableStateOf(false) }
        var showOpenProjects by remember { mutableStateOf(false) }
        var showProperties by remember { mutableStateOf(false) }
        var fitRequest by remember { mutableIntStateOf(0) }
        var activeTool by rememberSaveable { mutableIntStateOf(0) }
        var inspectorTab by rememberSaveable { mutableIntStateOf(0) }
        var inspectorExpanded by rememberSaveable { mutableStateOf(false) }
        var navigationOnly by rememberSaveable { mutableStateOf(false) }
        var showJumps by rememberSaveable { mutableStateOf(false) }
        var preview by remember { mutableFloatStateOf(1f) }
        var exportName by remember { mutableStateOf("myembro") }
        val enabled = !vm.interactionBlocked && !vm.gestureInProgress
        val importDesign = rememberDesignImporter(
            onFiles = { vm.importDesigns(it); preview = 1f },
            onFolder = { vm.importDesigns(emptyList(), tree = it); preview = 1f })
        // Keep the local design collection available alongside device files/folders.
        val addDesign = { vm.refreshDesigns(); showDesigns = true }
        val openDocument = rememberDesignImporter(
            onFiles = { vm.importDesigns(it, openSingleProject = true); fitRequest++; preview = 1f },
            onFolder = { vm.importDesigns(emptyList(), tree = it); preview = 1f })
        val importProgress by vm.importProgress.collectAsState()
        DesignImportDialogs(importProgress, vm.importReport, vm::dismissImportReport, vm.importDecisions, vm::cancelImport)
        val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) vm.saveProject(exportName, uri, afterSave = vm::finishNamedSave) else vm.cancelNamedSave()
        }
        val snackbar = remember { SnackbarHostState() }
        val message = vm.message
        LaunchedEffect(message) {
            if (message != null) {
                snackbar.showSnackbar(message)
                if (vm.message == message) vm.message = null
            }
        }
        val snapshot=vm.project
        val gesturing=vm.gestureInProgress
        val rendering=vm.rendering
        val positions=vm.sewingPositions
        val multiPosition=vm.multiPositionFrame!=null
        val overflow by produceState(false,snapshot,gesturing,rendering,positions,multiPosition) {
            value=withContext(Dispatchers.Default) {
                !gesturing && !rendering && (snapshot.exceedsHoop() ||
                    (multiPosition && positions.isNotEmpty() && snapshot.positionFit(positions).outsideIds.isNotEmpty()))
            }
        }
        val outside=!gesturing && !rendering && overflow
        Scaffold(containerColor = colors.background, snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
                    if(vm.rendering && !vm.gestureInProgress) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.editor_rebuilding_stitches),Modifier.padding(horizontal=16.dp),
                            style=MaterialTheme.typography.labelSmall)
                    }
                    TopAppBar(title = {
                        Column {
                            Text(stringResource(R.string.editor_title), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium)
                            Text((if (vm.dirty) "• " else "") + (vm.selected?.name ?: stringResource(R.string.editor_composition)), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
                        navigationIcon = { Row {
                            IconButton(onClick = { vm.leave(onBack) }, enabled = enabled) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.editor_back))
                            }
                            // Same unsaved-change dialog and draft handling as back.
                            io.github.linker2k.myembro.ui.HomeButton(enabled, home) { goHome -> vm.leave(goHome) }
                        } }, actions = {
                            AgentAskButton(vm.agent, vm.project, enabled)
                            EditorHistoryButton(vm, redo = false)
                            EditorHistoryButton(vm, redo = true)
                            Box {
                                IconButton(onClick = { projectMenu = true }, enabled = enabled) { Icon(Icons.Default.MoreVert, stringResource(R.string.editor_project_menu)) }
                                DropdownMenu(projectMenu, onDismissRequest = { projectMenu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_project_save)) },
                                        onClick = { projectMenu = false; vm.requestProjectSave() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_project_open)) },
                                        onClick = { projectMenu = false; vm.refreshProjects(); showOpenProjects = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_save)) }, enabled = vm.project.objects.any { it.visible },
                                        onClick = { projectMenu = false; vm.save() })
                                    if (relayOn) DropdownMenuItem(text = { Text(stringResource(R.string.relay_photo_send)) },
                                        onClick = { projectMenu = false; relayPhotoRequest++ })
                                }
                            }
                        })
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            SuggestionChip(onClick = { hoopMenu = true }, enabled = enabled && vm.availableHoops.isNotEmpty(),
                                label = { Text(stringResource(R.string.editor_hoop_size, Dimensions.size(vm.project.hoop.width / 10.0, vm.project.hoop.height / 10.0))) },
                                icon = { Icon(Icons.Default.CropSquare, null, Modifier.size(16.dp)) })
                            DropdownMenu(hoopMenu, onDismissRequest = { hoopMenu = false }) {
                                vm.availableHoops.forEach { hoop -> DropdownMenuItem(text = { Text(Dimensions.size(hoop.width / 10.0, hoop.height / 10.0)) },
                                    onClick = { vm.setHoop(hoop); hoopMenu = false }) }
                                vm.multiPositionFrames.forEach { frame -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_multi_frame, Dimensions.size(frame.coverage.width / 10.0, frame.coverage.height / 10.0))) },
                                    onClick = { vm.setMultiPositionFrame(frame); hoopMenu = false }) }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.editor_object_count, vm.project.objects.size),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        FilledTonalIconButton(onClick = vm::save, enabled = enabled && vm.project.objects.any { it.visible }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Save, stringResource(R.string.editor_save), Modifier.size(20.dp))
                        }
                    }
                    if (vm.multiPositionFrame != null) {
                        val fit = remember(vm.project, vm.sewingPositions, vm.gestureInProgress) {
                            if (vm.gestureInProgress) EditorPositionFit(emptySet(), emptySet()) else vm.positionFit
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(stringResource(if (vm.sewingPositions.isEmpty()) R.string.editor_positions_unconfirmed else R.string.editor_positions_count,
                                vm.sewingPositions.size), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            if (!vm.gestureInProgress && fit.spanningIds.isNotEmpty()) Text(
                                stringResource(R.string.editor_positions_spanning, fit.spanningIds.size),
                                style = MaterialTheme.typography.labelSmall, color = colors.error)
                            TextButton(onClick = { vm.prepareMultiPositionExport { splitPreview = it } },
                                enabled = enabled && vm.sewingPositions.isNotEmpty() && onMultiPositionExport != null) {
                                Text(stringResource(R.string.editor_split_export))
                            }
                        }
                    }
                    AgentPendingCard(vm.agent)
                    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (outside) Row(Modifier.fillMaxWidth().background(colors.errorContainer).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WarningAmber, null, tint = colors.onErrorContainer, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.editor_outside), style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
                    }
                }
            }, bottomBar = {
                EditorToolTabs(enabled, activeTool,
                    onSelect = { activeTool = 0; navigationOnly = false; vm.toggleSelectionMode() }, onAdd = addDesign,
                    onTransform = { activeTool = 2; inspectorTab = 0; inspectorExpanded = true },
                    onText = { activeTool = 3; vm.openLettering() }, onLayers = { showLayers = true })
            }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val layout = editorLayout(maxWidth.value)
                val inspectorHeight = minOf(360.dp, maxHeight * 0.56f)
                val miniLayerWidth = if (maxWidth < 380.dp) 116.dp else 132.dp
                val layersHeight = (maxHeight * 0.3f).coerceIn(112.dp, 248.dp)
                val canvas: @Composable (Modifier, Boolean) -> Unit = { modifier, floatingTools ->
                    Box(modifier) {
                        EditorCanvas(vm, fitRequest, Modifier.fillMaxSize(), navigationOnly, showJumps, preview)
                        if (floatingTools) EditorToolRail(navigationOnly, enabled, vm.snapEnabled,
                            onSelect = { navigationOnly = false }, onPan = { navigationOnly = true },
                            onFit = { fitRequest++; preview = 1f }, onSnap = vm::toggleSnap,
                            modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 36.dp))
                        if (floatingTools && vm.project.objects.isNotEmpty()) MiniLayerPanel(vm, enabled, addDesign, { showLayers = true },
                            Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 12.dp).width(miniLayerWidth))
                        if (vm.project.objects.isEmpty()) FilledTonalButton(onClick = addDesign, enabled = enabled,
                            modifier = Modifier.align(Alignment.Center)) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.editor_add))
                        }
                        if (vm.selectionMode) Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.small,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text(stringResource(R.string.editor_selected_count, vm.selectedIds.size), style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = vm::selectAll, enabled = enabled) { Text(stringResource(R.string.editor_select_all)) }
                                IconButton(onClick = vm::clearSelection, enabled = enabled) { Icon(Icons.Default.Deselect, stringResource(R.string.editor_clear_selection)) }
                            }
                        }
                    }
                }
                val inspector: @Composable (Modifier) -> Unit = { modifier ->
                    EditorInspector(vm, inspectorTab, { inspectorTab = it }, layout.expanded || inspectorExpanded,
                        { inspectorExpanded = !inspectorExpanded }, showJumps, { showJumps = it }, preview, { preview = it },
                        { showProperties = true }, modifier, docked = layout.expanded)
                }
                if (layout.expanded) Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorToolRail(navigationOnly, enabled, vm.snapEnabled,
                        onSelect = { navigationOnly = false }, onPan = { navigationOnly = true },
                        onFit = { fitRequest++; preview = 1f }, onSnap = vm::toggleSnap,
                        modifier = Modifier.padding(top = 24.dp))
                    canvas(Modifier.weight(1f).fillMaxHeight(), false)
                    Column(Modifier.width(layout.panelWidthDp.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EditorLayerPanel(vm, addDesign, Modifier.fillMaxWidth().height(layersHeight))
                        inspector(Modifier.fillMaxWidth().weight(1f))
                    }
                } else Column(Modifier.fillMaxSize()) {
                    canvas(Modifier.fillMaxWidth().weight(1f), true)
                    inspector(Modifier.fillMaxWidth().heightIn(max = inspectorHeight))
                }
            }
        }
        splitPreview?.let { ready -> EditorSplitDialog(ready, onCancel = { splitPreview = null }, onExport = {
            splitPreview = null
            onMultiPositionExport?.invoke(ready.project, ready.positions)
        }) }
        if (vm.replacementRequested && vm.savePrompt == null) AlertDialog(
            onDismissRequest = { if (!vm.busy) vm.cancelReplacement() },
            title = { Text(stringResource(R.string.editor_unsaved_title)) },
            text = { Text(stringResource(R.string.editor_unsaved_body)) },
            confirmButton = { TextButton(onClick = vm::saveAndContinue, enabled = !vm.busy) {
                Text(stringResource(R.string.editor_unsaved_save))
            } },
            dismissButton = {
                Row {
                    TextButton(onClick = vm::discardAndContinue, enabled = !vm.busy) { Text(stringResource(R.string.editor_discard)) }
                    TextButton(onClick = vm::cancelReplacement, enabled = !vm.busy) { Text(stringResource(R.string.editor_cancel)) }
                }
            })
        EditorRecoveryDialog(vm, onDismiss = onBack)
        vm.exportProblems?.let { problems -> AlertDialog(
            onDismissRequest = vm::dismissExportProblems,
            title = { Text(stringResource(R.string.editor_export_blocked)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                problems.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            } },
            confirmButton = { TextButton(onClick = vm::dismissExportProblems) { Text(stringResource(R.string.editor_apply)) } }) }
        if (showDesigns) ModalBottomSheet(onDismissRequest = { showDesigns = false }) {
            Text(stringResource(R.string.editor_add), Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { showDesigns = false; importDesign() }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_title))
            }
            if (onAiPicture != null) Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                io.github.linker2k.myembro.ui.home.AiPictureCreationChoice(enabled = !vm.busy) {
                    showDesigns = false; onAiPicture()
                }
            }
            if (vm.designs.isEmpty()) Text(stringResource(R.string.editor_library_empty), Modifier.padding(20.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(vm.designs, key = { it.path }) { file ->
                    ListItem(headlineContent = { Text(file.name) }, leadingContent = { Icon(Icons.Default.Gesture, null) },
                        modifier = Modifier.clickable { vm.addDesign(file); preview = 1f; showDesigns = false })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (vm.letteringOpen) {
            val original = vm.letteringObject
            val projectSnapshot = vm.project
            val letteringTarget = vm.letteringTargetId
            val sewingOptions = vm.settings.sewing
            var background by remember { mutableStateOf<io.github.linker2k.myembro.engine.model.EmbPattern?>(null) }
            LaunchedEffect(projectSnapshot, letteringTarget, sewingOptions) {
                background = withContext(Dispatchers.Default) {
                    projectSnapshot.copy(objects = projectSnapshot.objects.filterNot { it.id == letteringTarget }).flatten(sewingOptions)
                }
            }
            key(vm.letteringTargetId) {
                var showFonts by rememberSaveable { mutableStateOf(false) }
                var pickedFontId by rememberSaveable { mutableStateOf<String?>(null) }
                var fontsText by rememberSaveable { mutableStateOf("") }
                val closeFonts = { showFonts = false }
                ModalBottomSheet(onDismissRequest = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
                        confirmValueChange = { it != SheetValue.Hidden }), sheetMaxWidth = 720.dp,
                    dragHandle = null,
                    properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false)) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(0.96f)) {
                        // Keep the draft composed underneath the library: text and every setting survive a pick/back.
                        LetteringScreen(onBack = vm::dismissLettering,
                            onAdd = { if (vm.commitLettering(it)) { preview = 1f; activeTool = 0 } },
                            onOpenFonts = { text -> fontsText = text; showFonts = true },
                            modifier = Modifier.fillMaxSize(), hoop = vm.project.hoop,
                            // New text starts in the last font used (shared with the AI connection); app text is Korean first.
                            initialFontId = pickedFontId ?: if (original == null) vm.lastFonts.korean else null,
                            backgroundPattern = background, initialObject = original)
                        if (showFonts) {
                            BackHandler(onBack = closeFonts)
                            FontLibraryScreen(onBack = closeFonts,
                                onPick = { id -> pickedFontId = id; closeFonts() }, text = fontsText)
                        }
                    }
                }
            }
        }
        if (showLayers) LayerSheet(vm) { showLayers = false }
        if (showProperties && vm.selectedIds.size == 1) ObjectProperties(vm) { showProperties = false }
        vm.savePrompt?.let { request ->
            SaveProjectDialog(initialName = request.suggestedName,
                title = if (request.kind == EditorSaveKind.PROJECT) R.string.editor_project_save else R.string.editor_save,
                onDismiss = vm::cancelNamedSave, onSave = { vm.submitSaveName(it) },
                onExport = if (request.kind == EditorSaveKind.PROJECT) { name ->
                    exportName = name
                    vm.hideSavePrompt()
                    exportDocument.launch(if (name.endsWith(".myembro")) name else "$name.myembro")
                } else null)
        }
        vm.overwriteName?.let { name -> AlertDialog(
            onDismissRequest = vm::cancelOverwrite,
            title = { Text(stringResource(R.string.editor_overwrite_title)) },
            text = { Text(stringResource(R.string.editor_overwrite_body, name)) },
            confirmButton = { TextButton(onClick = { vm.submitSaveName(name, overwrite = true) }) { Text(stringResource(R.string.editor_overwrite)) } },
            dismissButton = { TextButton(onClick = vm::cancelOverwrite) { Text(stringResource(R.string.editor_cancel)) } }) }
        AgentAssistHost(vm.agent)
        if (showOpenProjects) ProjectPicker(vm, onDismiss = { showOpenProjects = false },
            onOpen = { file -> vm.openProject(file); showOpenProjects = false; fitRequest++; preview = 1f },
            onDevice = { showOpenProjects = false; openDocument() })
    }
    }
}

@Composable
internal fun EditorTool(icon: ImageVector, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.height(4.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayerSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.editor_layers), Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.editor_layer_hint), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        if (vm.project.objects.isEmpty()) Text(stringResource(R.string.editor_no_layers), Modifier.padding(20.dp))
        EditorLayerList(vm, Modifier.fillMaxWidth().heightIn(max = 480.dp)) { id ->
            onDismiss(); vm.openLettering(id)
        }
        Spacer(Modifier.height(24.dp))
    }
}
