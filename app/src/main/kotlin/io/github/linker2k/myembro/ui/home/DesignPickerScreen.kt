// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.theme.FormatBadge
import io.github.linker2k.myembro.ui.theme.MyembroLayout
import io.github.linker2k.myembro.ui.theme.MyembroShapes
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import java.io.File
import java.util.Locale

/** Lightweight, single-file picker for NewProjectSheet.onFromDesign; the host opens the editor. */
@Composable
fun DesignPickerScreen(
    onSelect: (File) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: DesignPickerViewModel = viewModel()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    MyembroTheme {
        DesignPickerContent(vm.items, vm.loading, vm.failed, vm::refresh, onSelect, onBack, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesignPickerContent(
    items: List<DesignPickerViewModel.Item>, loading: Boolean, failed: Boolean, onRetry: () -> Unit,
    onSelect: (File) -> Unit, onBack: () -> Unit, modifier: Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var samples by rememberSaveable { mutableStateOf(false) }
    val mineScroll = rememberLazyGridState()
    val samplesScroll = rememberLazyGridState()
    val focus = LocalFocusManager.current
    val visible = items.filter { it.sample == samples && (it.file.name.contains(query.trim(), ignoreCase = true) ||
        it.title.contains(query.trim(), ignoreCase = true)) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.picker_title)) },
                navigationIcon = { Row {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) }
                    io.github.linker2k.myembro.ui.HomeButton()
                } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val columns = MyembroLayout.designColumns(maxWidth)
            Column(Modifier.fillMaxSize()) {
                Text(stringResource(R.string.picker_intro), Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = MyembroShapes.Button, singleLine = true,
                    placeholder = { Text(stringResource(R.string.library_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, stringResource(R.string.library_clear_search))
                    } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                )
                TabRow(selectedTabIndex = if (samples) 1 else 0, containerColor = MaterialTheme.colorScheme.background) {
                    Tab(selected = !samples, onClick = { samples = false; focus.clearFocus() },
                        text = { Text(stringResource(R.string.picker_mine)) })
                    Tab(selected = samples, onClick = { samples = true; focus.clearFocus() },
                        text = { Text(stringResource(R.string.picker_samples)) })
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.library_result_count, visible.size), Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (failed) TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.picker_load_failed), color = MaterialTheme.colorScheme.error)
                }
                if (visible.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        if (loading) CircularProgressIndicator(Modifier.size(28.dp))
                        else {
                            Icon(Icons.Default.Search, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(if (query.isNotBlank()) R.string.library_no_results else R.string.picker_empty),
                                style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(if (query.isNotBlank()) R.string.library_no_results_hint else R.string.picker_empty_hint),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (query.isNotBlank()) TextButton(onClick = { query = "" }) {
                                Text(stringResource(R.string.library_clear_search))
                            } else if (!samples) TextButton(onClick = { samples = true }) {
                                Text(stringResource(R.string.picker_browse_samples))
                            }
                        }
                    }
                } else LazyVerticalGrid(
                    columns = GridCells.Fixed(columns), modifier = Modifier.weight(1f),
                    state = if (samples) samplesScroll else mineScroll,
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visible, key = { it.file.path }) { item -> PickerCard(item) { onSelect(item.file) } }
                }
            }
        }
    }
}

@Composable
private fun PickerCard(item: DesignPickerViewModel.Item, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = item.ready, shape = MyembroShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.15f), contentAlignment = Alignment.Center) {
            val thumb = item.thumbnail
            when {
                thumb != null -> Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize())
                item.error != null -> Icon(Icons.Default.WarningAmber, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.error)
                else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            FormatBadge(item.file.extension.uppercase(Locale.ROOT), Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Column(Modifier.padding(12.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(when {
                item.error != null -> stringResource(R.string.picker_unreadable)
                item.ready -> stringResource(R.string.viewer_export_summary, item.stitchCount, item.colorCount)
                else -> stringResource(R.string.library_reading)
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
