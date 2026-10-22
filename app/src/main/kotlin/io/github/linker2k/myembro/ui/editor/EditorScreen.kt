// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.ui.theme.MyembroTheme

/** Standalone entry point. Brand, dark mode, typography and surfaces come from MyembroTheme. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit) {
    MyembroTheme {
        val colors = MaterialTheme.colorScheme
        BackHandler(onBack = onBack)
        var showDesigns by remember { mutableStateOf(false) }
        var showLayers by remember { mutableStateOf(false) }
        var hoopMenu by remember { mutableStateOf(false) }
        var projectMenu by remember { mutableStateOf(false) }
        var showSaveProject by remember { mutableStateOf(false) }
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
        val enabled = !vm.busy && !vm.gestureInProgress
        val addDesign = { vm.refreshDesigns(); showDesigns = true }
        val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) { vm.openProject(uri); fitRequest++; preview = 1f }
        }
        val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) vm.saveProject(exportName, uri)
        }
        val snackbar = remember { SnackbarHostState() }
        val message = vm.message
        LaunchedEffect(message) {
            if (message != null) {
                snackbar.showSnackbar(message)
                if (vm.message == message) vm.message = null
            }
        }
        val outside = remember(vm.project, vm.gestureInProgress) { vm.hoopOverflowVisible }
        Scaffold(containerColor = colors.background, snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
                    TopAppBar(title = {
                        Column {
                            Text(stringResource(R.string.editor_title), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium)
                            Text(vm.selected?.name ?: stringResource(R.string.editor_composition), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
                        navigationIcon = { IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.editor_back))
                        } }, actions = {
                            IconButton(onClick = vm::undo, enabled = enabled && vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo)) }
                            IconButton(onClick = vm::redo, enabled = enabled && vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
                            Box {
                                IconButton(onClick = { projectMenu = true }, enabled = enabled) { Icon(Icons.Default.MoreVert, stringResource(R.string.editor_project_menu)) }
                                DropdownMenu(projectMenu, onDismissRequest = { projectMenu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_project_save)) },
                                        onClick = { projectMenu = false; showSaveProject = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_project_open)) },
                                        onClick = { projectMenu = false; vm.refreshProjects(); showOpenProjects = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_save)) }, enabled = vm.project.objects.any { it.visible },
                                        onClick = { projectMenu = false; vm.save() })
                                }
                            }
                        })
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            SuggestionChip(onClick = { hoopMenu = true }, enabled = enabled,
                                label = { Text(stringResource(R.string.editor_hoop_size, vm.project.hoop.name)) },
                                icon = { Icon(Icons.Default.CropSquare, null, Modifier.size(16.dp)) })
                            DropdownMenu(hoopMenu, onDismissRequest = { hoopMenu = false }) {
                                HoopSpec.ALL.forEach { hoop -> DropdownMenuItem(text = { Text("${hoop.name} mm") },
                                    onClick = { vm.setHoop(hoop); hoopMenu = false }) }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.editor_object_count, vm.project.objects.size),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        FilledTonalIconButton(onClick = vm::save, enabled = enabled && vm.project.objects.any { it.visible }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Save, stringResource(R.string.editor_save), Modifier.size(20.dp))
                        }
                    }
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
                    onColors = { activeTool = 3; inspectorTab = 2; inspectorExpanded = true }, onLayers = { showLayers = true })
            }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val inspectorHeight = minOf(360.dp, maxHeight * 0.56f)
                val miniLayerWidth = if (maxWidth < 380.dp) 116.dp else 132.dp
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        EditorCanvas(vm, fitRequest, Modifier.fillMaxSize(), navigationOnly, showJumps, preview)
                        EditorToolRail(navigationOnly, enabled, vm.snapEnabled,
                            onSelect = { navigationOnly = false }, onPan = { navigationOnly = true },
                            onFit = { fitRequest++; preview = 1f }, onSnap = vm::toggleSnap,
                            modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 36.dp))
                        if (vm.project.objects.isNotEmpty()) MiniLayerPanel(vm, enabled, addDesign, { showLayers = true },
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
                    EditorInspector(vm, inspectorTab, { inspectorTab = it }, inspectorExpanded,
                        { inspectorExpanded = !inspectorExpanded }, showJumps, { showJumps = it }, preview, { preview = it },
                        { showProperties = true }, Modifier.fillMaxWidth().heightIn(max = inspectorHeight))
                }
            }
        }
        if (showDesigns) ModalBottomSheet(onDismissRequest = { showDesigns = false }) {
            Text(stringResource(R.string.editor_add), Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            if (vm.designs.isEmpty()) Text(stringResource(R.string.editor_library_empty), Modifier.padding(20.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(vm.designs, key = { it.path }) { file ->
                    ListItem(headlineContent = { Text(file.name) }, leadingContent = { Icon(Icons.Default.Gesture, null) },
                        modifier = Modifier.clickable { vm.addDesign(file); preview = 1f; showDesigns = false })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (showLayers) LayerSheet(vm) { showLayers = false }
        if (showProperties && vm.selectedIds.size == 1) ObjectProperties(vm) { showProperties = false }
        if (showSaveProject) SaveProjectDialog(onDismiss = { showSaveProject = false },
            onSave = { name -> vm.saveProject(name); showSaveProject = false },
            onExport = { name -> exportName = name; showSaveProject = false
                exportDocument.launch(if (name.endsWith(".myembro")) name else "$name.myembro") })
        if (showOpenProjects) ProjectPicker(vm, onDismiss = { showOpenProjects = false },
            onOpen = { file -> vm.openProject(file); showOpenProjects = false; fitRequest++; preview = 1f },
            onDevice = { showOpenProjects = false; openDocument.launch(arrayOf("*/*")) })
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
    val colors = MaterialTheme.colorScheme
    val rowHeight = with(LocalDensity.current) { 64.dp.toPx() }
    val earlier = stringResource(R.string.editor_layer_earlier)
    val later = stringResource(R.string.editor_layer_later)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Text(stringResource(R.string.editor_layers), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.editor_layer_hint), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        if (vm.project.objects.isEmpty()) Text(stringResource(R.string.editor_no_layers), Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(vm.project.objects, key = { it.id }) { obj ->
                var offset by remember { mutableFloatStateOf(0f) }
                var dragging by remember { mutableStateOf(false) }
                val index = vm.project.objects.indexOfFirst { it.id == obj.id }
                Row(Modifier.fillMaxWidth().height(64.dp)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) offset else 0f }
                    .background(if (obj.id in vm.selectedIds) colors.primaryContainer else colors.surface)
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(earlier) { vm.reorder(obj.id, index - 1); true },
                            CustomAccessibilityAction(later) { vm.reorder(obj.id, index + 1); true },
                        )
                    }
                    .clickable { vm.select(obj.id) }
                    .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DragIndicator, stringResource(R.string.editor_drag_layer),
                        Modifier.size(48.dp).pointerInput(obj.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { vm.beginGesture(); dragging = true },
                                onDragEnd = { vm.endGesture(); dragging = false; offset = 0f },
                                onDragCancel = { vm.endGesture(); dragging = false; offset = 0f },
                                onDrag = { change, delta ->
                                    change.consume()
                                    offset += delta.y
                                    val current = vm.project.objects.indexOfFirst { it.id == obj.id }
                                    val steps = (offset / rowHeight).toInt()
                                    if (steps != 0) {
                                        val target = (current + steps).coerceIn(0, vm.project.objects.lastIndex)
                                        vm.reorder(obj.id, target, duringGesture = true)
                                        offset -= (target - current) * rowHeight
                                    }
                                })
                        }.padding(12.dp))
                    if (vm.selectionMode) Checkbox(checked = obj.id in vm.selectedIds,
                        onCheckedChange = { vm.toggleSelection(obj.id) }, enabled = obj.visible)
                    Text("${index + 1}", Modifier.width(28.dp), style = MaterialTheme.typography.labelLarge)
                    Text(obj.name, Modifier.weight(1f), maxLines = 1, color = colors.onSurface.copy(alpha = if (obj.visible) 1f else 0.45f))
                    IconButton(onClick = { vm.toggleVisible(obj.id) }) {
                        Icon(if (obj.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            stringResource(if (obj.visible) R.string.editor_hide else R.string.editor_show))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
