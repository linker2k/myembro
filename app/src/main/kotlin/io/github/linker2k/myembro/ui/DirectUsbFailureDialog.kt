// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R

/** A persistent failure report is more useful on real hardware than a disappearing snackbar. */
@Composable
internal fun DirectUsbFailureDialog(vm: LibraryViewModel) {
    val failure = vm.directFailure ?: return
    val context = LocalContext.current
    val title = stringResource(R.string.usb_diagnostics_title)
    AlertDialog(onDismissRequest = vm::dismissDirectFailure,
        title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.usb_failed, failure.message.orEmpty()))
            Text(stringResource(R.string.usb_diagnostics_hint))
            SelectionContainer { Text(failure.diagnostics, style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(onClick = vm::dismissDirectFailure) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { Row {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, failure.diagnostics))
                Toast.makeText(context, R.string.usb_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.usb_diagnostics_copy)) }
            TextButton(onClick = {
                try {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, failure.diagnostics)
                    }, title))
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.usb_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(R.string.usb_diagnostics_share)) }
        } })
}
