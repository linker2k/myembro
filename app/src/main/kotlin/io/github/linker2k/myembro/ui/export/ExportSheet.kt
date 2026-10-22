// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.export

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.theme.FormatBadge
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
    onExportUsb: (() -> Unit)? = null,
    designName: String? = null,
    busy: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    preview: Bitmap? = null,
    designSummary: String? = null,
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
            containerColor = MaterialTheme.colorScheme.background,
            modifier = modifier,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.export_sheet_title), style = MaterialTheme.typography.headlineSmall)
                Card(
                    shape = MyembroShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (preview != null) Image(preview.asImageBitmap(), null, Modifier.size(84.dp))
                        else Surface(shape = MyembroShapes.Button, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Icon(Icons.Default.Description, null, Modifier.padding(18.dp).size(32.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(designName?.substringBeforeLast('.')?.plus(".pes")
                                ?: stringResource(R.string.export_default_name) + ".pes",
                                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (designSummary != null) Text(designSummary, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FormatBadge(stringResource(R.string.export_sheet_format))
                        }
                    }
                }
                Text(stringResource(R.string.export_sheet_destination), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp))
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
                    stringResource(if (onExportUsb == null) R.string.export_sheet_usb_library_hint else R.string.export_sheet_usb_hint),
                    available && onExportUsb != null) { onExportUsb?.let(::choose) }
                Text(stringResource(R.string.export_sheet_compatibility), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
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
        shape = MyembroShapes.Card,
        contentPadding = PaddingValues(16.dp),
        colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
        border = if (primary) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Surface(
            shape = MyembroShapes.Chip,
            color = if (primary) MaterialTheme.colorScheme.onPrimary.copy(alpha = .14f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ) { Icon(icon, null, Modifier.padding(10.dp).size(24.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp))
    }
}
