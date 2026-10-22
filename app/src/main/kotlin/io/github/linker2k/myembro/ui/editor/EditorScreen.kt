// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.linker2k.myembro.engine.edit.densityWarning
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.ui.lettering.LetteringSheet

private val Linen = Color(0xFFF6F0E6)
private val Ink = Color(0xFF2B2420)
private val Terracotta = Color(0xFFC4552D)

/** Standalone editor entry point; the host owns navigation and library refresh on return. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit) {
    val colors = lightColorScheme(primary = Terracotta, onPrimary = Color.White,
        background = Linen, onBackground = Ink, surface = Linen, onSurface = Ink,
        secondaryContainer = Color(0xFFEADACB), onSecondaryContainer = Ink)
    MaterialTheme(colorScheme = colors) {
        BackHandler(onBack = onBack)
        var showDesigns by remember { mutableStateOf(false) }
        var showLayers by remember { mutableStateOf(false) }
        var showLettering by remember { mutableStateOf(false) }
        var hoopMenu by remember { mutableStateOf(false) }
        var fitRequest by remember { mutableIntStateOf(0) }
        var projectMenu by remember { mutableStateOf(false) }
        var showSaveProject by remember { mutableStateOf(false) }
        var showOpenProjects by remember { mutableStateOf(false) }
        var showProperties by remember { mutableStateOf(false) }
        var exportName by remember { mutableStateOf("myembro") }
        val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) { vm.openProject(uri); fitRequest++ }
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
        val outside = remember(vm.project, vm.gestureInProgress) {
            vm.hoopOverflowVisible
        }
        val densityWarning = remember(vm.project) { vm.project.objects.any { it.visible && it.densityWarning } }
        val controlsEnabled = !vm.busy && !vm.gestureInProgress
        Scaffold(containerColor = Linen, snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
                    TopAppBar(title = { Text(stringResource(R.string.editor_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Linen),
                        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.editor_back)) } },
                        actions = {
                            TextButton(onClick = vm::save, enabled = controlsEnabled && vm.project.objects.any { it.visible }) {
                                Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.editor_save))
                            }
                            Box {
                                IconButton(onClick = { projectMenu = true }, enabled = controlsEnabled) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.editor_project_menu))
                                }
                                DropdownMenu(projectMenu, onDismissRequest = { projectMenu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_project_save)) },
                                        onClick = { projectMenu = false; showSaveProject = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_project_open)) },
                                        onClick = { projectMenu = false; vm.refreshProjects(); showOpenProjects = true })
                                }
                            }
                        })
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { hoopMenu = true }, enabled = controlsEnabled, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text(stringResource(R.string.editor_hoop_size, vm.project.hoop.name),
                                    Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = hoopMenu, onDismissRequest = { hoopMenu = false }) {
                                HoopSpec.ALL.forEach { hoop -> DropdownMenuItem(
                                    text = { Text("${hoop.name} mm") },
                                    onClick = { vm.setHoop(hoop); hoopMenu = false }) }
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = vm::undo, enabled = vm.canUndo && controlsEnabled) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo)) }
                        IconButton(onClick = vm::redo, enabled = vm.canRedo && controlsEnabled) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
                        IconButton(onClick = { fitRequest++ }, enabled = controlsEnabled) { Icon(Icons.Default.CenterFocusStrong, stringResource(R.string.editor_fit)) }
                    }
                    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (densityWarning) Text(stringResource(R.string.editor_density_warning),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = Terracotta)
                    if (outside) {
                        Row(Modifier.fillMaxWidth().background(Color(0xFFF8DED2)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WarningAmber, null, tint = Terracotta, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.editor_outside), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            bottomBar = {
                Surface(color = Color(0xFFFFFAF2), tonalElevation = 2.dp) {
                    Row(Modifier.navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        EditorTool(Icons.Default.Add, R.string.editor_add, enabled = controlsEnabled) { vm.refreshDesigns(); showDesigns = true }
                        EditorTool(Icons.Default.Checklist, R.string.editor_multi_select, enabled = controlsEnabled, onClick = vm::toggleSelectionMode)
                        EditorTool(Icons.Default.Tune, R.string.editor_properties, vm.selectedIds.size == 1 && controlsEnabled) { showProperties = true }
                        EditorTool(Icons.Default.TextFields, R.string.editor_text, enabled = controlsEnabled) { showLettering = true }
                        EditorTool(Icons.Default.Layers, R.string.editor_layers, controlsEnabled) { showLayers = true }
                        EditorTool(Icons.Default.ContentCopy, R.string.editor_duplicate, vm.selected != null && controlsEnabled, vm::duplicate)
                        EditorTool(Icons.Default.Flip, R.string.editor_mirror, vm.selected != null && controlsEnabled, vm::mirror)
                        EditorTool(Icons.AutoMirrored.Filled.RotateRight, R.string.editor_rotate, vm.selected != null && controlsEnabled, vm::rotate90)
                        EditorTool(Icons.Default.FilterCenterFocus, R.string.editor_center, vm.selected != null && controlsEnabled, vm::center)
                        EditorTool(Icons.Default.DeleteOutline, R.string.editor_delete, vm.selected != null && controlsEnabled, vm::delete)
                        EditorTool(Icons.AutoMirrored.Filled.Undo, R.string.editor_undo, vm.canUndo && controlsEnabled, vm::undo)
                        EditorTool(Icons.AutoMirrored.Filled.Redo, R.string.editor_redo, vm.canRedo && controlsEnabled, vm::redo)
                    }
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (vm.selectedIds.size > 1) stringResource(R.string.editor_selected_count, vm.selectedIds.size)
                        else vm.selected?.name ?: stringResource(R.string.editor_composition), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(stringResource(R.string.editor_object_count, vm.project.objects.size), style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(enabled = controlsEnabled, selected = vm.selectionMode, onClick = vm::toggleSelectionMode,
                        label = { Text(stringResource(R.string.editor_multi_select)) })
                    if (vm.selectionMode) {
                        TextButton(onClick = vm::selectAll, enabled = controlsEnabled) { Text(stringResource(R.string.editor_select_all)) }
                        TextButton(onClick = vm::clearSelection, enabled = controlsEnabled) { Text(stringResource(R.string.editor_clear_selection)) }
                    }
                    FilterChip(enabled = controlsEnabled, selected = vm.snapEnabled, onClick = vm::toggleSnap,
                        label = { Text(stringResource(R.string.editor_snap)) })
                }
                if (vm.selectedIds.size >= 2) AlignmentTools(vm)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    EditorCanvas(vm, fitRequest, Modifier.fillMaxSize())
                    if (vm.project.objects.isEmpty()) {
                        FilledTonalButton(onClick = { vm.refreshDesigns(); showDesigns = true }, modifier = Modifier.align(Alignment.Center)) {
                            Icon(Icons.Default.Add, null)
                            Text(stringResource(R.string.editor_add))
                        }
                    }
                }
                Text(stringResource(if (vm.selectionMode) R.string.editor_multi_hint else if (vm.selected == null) R.string.editor_navigation_hint else R.string.editor_selection_hint),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = 0.7f))
            }
        }
        if (showDesigns) ModalBottomSheet(onDismissRequest = { showDesigns = false }, containerColor = Linen) {
            Text(stringResource(R.string.editor_add), Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            if (vm.designs.isEmpty()) Text(stringResource(R.string.editor_library_empty), Modifier.padding(20.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(vm.designs, key = { it.path }) { file ->
                    ListItem(headlineContent = { Text(file.name) }, leadingContent = { Icon(Icons.Default.Gesture, null) },
                        modifier = Modifier.clickable { vm.addDesign(file); showDesigns = false },
                        colors = ListItemDefaults.colors(containerColor = Linen))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (showLayers) LayerSheet(vm) { showLayers = false }
        if (showLettering) LetteringSheet(hoop = vm.project.hoop, onDismiss = { showLettering = false }, onAdd = vm::addText)
        if (showProperties && vm.selectedIds.size == 1) ObjectProperties(vm) { showProperties = false }
        if (showSaveProject) SaveProjectDialog(onDismiss = { showSaveProject = false },
            onSave = { name -> vm.saveProject(name); showSaveProject = false },
            onExport = { name -> exportName = name; showSaveProject = false
                exportDocument.launch(if (name.endsWith(".myembro")) name else "$name.myembro") })
        if (showOpenProjects) ProjectPicker(vm, onDismiss = { showOpenProjects = false },
            onOpen = { file -> vm.openProject(file); showOpenProjects = false; fitRequest++ },
            onDevice = { showOpenProjects = false; openDocument.launch(arrayOf("*/*")) })
    }
}

@Composable
internal fun EditorTool(icon: ImageVector, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayerSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    val rowHeight = with(LocalDensity.current) { 64.dp.toPx() }
    val earlier = stringResource(R.string.editor_layer_earlier)
    val later = stringResource(R.string.editor_layer_later)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Linen) {
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
                    .background(if (obj.id in vm.selectedIds) Color(0xFFEADACB) else Linen)
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
                    Text(obj.name, Modifier.weight(1f), maxLines = 1, color = Ink.copy(alpha = if (obj.visible) 1f else 0.45f))
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
