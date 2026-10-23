// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.usb.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun UsbTransferDialogs(vm: LibraryViewModel) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    if (vm.showUsbHistory && vm.usbResult == null && selectedId == null) {
        AlertDialog(onDismissRequest = { vm.showUsbHistory = false },
            title = { Text(stringResource(R.string.usb_history_title)) },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.usbHistory.isEmpty()) Text(stringResource(R.string.usb_history_empty))
                vm.usbHistory.forEach { record ->
                    OutlinedCard(onClick = { selectedId = record.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(transferTime(record.time), style = MaterialTheme.typography.labelMedium)
                            Text(transferSummary(record), style = MaterialTheme.typography.bodyMedium)
                            Text((record.files.map { it.name } + record.failures.map { it.first }).distinct().joinToString(),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Text(record.drive, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { vm.showUsbHistory = false }) { Text(stringResource(R.string.usb_result_close)) } })
    }
    val current = vm.usbResult
    val record = current ?: vm.usbHistory.firstOrNull { it.id == selectedId } ?: return
    TransferResultDialog(record, persistent = current != null, onClose = {
        if (current != null) vm.dismissUsbResult() else selectedId = null
    })
}

@Composable
private fun transferSummary(record: UsbTransferRecord): String {
    val context = LocalContext.current
    val first = record.files.firstOrNull()
    val size = Formatter.formatShortFileSize(context, record.files.sumOf { it.size })
    return when (record.outcome) {
        UsbTransferOutcome.SUCCESS -> if (record.files.size == 1)
            stringResource(R.string.usb_result_success_one, first!!.name, size)
        else stringResource(R.string.usb_result_success_many, record.files.size, size)
        UsbTransferOutcome.PARTIAL -> stringResource(R.string.usb_result_partial, record.files.size, record.failures.size, size)
        UsbTransferOutcome.FAILURE -> stringResource(R.string.usb_result_failure)
    }
}

@Composable
private fun TransferResultDialog(record: UsbTransferRecord, persistent: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var details by remember(record.id) { mutableStateOf(false) }
    val title = stringResource(R.string.usb_result_title)
    val diagnosticText = remember(record) { record.diagnostics() }
    AlertDialog(onDismissRequest = { if (!persistent) onClose() },
        properties = DialogProperties(dismissOnBackPress = !persistent, dismissOnClickOutside = !persistent),
        title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(transferSummary(record), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.usb_result_remaining, record.remainingBytes?.let {
                Formatter.formatShortFileSize(context, it)
            } ?: stringResource(R.string.usb_result_unknown)))
            if (record.failures.isNotEmpty()) Text(stringResource(when (record.problem) {
                UsbTransferProblem.SPACE -> R.string.usb_result_problem_space
                UsbTransferProblem.VERIFY -> R.string.usb_result_problem_verify
                UsbTransferProblem.PERMISSION -> R.string.usb_result_problem_permission
                UsbTransferProblem.DISCONNECTED -> R.string.usb_direct_disconnected
                UsbTransferProblem.NO_DEVICE -> R.string.usb_none
                UsbTransferProblem.FOLDER -> R.string.usb_result_problem_folder
                UsbTransferProblem.OTHER -> R.string.usb_result_problem_other
            }), color = MaterialTheme.colorScheme.error)
            record.notices.forEach { notice -> Text(stringResource(when (notice) {
                UsbTransferNotice.NAME_COLLISION -> R.string.usb_result_name_collision
                UsbTransferNotice.FILESYSTEM -> R.string.usb_result_filesystem
            }), color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { details = !details }) {
                Text(stringResource(if (details) R.string.usb_result_less else R.string.usb_result_details))
            }
            if (details) {
                Text(transferTime(record.time) + "\n" + record.drive, style = MaterialTheme.typography.bodySmall)
                record.files.forEach { Text(stringResource(R.string.usb_result_file, it.name,
                    Formatter.formatShortFileSize(context, it.size)), style = MaterialTheme.typography.bodyMedium) }
                // Names, exact byte counts, raw failure reasons and firmware notes are deliberately folded.
                SelectionContainer { Text(diagnosticText, style = MaterialTheme.typography.bodySmall) }
            }
        } },
        // A single column avoids overlapping Material AlertDialog button slots on narrow phones.
        confirmButton = { Column(Modifier.fillMaxWidth()) {
            Row {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(title, diagnosticText))
                    Toast.makeText(context, R.string.usb_diagnostics_copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.usb_diagnostics_copy)) }
                TextButton(onClick = {
                    try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, diagnosticText)
                    }, title)) } catch (_: Exception) {
                        Toast.makeText(context, R.string.usb_result_share_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.usb_diagnostics_share)) }
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.usb_result_close)) }
        } })
}

private fun transferTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
