// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linker2k.myembro.R
import java.time.LocalDate

@Composable
fun BackupSection(vm: BackupViewModel = viewModel()) {
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {it?.let(vm::backup)}
    val load=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {it?.let(vm::open)}
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Backup,null,tint=MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.backup_title),style=MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.backup_description),style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(enabled=!vm.busy,onClick={save.launch("myembro-backup-${LocalDate.now()}.zip")}) {Text(stringResource(R.string.backup_save))}
                OutlinedButton(enabled=!vm.busy,onClick={load.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream"))}) {Text(stringResource(R.string.backup_restore))}
            }
            if(vm.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(vm.progress.ifBlank {stringResource(R.string.backup_working)})}
        }
    }
    vm.pending?.let {plan ->
        AlertDialog(onDismissRequest=vm::cancelRestore,title={Text(stringResource(R.string.backup_confirm_title))},
            text={Text(stringResource(R.string.backup_confirm,plan.fileCount,vm.duplicates))},
            confirmButton={TextButton(onClick={vm.restore(BackupArchive.Duplicates.COPY)}) {Text(stringResource(R.string.backup_copy))}},
            dismissButton={Row {
                TextButton(onClick=vm::cancelRestore){Text(stringResource(R.string.backup_cancel))}
                TextButton(onClick={vm.restore(BackupArchive.Duplicates.SKIP)}){Text(stringResource(R.string.backup_skip))}
            }})
    }
    vm.result?.let { message -> AlertDialog(onDismissRequest=vm::dismissResult,
        title={Text(stringResource(R.string.backup_title))},text={Text(message)},
        confirmButton={TextButton(onClick=vm::dismissResult){Text(stringResource(R.string.backup_close))}}) }
}
