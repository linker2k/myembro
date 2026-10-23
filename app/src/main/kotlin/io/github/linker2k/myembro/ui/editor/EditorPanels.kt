// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.AlignHorizontalLeft
import androidx.compose.material.icons.automirrored.filled.AlignHorizontalRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.Alignment as ObjectAlignment
import io.github.linker2k.myembro.engine.edit.Axis
import io.github.linker2k.myembro.engine.edit.densityWarning
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.lettering.ThreadWeight
import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.ui.lettering.LetteringModeSelector
import io.github.linker2k.myembro.ui.lettering.ThreadWeightSelector
import io.github.linker2k.myembro.ui.lettering.TextFillWarning
import java.io.File
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun AlignmentTools(vm: EditorViewModel) {
    val enabled = !vm.busy && !vm.gestureInProgress
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        EditorTool(Icons.AutoMirrored.Filled.AlignHorizontalLeft, R.string.editor_align_left, enabled) { vm.align(ObjectAlignment.LEFT) }
        EditorTool(Icons.Default.AlignHorizontalCenter, R.string.editor_align_center_x, enabled) { vm.align(ObjectAlignment.CENTER_X) }
        EditorTool(Icons.AutoMirrored.Filled.AlignHorizontalRight, R.string.editor_align_right, enabled) { vm.align(ObjectAlignment.RIGHT) }
        EditorTool(Icons.Default.AlignVerticalTop, R.string.editor_align_top, enabled) { vm.align(ObjectAlignment.TOP) }
        EditorTool(Icons.Default.AlignVerticalCenter, R.string.editor_align_center_y, enabled) { vm.align(ObjectAlignment.CENTER_Y) }
        EditorTool(Icons.Default.AlignVerticalBottom, R.string.editor_align_bottom, enabled) { vm.align(ObjectAlignment.BOTTOM) }
        EditorTool(Icons.Default.HorizontalDistribute, R.string.editor_distribute_x, vm.selectedIds.size >= 3 && enabled) { vm.distribute(Axis.HORIZONTAL) }
        EditorTool(Icons.Default.VerticalDistribute, R.string.editor_distribute_y, vm.selectedIds.size >= 3 && enabled) { vm.distribute(Axis.VERTICAL) }
    }
}

@Composable
internal fun ObjectProperties(vm: EditorViewModel, onDismiss: () -> Unit) {
    val obj = vm.selected ?: return
    val t = obj.transform
    fun format(value: Double) = String.format(Locale.ROOT, "%.2f", value)
    var x by remember(obj.id) { mutableStateOf(format(t.x / 10)) }
    var y by remember(obj.id) { mutableStateOf(format(t.y / 10)) }
    var rotation by remember(obj.id) { mutableStateOf(format(t.rotationDeg)) }
    var sx by remember(obj.id) { mutableStateOf(format(abs(t.scaleX) * 100)) }
    var sy by remember(obj.id) { mutableStateOf(format(abs(t.scaleY) * 100)) }
    var style by remember(obj.id) { mutableStateOf((obj as? TextObject)?.stitchStyle ?: StitchStyle.RUNNING) }
    var weight by remember(obj.id) { mutableStateOf((obj as? TextObject)?.threadWeight ?: ThreadWeight.WT40) }
    val parsed = parseEditorTransform(x, y, rotation, sx, sy, t)
    val warning = parsed?.let { obj.withTransform(it).densityWarning } == true
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_properties)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(obj.name, style = MaterialTheme.typography.bodyMedium)
                if (obj is TextObject) {
                    LetteringModeSelector(obj.singleLine, style, { style = it })
                    ThreadWeightSelector(weight) { weight = it }
                    TextFillWarning(obj.withThreadWeight(weight), style)
                    TextButton(onClick = { vm.openLettering(obj.id); onDismiss() }) {
                        Text(stringResource(R.string.lettering_fill_advanced))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumericField(x, { x = it }, R.string.editor_property_x, Modifier.weight(1f))
                    NumericField(y, { y = it }, R.string.editor_property_y, Modifier.weight(1f))
                }
                NumericField(rotation, { rotation = it }, R.string.editor_property_rotation, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumericField(sx, { sx = it }, R.string.editor_property_scale_x, Modifier.weight(1f))
                    NumericField(sy, { sy = it }, R.string.editor_property_scale_y, Modifier.weight(1f))
                }
                Text(stringResource(R.string.editor_property_hint), style = MaterialTheme.typography.bodySmall)
                if (warning) Text(stringResource(R.string.editor_density_warning), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                if (parsed == null) Text(stringResource(R.string.editor_property_invalid), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = {
            parsed?.let { if (obj is TextObject) vm.applyTextProperties(it, style, weight) else vm.applyProperties(it) }; onDismiss()
        }) {
            Text(stringResource(R.string.editor_apply))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } })
}

@Composable
private fun NumericField(value: String, onChange: (String) -> Unit, label: Int, modifier: Modifier) {
    OutlinedTextField(value, onChange, modifier, singleLine = true, label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
internal fun SaveProjectDialog(onDismiss: () -> Unit, onSave: (String) -> Unit, onExport: (String) -> Unit) {
    var name by remember { mutableStateOf("myembro-${System.currentTimeMillis()}") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.editor_project_save)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.editor_project_name)) }) },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.editor_save_local)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onExport(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.editor_export_file)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
            }
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectPicker(vm: EditorViewModel, onDismiss: () -> Unit, onOpen: (File) -> Unit, onDevice: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.editor_project_open), Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = onDevice, modifier = Modifier.padding(horizontal = 20.dp)) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.editor_open_device))
        }
        if (vm.projectFiles.isEmpty()) Text(stringResource(R.string.editor_projects_empty), Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
            items(vm.projectFiles, key = { it.path }) { file ->
                ListItem(headlineContent = { Text(file.name) }, leadingContent = { Icon(Icons.Default.Description, null) },
                    modifier = Modifier.clickable { onOpen(file) })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
