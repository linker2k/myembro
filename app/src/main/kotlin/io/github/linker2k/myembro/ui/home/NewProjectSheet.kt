// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.format.dimensions
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linker2k.myembro.ui.settings.SettingsViewModel
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.theme.HoopPreview
import io.github.linker2k.myembro.ui.theme.MyembroShapes
import io.github.linker2k.myembro.ui.theme.MyembroTheme

/** Shared creation flow. Unsupported frames stay selectable with a compatibility warning. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectSheet(
    onDismissRequest: () -> Unit,
    onBlank: (Hoop) -> Unit,
    onFromDesign: () -> Unit,
    onFromText: () -> Unit,
    hoops: List<Hoop> = listOf(Hoop.H100),
    letteringAvailable: Boolean = false,
    modifier: Modifier = Modifier,
    onBlankFrame: ((HoopSpec) -> Unit)? = null,
) {
    val settings: SettingsViewModel = viewModel()
    val options = settings.settings.frames.filter { frame -> frame.preset in hoops || (frame.preset == null && onBlankFrame != null) }
        .sortedBy { it.id != settings.settings.defaultFrame.id }
    var choosingHoop by remember { mutableStateOf(false) }
    var actionTaken by remember { mutableStateOf(false) }
    fun choose(action: () -> Unit) {
        if (actionTaken) return
        actionTaken = true
        onDismissRequest()
        action()
    }
    MyembroTheme {
        ModalBottomSheet(onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background, modifier = modifier) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (choosingHoop) R.string.new_project_choose_hoop else R.string.home_new_project),
                    style = MaterialTheme.typography.headlineSmall)
                if (choosingHoop) {
                    options.forEach { frame ->
                        Surface(onClick = { choose {
                            if (onBlankFrame != null) onBlankFrame(frame.spec) else frame.preset?.let(onBlank)
                        } }, enabled = !actionTaken,
                            shape = MyembroShapes.Card, color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                frame.preset?.let { HoopPreview(it, Modifier.size(48.dp)) }
                                Column(Modifier.weight(1f)) {
                                    Text((if (frame.preset == null || frame.id.startsWith("SA")) frame.spec.name + " · " else "") + frame.dimensions)
                                    if (settings.settings.machineProfile?.supports(frame.spec) != true) Text(
                                        stringResource(R.string.machine_hoop_unsupported), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                    if (frame.preset == Hoop.SA434) Text(stringResource(R.string.machine_positions_unverified),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ChevronRight, null)
                            }
                        }
                    }
                    TextButton(onClick = { choosingHoop = false }) { Text(stringResource(R.string.settings_back)) }
                } else {
                    Text(stringResource(R.string.new_project_intro), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CreationChoice(Icons.Default.Add, stringResource(R.string.new_project_blank),
                        if (options.size == 1) options.single().dimensions
                        else stringResource(R.string.new_project_choose_hoop), !actionTaken && options.isNotEmpty()) {
                        if (options.size == 1) choose {
                            if (onBlankFrame != null) onBlankFrame(options.single().spec) else options.single().preset?.let(onBlank)
                        } else choosingHoop = true
                    }
                    CreationChoice(Icons.Default.CollectionsBookmark, stringResource(R.string.new_project_from_design),
                        stringResource(R.string.new_project_from_design_hint), !actionTaken) { choose(onFromDesign) }
                    if (letteringAvailable) CreationChoice(Icons.Default.TextFields, stringResource(R.string.new_project_from_text),
                        stringResource(R.string.new_project_from_text_hint), !actionTaken) { choose(onFromText) }
                }
            }
        }
    }
}

@Composable
private fun CreationChoice(icon: ImageVector, title: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = MyembroShapes.Card,
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = MyembroShapes.Chip, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp))
        }
    }
}
