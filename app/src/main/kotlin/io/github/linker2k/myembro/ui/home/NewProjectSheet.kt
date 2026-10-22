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
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.theme.HoopPreview
import io.github.linker2k.myembro.ui.theme.MyembroShapes
import io.github.linker2k.myembro.ui.theme.MyembroTheme

/** Shared creation flow. [hoops] must come from the selected machine's supported project hoops. */
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
) {
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
                    hoops.distinct().forEach { hoop ->
                        Surface(onClick = { choose { onBlank(hoop) } }, enabled = !actionTaken,
                            shape = MyembroShapes.Card, color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                HoopPreview(hoop, Modifier.size(48.dp))
                                Text(stringResource(R.string.settings_hoop_size, hoop.label), Modifier.weight(1f))
                                Icon(Icons.Default.ChevronRight, null)
                            }
                        }
                    }
                    TextButton(onClick = { choosingHoop = false }) { Text(stringResource(R.string.settings_back)) }
                } else {
                    Text(stringResource(R.string.new_project_intro), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CreationChoice(Icons.Default.Add, stringResource(R.string.new_project_blank),
                        if (hoops.size == 1) stringResource(R.string.settings_hoop_size, hoops.single().label)
                        else stringResource(R.string.new_project_choose_hoop), !actionTaken && hoops.isNotEmpty()) {
                        if (hoops.size == 1) choose { onBlank(hoops.single()) } else choosingHoop = true
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
