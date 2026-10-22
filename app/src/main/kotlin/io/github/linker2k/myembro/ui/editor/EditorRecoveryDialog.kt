// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.linker2k.myembro.R

/** The navigation host can also show recovery on Home immediately after process restart. */
@Composable
fun EditorRecoveryDialog(vm: EditorViewModel, onRestored: () -> Unit = {}, onDismiss: () -> Unit = {}) {
    if (vm.recoveryProject == null) return
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_restore_title)) },
        text = { Text(stringResource(R.string.editor_restore_body)) },
        confirmButton = { TextButton(onClick = { vm.restoreDraft(); onRestored() }) { Text(stringResource(R.string.editor_restore)) } },
        dismissButton = { TextButton(onClick = vm::discardDraft) { Text(stringResource(R.string.editor_discard)) } })
}
