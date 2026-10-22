// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.linker2k.myembro.engine.edit.TextObject

@Composable
internal fun EditorLayerPanel(vm: EditorViewModel, onAdd: () -> Unit, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editor_layers), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onAdd, enabled = !vm.busy && !vm.gestureInProgress) {
                    Icon(Icons.Default.Add, stringResource(R.string.editor_add))
                }
            }
            if (vm.project.objects.isEmpty()) Text(stringResource(R.string.editor_no_layers),
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            EditorLayerList(vm, Modifier.fillMaxWidth().weight(1f)) { vm.openLettering(it) }
        }
    }
}

@Composable
internal fun EditorLayerList(vm: EditorViewModel, modifier: Modifier, onEditText: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rowHeight = with(LocalDensity.current) { 64.dp.toPx() }
    val earlier = stringResource(R.string.editor_layer_earlier)
    val later = stringResource(R.string.editor_layer_later)
    LazyColumn(modifier) {
        items(vm.project.objects, key = { it.id }) { obj ->
            var offset by remember { mutableFloatStateOf(0f) }
            var dragging by remember { mutableStateOf(false) }
            val index = vm.project.objects.indexOfFirst { it.id == obj.id }
            Row(Modifier.fillMaxWidth().height(64.dp).zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = if (dragging) offset else 0f }
                .background(if (obj.id in vm.selectedIds) colors.primaryContainer else colors.surface)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(earlier) { vm.reorder(obj.id, index - 1); true },
                        CustomAccessibilityAction(later) { vm.reorder(obj.id, index + 1); true })
                }.clickable(enabled = !vm.busy && !vm.gestureInProgress) {
                    vm.select(obj.id)
                    if (obj is TextObject && obj.visible && !vm.selectionMode) onEditText(obj.id)
                }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DragIndicator, stringResource(R.string.editor_drag_layer),
                    Modifier.size(40.dp).pointerInput(obj.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                if (!vm.busy && !vm.gestureInProgress) { vm.beginGesture(); dragging = true }
                            },
                            onDragEnd = { if (dragging) vm.endGesture(); dragging = false; offset = 0f },
                            onDragCancel = { if (dragging) vm.endGesture(); dragging = false; offset = 0f },
                            onDrag = { change, delta ->
                                if (dragging && vm.gestureInProgress) {
                                    change.consume(); offset += delta.y
                                    val current = vm.project.objects.indexOfFirst { it.id == obj.id }
                                    val steps = (offset / rowHeight).toInt()
                                    if (steps != 0) {
                                        val target = (current + steps).coerceIn(0, vm.project.objects.lastIndex)
                                        vm.reorder(obj.id, target, duringGesture = true)
                                        offset -= (target - current) * rowHeight
                                    }
                                }
                            })
                    }.padding(8.dp))
                if (vm.selectionMode) Checkbox(obj.id in vm.selectedIds, { vm.toggleSelection(obj.id) }, enabled = obj.visible && !vm.gestureInProgress)
                Text("${index + 1}", Modifier.width(24.dp), style = MaterialTheme.typography.labelMedium)
                Text(obj.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = colors.onSurface.copy(alpha = if (obj.visible) 1f else 0.45f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { vm.toggleVisible(obj.id) }, enabled = !vm.busy && !vm.gestureInProgress) {
                    Icon(if (obj.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        stringResource(if (obj.visible) R.string.editor_hide else R.string.editor_show))
                }
            }
        }
    }
}
