// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R

/** No implicit cleanup: the user chooses the exact .PES files to delete before a retry. */
@Composable
internal fun DirectUsbSpaceDialog(vm: LibraryViewModel) {
    val request = vm.directSpaceRequest ?: return
    val plan = request.plan
    var selected by remember(request) { mutableStateOf(emptySet<String>()) }
    fun toggle(name: String) { selected = if (name in selected) selected - name else selected + name }
    AlertDialog(onDismissRequest = vm::cancelDirectSpace,
        title = { Text(stringResource(R.string.usb_direct_space_title)) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.usb_direct_space_body, plan.requiredBytes, plan.freeBytes, plan.payloads.size, plan.freeEntries))
            if (plan.candidates.isEmpty()) Text(stringResource(R.string.usb_direct_space_empty))
            plan.candidates.forEach { entry ->
                Row(Modifier.fillMaxWidth().clickable { toggle(entry.name) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(entry.name in selected, { toggle(entry.name) })
                    Text("${entry.name} (${entry.size} B)", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } },
        confirmButton = { TextButton(onClick = { vm.confirmDirectDelete(selected) }, enabled = plan.canProceed(selected) && !vm.busy) {
            Text(stringResource(R.string.usb_direct_delete_export))
        } },
        dismissButton = { TextButton(onClick = vm::cancelDirectSpace) { Text(stringResource(R.string.cancel)) } })
}
