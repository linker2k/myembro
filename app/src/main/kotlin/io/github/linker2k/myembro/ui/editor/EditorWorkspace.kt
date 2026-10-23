// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.ui.RenderData
import io.github.linker2k.myembro.ui.theme.ThreadTerracotta
import androidx.compose.ui.graphics.toArgb
import java.util.Locale
import io.github.linker2k.myembro.ui.format.Dimensions
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun EditorToolRail(navigationOnly: Boolean, enabled: Boolean, snapping: Boolean,
    onSelect: () -> Unit, onPan: () -> Unit, onFit: () -> Unit, onSnap: () -> Unit, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large,
        shadowElevation = 3.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RailButton(Icons.Default.NearMe, R.string.editor_select_tool, !navigationOnly, enabled, onSelect)
            RailButton(Icons.Default.PanTool, R.string.editor_pan_tool, navigationOnly, enabled, onPan)
            RailButton(Icons.Default.CenterFocusStrong, R.string.editor_fit, false, enabled, onFit)
            HorizontalDivider(Modifier.width(28.dp).padding(vertical = 4.dp))
            RailButton(Icons.Default.GridOn, R.string.editor_snap, snapping, enabled, onSnap)
        }
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (selected) FilledTonalIconButton(onClick, Modifier.size(44.dp), enabled = enabled,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary)) { Icon(icon, stringResource(label), Modifier.size(22.dp)) }
    else IconButton(onClick, Modifier.size(44.dp), enabled = enabled) { Icon(icon, stringResource(label), Modifier.size(22.dp)) }
}

@Composable
internal fun EditorToolTabs(enabled: Boolean, activeTool: Int,
    onSelect: () -> Unit, onAdd: () -> Unit, onTransform: () -> Unit, onText: () -> Unit, onLayers: () -> Unit) {
    val entries = listOf(Triple(Icons.Default.NearMe, R.string.editor_select_tool, onSelect),
        Triple(Icons.Default.AddCircleOutline, R.string.editor_design_tab, onAdd),
        Triple(Icons.Default.Tune, R.string.editor_transform_tab, onTransform),
        Triple(Icons.Default.TextFields, R.string.editor_text_tool, onText),
        Triple(Icons.Default.Layers, R.string.editor_layers, onLayers))
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        entries.forEachIndexed { index, (icon, label, action) ->
            NavigationBarItem(selected = activeTool == index, onClick = action, enabled = enabled,
                icon = { Icon(icon, null, Modifier.size(24.dp)) },
                label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer))
        }
    }
}

@Composable
internal fun MiniLayerPanel(vm: EditorViewModel, enabled: Boolean, onAdd: () -> Unit, onExpand: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = colors.surface,
        shadowElevation = 3.dp, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editor_layers), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = onAdd, enabled = enabled, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Add, stringResource(R.string.editor_add), Modifier.size(19.dp))
                }
            }
            vm.project.objects.take(3).forEachIndexed { index, obj ->
                Row(Modifier.fillMaxWidth().background(if (obj.id in vm.selectedIds) colors.primaryContainer else colors.surface)
                    .clickable(enabled = enabled) {
                        vm.select(obj.id)
                        if (obj is TextObject && obj.visible && !vm.selectionMode) vm.openLettering(obj.id)
                    }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp)) {
                        LayerThumbnail(obj, Modifier.fillMaxSize())
                        Text("${index + 1}", Modifier.align(Alignment.BottomStart).background(colors.surface.copy(alpha = 0.9f)),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(obj.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = if (obj.visible) colors.onSurface else colors.onSurfaceVariant)
                    IconButton(onClick = { vm.toggleVisible(obj.id) }, enabled = enabled, modifier = Modifier.size(36.dp)) {
                        Icon(if (obj.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            stringResource(if (obj.visible) R.string.editor_hide else R.string.editor_show), Modifier.size(17.dp))
                    }
                }
            }
            TextButton(onClick = onExpand, enabled = enabled, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(4.dp)) {
                Text(stringResource(R.string.editor_all_layers), style = MaterialTheme.typography.labelSmall)
                Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun LayerThumbnail(obj: DesignObject, modifier: Modifier) {
    val data = remember(obj) { RenderData(obj.localPattern()) }
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }
    Canvas(modifier) {
        drawRect(background)
        val b = data.pattern.bounds ?: return@Canvas
        val scale = minOf(size.width / maxOf(b.width, 1), size.height / maxOf(b.height, 1)) * 0.8f
        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            canvas.save(); canvas.translate(size.width / 2, size.height / 2); canvas.scale(scale, scale)
            canvas.translate(-(b.minX + b.maxX) / 2f, -(b.minY + b.maxY) / 2f)
            paint.strokeWidth = 1.2f / scale
            data.runs.forEach { run -> paint.color = run.color
                canvas.drawLines(data.stitchLines, run.start * 4, (run.end - run.start) * 4, paint) }
            canvas.restore()
        }
    }
}

@Composable
internal fun EditorInspector(vm: EditorViewModel, tab: Int, onTab: (Int) -> Unit, expanded: Boolean,
    onExpand: () -> Unit, showJumps: Boolean, onJumps: (Boolean) -> Unit, preview: Float, onPreview: (Float) -> Unit,
    onProperties: () -> Unit, modifier: Modifier, docked: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val enabled = !vm.busy && !vm.gestureInProgress
    val obj = vm.selected?.takeIf { vm.selectedIds.size == 1 }
    Surface(modifier, shape = if (docked) MaterialTheme.shapes.large else MaterialTheme.shapes.extraLarge,
        color = colors.surface, shadowElevation = if (docked) 0.dp else 6.dp) {
        Column {
            if (!docked) Box(Modifier.fillMaxWidth().height(16.dp).clickable(enabled = enabled, onClick = onExpand), contentAlignment = Alignment.Center) {
                HorizontalDivider(Modifier.width(32.dp), thickness = 3.dp, color = colors.outline)
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (vm.selectedIds.size > 1) stringResource(R.string.editor_selected_count, vm.selectedIds.size)
                    else obj?.name ?: stringResource(R.string.editor_composition), Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                if (obj != null) Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(stringResource(if (obj is TextObject) R.string.editor_text_object else R.string.editor_imported_object),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = colors.onSecondaryContainer)
                }
                if (obj is TextObject) IconButton(onClick = { vm.openLettering(obj.id) }, enabled = enabled, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.TextFields, stringResource(R.string.editor_edit_text), Modifier.size(20.dp))
                }
                if (!docked) IconButton(onClick = onExpand, enabled = enabled, modifier = Modifier.size(40.dp)) {
                    Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        stringResource(if (expanded) R.string.editor_collapse_properties else R.string.editor_expand_properties), Modifier.size(20.dp))
                }
            }
            TabRow(selectedTabIndex = tab, containerColor = colors.surface) {
                listOf(R.string.editor_transform_tab, R.string.editor_stitch_tab, R.string.editor_color_tab).forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { onTab(index) }, enabled = enabled,
                        text = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) })
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
                when (tab) {
                    0 -> if (obj != null) TransformInspector(vm, obj, expanded, enabled, onProperties)
                        else if (vm.selectedIds.size > 1) {
                            AlignmentTools(vm)
                            InspectorActions(vm, enabled)
                        } else Text(stringResource(R.string.editor_inspector_empty), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    1 -> StitchInspector(vm, showJumps, onJumps, preview, onPreview, enabled)
                    2 -> ColorInspector(vm, enabled)
                }
            }
        }
    }
}

@Composable
private fun TransformInspector(vm: EditorViewModel, obj: DesignObject, expanded: Boolean, enabled: Boolean, onProperties: () -> Unit) {
    val t = obj.transform
    val b = remember(obj) { obj.localPattern().bounds }
    val widthMm = (b?.width ?: 0) / 10.0 * abs(t.scaleX)
    val heightMm = (b?.height ?: 0) / 10.0 * abs(t.scaleY)
    fun format(v: Double) = String.format(Locale.ROOT, "%.2f", v)
    var width by remember(obj) { mutableStateOf(format(widthMm)) }
    var height by remember(obj) { mutableStateOf(format(heightMm)) }
    var x by remember(obj) { mutableStateOf(format(t.x / 10)) }
    var y by remember(obj) { mutableStateOf(format(t.y / 10)) }
    var rotation by remember(obj) { mutableStateOf(format(t.rotationDeg)) }
    var locked by remember(obj.id) { mutableStateOf(true) }
    val parsed = inspectorTransform(obj, width, height, x, y, rotation)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InspectorNumber(width, { value -> width = value
                val n = value.replace(',', '.').toDoubleOrNull()
                if (locked && n != null && widthMm > 0) height = format(n * heightMm / widthMm)
            }, R.string.editor_width_mm, Modifier.weight(1f), enabled && b?.width != 0)
            IconButton(onClick = { locked = !locked }, enabled = enabled, modifier = Modifier.size(32.dp)) {
                Icon(if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    stringResource(R.string.editor_lock_aspect), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
            }
            InspectorNumber(height, { value -> height = value
                val n = value.replace(',', '.').toDoubleOrNull()
                if (locked && n != null && heightMm > 0) width = format(n * widthMm / heightMm)
            }, R.string.editor_height_mm, Modifier.weight(1f), enabled && b?.height != 0)
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InspectorNumber(x, { x = it }, R.string.editor_property_x, Modifier.weight(1f), enabled)
                InspectorNumber(y, { y = it }, R.string.editor_property_y, Modifier.weight(1f), enabled)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InspectorNumber(rotation, { rotation = it }, R.string.editor_property_rotation, Modifier.weight(1f), enabled)
                IconButton(onClick = onProperties, enabled = enabled) { Icon(Icons.Default.OpenInFull, stringResource(R.string.editor_properties)) }
                TextButton(onClick = { parsed?.let(vm::applyProperties) }, enabled = enabled && parsed != null && parsed != t) {
                    Text(stringResource(R.string.editor_apply))
                }
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.editor_rotation_value, format(t.rotationDeg)), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = vm::mirror, enabled = enabled, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Flip, stringResource(R.string.editor_mirror), Modifier.size(20.dp)) }
            IconButton(onClick = vm::rotate90, enabled = enabled, modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.editor_rotate), Modifier.size(20.dp)) }
            TextButton(onClick = { parsed?.let(vm::applyProperties) }, enabled = enabled && parsed != null && parsed != t) { Text(stringResource(R.string.editor_apply)) }
        }
        if (parsed == null) Text(stringResource(R.string.editor_property_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        if ((parsed?.let { obj.withTransform(it).densityWarning } ?: obj.densityWarning)) Text(stringResource(R.string.editor_density_warning),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        if (expanded) InspectorActions(vm, enabled)
    }
}

@Composable
private fun InspectorNumber(value: String, onChange: (String) -> Unit, label: Int, modifier: Modifier, enabled: Boolean) {
    val dimension = value.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
        ?.takeIf { label in setOf(R.string.editor_property_x, R.string.editor_property_y, R.string.editor_width_mm, R.string.editor_height_mm) }
    OutlinedTextField(value, onChange, modifier, enabled = enabled, singleLine = true,
        label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) },
        supportingText = if (dimension != null) { { Text(Dimensions.length(dimension), style = MaterialTheme.typography.labelSmall) } } else null,
        textStyle = MaterialTheme.typography.bodyMedium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun InspectorActions(vm: EditorViewModel, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(Triple(Icons.Default.ContentCopy, R.string.editor_duplicate, vm::duplicate),
            Triple(Icons.Default.Flip, R.string.editor_mirror, vm::mirror),
            Triple(Icons.AutoMirrored.Filled.RotateRight, R.string.editor_rotate, vm::rotate90),
            Triple(Icons.Default.FilterCenterFocus, R.string.editor_center, vm::center),
            Triple(Icons.Default.DeleteOutline, R.string.editor_delete, vm::delete)).forEach { (icon, label, action) ->
            IconButton(onClick = action, enabled = enabled) { Icon(icon, stringResource(label), tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun StitchInspector(vm: EditorViewModel, showJumps: Boolean, onJumps: (Boolean) -> Unit,
    preview: Float, onPreview: (Float) -> Unit, enabled: Boolean) {
    val project = vm.project
    val selected = vm.selectedIds
    val sewingOptions = vm.settings.sewing
    val pattern by produceState(EmbPattern(emptyList(), emptyList()), project, selected, sewingOptions) {
        value = withContext(Dispatchers.Default) {
            val result = if (selected.isEmpty()) project.flatten(sewingOptions)
                else project.copy(objects = project.objects.filter { it.id in selected }).flatten(sewingOptions)
            result.apply { stitchCount; colorBlocks; trimCount }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        StitchStat(R.string.stat_stitches, pattern.stitchCount)
        StitchStat(R.string.stat_colors, pattern.colorBlocks.size)
        StitchStat(R.string.stat_trims, pattern.trimCount)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.show_jumps), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(showJumps, onJumps, enabled = enabled)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.editor_sewing_preview), style = MaterialTheme.typography.labelSmall)
        Text(stringResource(R.string.editor_preview_percent, (preview * 100).roundToInt()), style = MaterialTheme.typography.labelSmall)
    }
    Slider(preview, onPreview, enabled = enabled)
}

@Composable
private fun StitchStat(label: Int, value: Int) {
    Column {
        Text(String.format(Locale.getDefault(), "%,d", value), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ColorInspector(vm: EditorViewModel, enabled: Boolean) {
    val obj = vm.selected?.takeIf { vm.selectedIds.size == 1 }
    val project = vm.project
    val threads by produceState<List<EmbThread>>(emptyList(), obj, project) {
        value = withContext(Dispatchers.Default) {
            val p = obj?.localPattern() ?: project.flatten()
            p.threads.ifEmpty { p.colorBlocks.map { it.thread } }
        }
    }
    var selectedIndex by remember(obj?.id) { mutableIntStateOf(0) }
    if (threads.isEmpty()) { Text(stringResource(R.string.editor_inspector_empty), style = MaterialTheme.typography.bodySmall); return }
    val index = selectedIndex.coerceIn(0, threads.lastIndex)
    val current = threads[index]
    var hex by remember(obj, index) { mutableStateOf(current.hex.removePrefix("#")) }
    val parsed = parseThreadHex(hex)
    Text(stringResource(R.string.editor_thread_count, threads.size), style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        threads.forEachIndexed { i, thread -> ThreadSwatch(thread, i == index, enabled) { selectedIndex = i } }
    }
    Text(current.displayDetails,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (obj != null) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(EmbThread(ThreadTerracotta.toArgb(), stringResource(R.string.editor_thread_terracotta))) + EmbPattern.DEFAULT_PALETTE).forEach { thread -> ThreadSwatch(thread, thread.sameColor(current), enabled) {
                vm.changeThread(index, thread)
            } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(hex, { hex = it }, Modifier.weight(1f), singleLine = true, enabled = enabled,
                isError = parsed == null, label = { Text(stringResource(R.string.editor_thread_hex)) },
                prefix = { Text("#") }, textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            IconButton(onClick = { parsed?.let { vm.changeThread(index, EmbThread(it)) } }, enabled = enabled && parsed != null && parsed != current.color) {
                Icon(Icons.Default.Check, stringResource(R.string.editor_apply))
            }
        }
    } else Text(stringResource(R.string.editor_color_select_one), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ThreadSwatch(thread: EmbThread, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = listOf(thread.displayName, thread.hex).distinct().joinToString(" ")
    // Thread RGB is design data; all UI chrome still comes from the theme.
    Box(Modifier.size(40.dp).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Surface(Modifier.size(32.dp), shape = CircleShape, color = Color(thread.color),
            border = BorderStroke(if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) { }
    }
}
