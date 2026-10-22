// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.format.Dimensions
import io.github.linker2k.myembro.ui.machines.CustomFrame
import io.github.linker2k.myembro.ui.machines.DimensionUnit
import io.github.linker2k.myembro.ui.theme.MyembroShapes
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
internal fun CustomFramesSection(vm: SettingsViewModel) {
    var editing by remember { mutableStateOf<CustomFrame?>(null) }
    var dialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomFrame?>(null) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.settings_custom_frames), style = MaterialTheme.typography.titleSmall)
        vm.settings.customFrames.forEach { frame ->
            Surface(onClick = { vm.setDefaultFrame(frame.id) }, shape = MyembroShapes.Button,
                color = if (vm.settings.defaultFrame.id == frame.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(frame.name, style = MaterialTheme.typography.titleSmall)
                        Text(Dimensions.size(frame.width / 10.0, frame.height / 10.0), style = MaterialTheme.typography.bodySmall)
                        if (vm.settings.machineProfile?.supports(frame.spec) != true) Text(stringResource(R.string.machine_hoop_unsupported),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = { editing = frame; dialog = true }) {
                        Icon(Icons.Default.Edit, stringResource(R.string.settings_edit_frame))
                    }
                    IconButton(onClick = { deleting = frame }) {
                        Icon(Icons.Default.DeleteOutline, stringResource(R.string.settings_delete_frame))
                    }
                }
            }
        }
        TextButton(onClick = { editing = null; dialog = true }, enabled = vm.settings.customFrames.size < 100) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.settings_add_frame))
        }
    }
    if (dialog) CustomFrameDialog(editing, { dialog = false }) { vm.saveCustomFrame(it); dialog = false }
    deleting?.let { frame ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.settings_delete_frame)) },
            text = { Text(stringResource(R.string.settings_delete_frame_confirm, frame.name)) },
            confirmButton = { TextButton(onClick = { vm.deleteCustomFrame(frame.id); deleting = null }) { Text(stringResource(R.string.settings_delete_frame)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_cancel)) } })
    }
}

@Composable
private fun CustomFrameDialog(initial: CustomFrame?, onDismiss: () -> Unit, onSave: (CustomFrame) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var width by remember { mutableStateOf(initial?.width?.let { BigDecimal(it).movePointLeft(1).stripTrailingZeros().toPlainString() }.orEmpty()) }
    var height by remember { mutableStateOf(initial?.height?.let { BigDecimal(it).movePointLeft(1).stripTrailingZeros().toPlainString() }.orEmpty()) }
    var unit by remember { mutableStateOf(DimensionUnit.MM) }
    val valid = runCatching { CustomFrame.fromInput(name, width, height, unit, initial?.id ?: "new") }.getOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.settings_custom_frames)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.settings_frame_name)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DimensionUnit.entries.forEach { choice -> FilterChip(selected = unit == choice, onClick = {
                    if (unit != choice) {
                        fun convert(input: String) = runCatching {
                            val value = input.replace(',', '.').toBigDecimal()
                            (if (choice == DimensionUnit.IN) value.divide(BigDecimal("25.4"), 6, RoundingMode.HALF_UP)
                            else value.multiply(BigDecimal("25.4"))).stripTrailingZeros().toPlainString()
                        }.getOrDefault(input)
                        width = convert(width); height = convert(height); unit = choice
                    }
                }, label = { Text(if (choice == DimensionUnit.MM) "mm" else "in") }) }
            }
            OutlinedTextField(width, { width = it.take(20) }, label = { Text(stringResource(R.string.settings_frame_width)) },
                suffix = { Text(if (unit == DimensionUnit.MM) "mm" else "in") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(height, { height = it.take(20) }, label = { Text(stringResource(R.string.settings_frame_height)) },
                suffix = { Text(if (unit == DimensionUnit.MM) "mm" else "in") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text(valid?.let { Dimensions.size(it.width / 10.0, it.height / 10.0) } ?: stringResource(R.string.settings_frame_validation),
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = valid != null, onClick = {
            onSave(CustomFrame.fromInput(name, width, height, unit, initial?.id ?: java.util.UUID.randomUUID().toString()))
        }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } })
}
