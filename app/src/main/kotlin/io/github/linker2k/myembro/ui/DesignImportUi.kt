// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.library.*

/** Common entry point: multiple files or a recursively imported folder, with identical filters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberDesignImporter(onFiles: (List<Uri>) -> Unit, onFolder: (Uri) -> Unit): () -> Unit {
    var show by remember { mutableStateOf(false) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), onFiles)
    // SAF tree grants include descendants: https://developer.android.com/training/data-storage/shared/documents-files
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(onFolder) }
    if (show) ModalBottomSheet(onDismissRequest = { show = false }) {
        Text(stringResource(R.string.import_title), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.import_supported), Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        ListItem(headlineContent = { Text(stringResource(R.string.import_files)) },
            modifier = Modifier.clickable { show = false; files.launch(DesignFormats.mimeTypes) },
            leadingContent = { Icon(Icons.Default.FileOpen, null) })
        ListItem(headlineContent = { Text(stringResource(R.string.import_folder)) },
            supportingContent = { Text(stringResource(R.string.import_folder_hint)) },
            modifier = Modifier.clickable { show = false; folder.launch(null) },
            leadingContent = { Icon(Icons.Default.FolderOpen, null) })
        Spacer(Modifier.height(24.dp))
    }
    return { show = true }
}

@Composable
fun DesignImportDialogs(progress: DesignImportProgress?, report: DesignImportReport?, onDismiss: () -> Unit,
    decisions: ImportDecisions, onCancel: () -> Unit,
) {
    val prompt by decisions.prompt.collectAsState()
    if (prompt != null) DuplicateImportDialog(prompt!!, decisions::answer, onCancel)
    else if (progress != null) AlertDialog(onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.import_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (progress.total == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.import_scanning, progress.completed))
            } else {
                LinearProgressIndicator(progress = { if (progress.total == 0) 1f else progress.completed.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(if (progress.committing) R.string.import_saving else R.string.import_progress, progress.completed, progress.total))
            }
            Text(progress.name, maxLines = 2, style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = {}, dismissButton = {
            TextButton(onClick = onCancel, enabled = !progress.committing) { Text(stringResource(android.R.string.cancel)) }
        })
    else if (report != null) AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_complete)) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.import_summary, report.imported.size, report.skipped.count { it.reason != ImportSkipReason.CONVERTED }))
            report.skipped.groupBy { it.reason }.forEach { (reason, entries) ->
                Text(stringResource(when (reason) {
                    ImportSkipReason.UNSUPPORTED -> R.string.import_unsupported
                    ImportSkipReason.DUPLICATE -> R.string.import_duplicate
                    ImportSkipReason.FAILED -> R.string.import_failed
                    ImportSkipReason.CONVERTED -> R.string.import_svg_converted
                }, entries.size), style = MaterialTheme.typography.titleSmall)
                entries.take(20).forEach { Text(it.name + (importNote(it)?.let { detail -> ": $detail" } ?: ""), style = MaterialTheme.typography.bodySmall) }
                if (entries.size > 20) Text(stringResource(R.string.import_more, entries.size - 20))
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } })
}

@Composable
private fun DuplicateImportDialog(prompt: DuplicatePrompt, onAnswer: (DuplicateAction, Boolean) -> Unit, onCancel: () -> Unit) {
    var applyToRemaining by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.import_duplicates_title, prompt.items.size)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.import_duplicates_hint))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(prompt.items, key = { _, item -> item.id }) { index, item ->
                    Text((if (index == prompt.index) "→ " else "") + item.name,
                        fontWeight = if (index == prompt.index) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == prompt.index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            Text(stringResource(R.string.import_duplicate_current, prompt.index + 1, prompt.items.size, prompt.current.name),
                style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().clickable { applyToRemaining = !applyToRemaining },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(applyToRemaining, { applyToRemaining = it })
                Text(stringResource(R.string.import_apply_remaining))
            }
        } },
        confirmButton = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(DuplicateAction.SKIP to R.string.import_action_skip, DuplicateAction.COPY to R.string.import_action_copy,
                DuplicateAction.REPLACE to R.string.import_action_replace).forEach { (action, label) ->
                OutlinedButton(onClick = { onAnswer(action, applyToRemaining) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(android.R.string.cancel)) }
        } })
}

/** The detail line of an import entry; SVG notes list the left-out element kinds and default stitching. */
@Composable
private fun importNote(entry: ImportSkip): String? {
    if (entry.reason != ImportSkipReason.CONVERTED) return entry.detail
    val parts = ArrayList<String>()
    if (entry.autoAssigned) parts += stringResource(R.string.import_svg_auto)
    if (entry.excluded.isNotEmpty()) {
        val kinds = ArrayList<String>()
        for ((kind, count) in entry.excluded) kinds += "${svgKindLabel(kind)} $count"
        parts += stringResource(R.string.import_svg_excluded, kinds.joinToString(", "))
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

@Composable
private fun svgKindLabel(kind: String): String = when {
    kind == "text" -> stringResource(R.string.import_svg_kind_text)
    kind == "image" -> stringResource(R.string.import_svg_kind_image)
    kind.startsWith("fill:") || kind.startsWith("stroke:") || kind.startsWith("satin:") ->
        stringResource(R.string.import_svg_kind_method, kind.substringAfter(':'))
    kind.startsWith("command:") -> stringResource(R.string.import_svg_kind_command, kind.substringAfter(':'))
    else -> kind
}
