// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.library.DesignLibrary
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import io.github.linker2k.myembro.ui.theme.MyembroLayout
import io.github.linker2k.myembro.ui.theme.MyembroShapes
import java.io.File
import java.util.Locale

/** Compatibility entry point while the host adopts MyembroTheme. */
@Composable
internal fun EmbroideryTheme(content: @Composable () -> Unit) = MyembroTheme(content = content)

/**
 * Design list: tap to open, long-press to start selecting, then export the selection to a
 * USB drive plugged into the phone.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onOpen: (File) -> Unit, onFreeDesigns: (() -> Unit)? = null) {
    EmbroideryTheme { LibraryContent(vm, onOpen, onFreeDesigns) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun LibraryContent(vm: LibraryViewModel, onOpen: (File) -> Unit, onFreeDesigns: (() -> Unit)?) {
    val selecting = vm.selection.isNotEmpty()
    var showUsbHelp by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Set<File>>(emptySet()) }
    val visible = vm.visibleItems
    val snackbar = remember { SnackbarHostState() }
    val importer = rememberDesignImporter(onFiles = { vm.import(it) }, onFolder = { vm.import(emptyList(), tree = it) })
    val importProgress by vm.importProgress.collectAsState()
    DesignImportDialogs(importProgress, vm.importReport, vm::dismissImportReport, vm.importDecisions, vm::cancelImport)
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onAccessResult(it.data?.data)
    }

    LaunchedEffect(vm) { vm.refresh() }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Text(
                        if (selecting) stringResource(R.string.library_selected, vm.selection.size)
                        else stringResource(R.string.library_title),
                    )
                },
                navigationIcon = {
                    if (selecting) IconButton(onClick = vm::clearSelection) {
                        Icon(Icons.Default.Close, stringResource(R.string.cancel))
                    } else HomeButton()
                },
                actions = {
                    IconButton(onClick = { vm.showUsbHistory = true }) {
                        Icon(Icons.Default.History, stringResource(R.string.usb_history_title))
                    }
                    if (selecting) {
                        IconButton(onClick = vm::selectAll) {
                            Icon(Icons.Default.SelectAll, stringResource(R.string.library_select_all))
                        }
                        IconButton(onClick = { deleting = vm.selection.toSet() }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.library_delete))
                        }
                    } else {
                        TextButton(onClick = importer) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.library_import))
                        }
                        if (onFreeDesigns != null) {
                            IconButton(onClick = onFreeDesigns) {
                                Icon(Icons.Default.TravelExplore, stringResource(R.string.sources_title))
                            }
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
                        shape = MyembroShapes.Button,
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
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(MyembroLayout.designColumns(maxWidth)),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "controls", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        if (!selecting) {
                            Text(stringResource(R.string.library_studio), style = MaterialTheme.typography.headlineMedium)
                            Text(stringResource(R.string.library_studio_hint), Modifier.padding(bottom = 16.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        UsbStatus(vm) { vm.refreshDrives(); showUsbHelp = true }
                        LibraryControls(vm, visible.size)
                    }
                }
                if (visible.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        if (vm.loading) CircularProgressIndicator(Modifier.size(28.dp))
                        else {
                            Icon(Icons.Default.Search, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.library_no_results), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.library_no_results_hint), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.searchQuery = ""; vm.filter = LibraryViewModel.Filter.ALL }) {
                                Text(stringResource(R.string.library_reset_filters))
                            }
                        }
                    }
                }
                items(visible, key = { it.file.path }) { item ->
                    DesignCard(
                        item = item, selecting = selecting, selected = item.file in vm.selection,
                        favorite = vm.isFavorite(item.file), exceedsMachine = vm.fitsMachine(item) == false,
                        defaultLogo = item.file.name == vm.defaultLogoName,
                        onFavorite = { vm.toggleFavorite(item.file) },
                        onClick = { if (selecting) vm.toggle(item.file) else onOpen(item.file) },
                        onLongClick = { vm.toggle(item.file) },
                    )
                }
            }
        }
    }

    if (deleting.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleting = emptySet() },
            title = { Text(stringResource(R.string.library_delete_title, deleting.size)) },
            text = { Text(stringResource(R.string.library_delete_message)) },
            confirmButton = { TextButton(onClick = {
                // A changed selection must not delete files that were absent from the confirmation.
                if (vm.selection.toSet() == deleting) vm.deleteSelected()
                deleting = emptySet()
            }) { Text(stringResource(R.string.library_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = emptySet() }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showUsbHelp) {
        AlertDialog(
            onDismissRequest = { showUsbHelp = false },
            icon = { Icon(Icons.Default.Usb, null) },
            title = { Text(stringResource(R.string.library_usb_help_title)) },
            text = {
                Text(stringResource(if (vm.drives.any { it.isDirect }) R.string.usb_direct_notice
                    else if (vm.machineProfile?.id == "se400") R.string.machine_transfer_se400
                    else R.string.library_usb_help_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    showUsbHelp = false
                    if (selecting && !vm.busy) vm.exportToUsb()
                }) {
                    Text(stringResource(if (selecting && !vm.busy) R.string.usb_export else R.string.library_got_it))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::refreshDrives) { Text(stringResource(R.string.library_usb_refresh)) }
            },
        )
    }

    DirectUsbSpaceDialog(vm)
    UsbTransferDialogs(vm)

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
private fun UsbStatus(vm: LibraryViewModel, onClick: () -> Unit) {
    val label = vm.drives.firstOrNull()?.label
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = MyembroShapes.Card,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Usb, null, Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (label != null) stringResource(R.string.usb_connected, label)
                    else stringResource(R.string.usb_not_connected),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(if (vm.drives.firstOrNull()?.isDirect == true) R.string.usb_direct_short_hint
                    else R.string.library_usb_hint), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun LibraryControls(vm: LibraryViewModel, visibleCount: Int) {
    val focus = LocalFocusManager.current
    var sorting by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = vm.searchQuery,
        onValueChange = { vm.searchQuery = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.library_search)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (vm.searchQuery.isNotEmpty()) IconButton(onClick = { vm.searchQuery = "" }) {
                Icon(Icons.Default.Close, stringResource(R.string.library_clear_search))
            }
        },
        singleLine = true,
        shape = MyembroShapes.Card,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
    LazyRow(contentPadding = PaddingValues(0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                shape = MyembroShapes.Chip,
                colors = libraryChipColors(),
                selected = vm.filter == LibraryViewModel.Filter.ALL,
                onClick = { vm.filter = LibraryViewModel.Filter.ALL },
                label = { Text(stringResource(R.string.library_filter_all, vm.items.size)) },
            )
        }
        item {
            FilterChip(
                shape = MyembroShapes.Chip,
                colors = libraryChipColors(),
                selected = vm.filter == LibraryViewModel.Filter.FITS_100,
                onClick = { vm.filter = LibraryViewModel.Filter.FITS_100 },
                label = { Text(stringResource(R.string.library_filter_fits)) },
            )
        }
        item {
            FilterChip(
                shape = MyembroShapes.Chip,
                colors = libraryChipColors(),
                selected = vm.filter == LibraryViewModel.Filter.PES,
                onClick = { vm.filter = LibraryViewModel.Filter.PES },
                label = { Text(stringResource(R.string.library_filter_pes)) },
            )
        }
        item {
            FilterChip(
                shape = MyembroShapes.Chip,
                colors = libraryChipColors(),
                selected = vm.filter == LibraryViewModel.Filter.RECENT,
                onClick = { vm.filter = LibraryViewModel.Filter.RECENT },
                label = { Text(stringResource(R.string.library_filter_recent)) },
            )
        }
        item {
            FilterChip(
                selected = vm.filter == LibraryViewModel.Filter.FAVORITES,
                onClick = { vm.filter = LibraryViewModel.Filter.FAVORITES },
                label = { Text(stringResource(R.string.library_filter_favorites)) },
                leadingIcon = { Icon(Icons.Default.FavoriteBorder, null, Modifier.size(16.dp)) },
                shape = MyembroShapes.Chip,
                colors = libraryChipColors(),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (vm.filter == LibraryViewModel.Filter.RECENT) stringResource(R.string.library_recent_count, visibleCount)
            else stringResource(R.string.library_result_count, visibleCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { sorting = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(sortLabel(vm.sort)))
            }
            DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                LibraryViewModel.Sort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(stringResource(sortLabel(sort))) },
                        onClick = { vm.sort = sort; sorting = false },
                        leadingIcon = { if (vm.sort == sort) Icon(Icons.Default.Check, null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun libraryChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surface,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
)

private fun sortLabel(sort: LibraryViewModel.Sort): Int = when (sort) {
    LibraryViewModel.Sort.RECENT -> R.string.library_sort_recent
    LibraryViewModel.Sort.NAME -> R.string.library_sort_name
    LibraryViewModel.Sort.STITCHES -> R.string.library_sort_stitches
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesignCard(
    item: LibraryViewModel.Item,
    selecting: Boolean,
    selected: Boolean,
    favorite: Boolean,
    exceedsMachine: Boolean,
    defaultLogo: Boolean = false,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = MyembroShapes.Card
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            val thumb = item.thumbnail
            if (thumb != null) {
                Image(thumb.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(1f))
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    if (item.error == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Text("!", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                shape = MyembroShapes.Chip,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(item.file.extension.uppercase(Locale.ROOT) +
                    if (DesignLibrary.isSample(item.file)) " · " + stringResource(R.string.library_sample_badge)
                    else if (defaultLogo) " · " + stringResource(R.string.library_default_badge) else "",
                    Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            if (exceedsMachine) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(top = 12.dp, start = 8.dp, end = 48.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WarningAmber, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.library_hoop_exceeded), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() }, modifier = Modifier.align(Alignment.TopEnd))
            } else {
                IconToggleButton(
                    checked = favorite,
                    onCheckedChange = { onFavorite() },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        stringResource(if (favorite) R.string.library_unfavorite else R.string.library_favorite),
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(item.file.nameWithoutExtension, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                item.summary ?: item.error ?: stringResource(R.string.library_reading),
                style = MaterialTheme.typography.labelSmall,
                color = if (item.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}
