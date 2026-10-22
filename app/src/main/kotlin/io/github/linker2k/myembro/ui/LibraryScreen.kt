// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import java.io.File

/**
 * Design list: tap to open, long-press to start selecting, then export the selection to a
 * USB drive plugged into the phone.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onOpen: (File) -> Unit) {
    val selecting = vm.selection.isNotEmpty()
    val snackbar = remember { SnackbarHostState() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        vm.import(it)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onAccessResult(it.data?.data)
    }

    LaunchedEffect(vm.accessRequest) {
        vm.accessRequest?.let { folderPicker.launch(vm.accessIntent(it)) }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) stringResource(R.string.library_selected, vm.selection.size)
                        else stringResource(R.string.library_title),
                    )
                },
                navigationIcon = {
                    if (selecting) IconButton(onClick = vm::clearSelection) {
                        Icon(Icons.Default.Close, stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = vm::selectAll) {
                            Icon(Icons.Default.SelectAll, stringResource(R.string.library_select_all))
                        }
                        IconButton(onClick = vm::deleteSelected) {
                            Icon(Icons.Default.Delete, stringResource(R.string.library_delete))
                        }
                    } else {
                        IconButton(onClick = { importer.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.Add, stringResource(R.string.library_import))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selecting) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = { vm.exportToUsb() },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Usb, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.usb_export_n, vm.selection.size))
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            UsbStatus(vm)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(vm.items, key = { it.file.path }) { item ->
                    DesignCard(
                        item = item,
                        selecting = selecting,
                        selected = item.file in vm.selection,
                        onClick = { if (selecting) vm.toggle(item.file) else onOpen(item.file) },
                        onLongClick = { vm.toggle(item.file) },
                    )
                }
            }
        }
    }

    vm.driveChoice?.let { choices ->
        AlertDialog(
            onDismissRequest = { vm.chooseDrive(null) },
            title = { Text(stringResource(R.string.usb_choose)) },
            text = {
                Column {
                    choices.forEach { d ->
                        TextButton(onClick = { vm.chooseDrive(d) }) { Text(d.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.chooseDrive(null) }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun UsbStatus(vm: LibraryViewModel) {
    val label = vm.drives.firstOrNull()?.label
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = vm::refreshDrives,
            label = {
                Text(
                    if (label != null) stringResource(R.string.usb_connected, label)
                    else stringResource(R.string.usb_not_connected),
                )
            },
            leadingIcon = { Icon(Icons.Default.Usb, null, Modifier.size(18.dp)) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesignCard(
    item: LibraryViewModel.Item,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        shape = shape,
        modifier = Modifier
            .clip(shape)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            val thumb = item.thumbnail
            if (thumb != null) {
                Image(thumb.asImageBitmap(), item.file.name, Modifier.fillMaxWidth().aspectRatio(1f))
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    if (item.error == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Text("!", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() }, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(item.file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.summary ?: item.error ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = if (item.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}
