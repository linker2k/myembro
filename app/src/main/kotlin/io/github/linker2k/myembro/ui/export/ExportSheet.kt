// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.export

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.theme.MyembroShapes
import io.github.linker2k.myembro.ui.EmbroideryTheme

/**
 * Shared export picker, with no storage, ActivityResult or ViewModel ownership.
 * The host supplies the existing save launcher / viewer.share / library.exportToUsb callbacks
 * and removes this composable in onDismissRequest. Actions dismiss before calling the host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    onDismissRequest: () -> Unit,
    onSavePes: () -> Unit,
    onShare: () -> Unit,
    onExportUsb: () -> Unit,
    designName: String? = null,
    busy: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var actionTaken by remember { mutableStateOf(false) }
    val available = enabled && !busy && !actionTaken
    fun choose(action: () -> Unit) {
        if (!available || actionTaken) return
        actionTaken = true
        onDismissRequest()
        action()
    }
    EmbroideryTheme {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = modifier,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.export_sheet_title), style = MaterialTheme.typography.headlineSmall)
                if (!designName.isNullOrBlank()) Text(designName, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.export_sheet_busy), style = MaterialTheme.typography.bodySmall)
                } else if (!enabled) Text(stringResource(R.string.export_sheet_no_design), style = MaterialTheme.typography.bodySmall)
                ExportAction(Icons.Default.SaveAlt, stringResource(R.string.export_sheet_save),
                    stringResource(R.string.export_sheet_save_hint), available, primary = true) { choose(onSavePes) }
                ExportAction(Icons.Default.Share, stringResource(R.string.export_sheet_share),
                    stringResource(R.string.export_sheet_share_hint), available) { choose(onShare) }
                ExportAction(Icons.Default.Usb, stringResource(R.string.export_sheet_usb),
                    stringResource(R.string.export_sheet_usb_hint), available) { choose(onExportUsb) }
            }
        }
    }
}

@Composable
private fun ExportAction(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MyembroShapes.Button,
        contentPadding = PaddingValues(16.dp),
        colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
        border = if (primary) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Icon(icon, null, Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
