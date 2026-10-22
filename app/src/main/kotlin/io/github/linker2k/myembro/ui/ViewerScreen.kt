// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.export.ExportSheet
import io.github.linker2k.myembro.library.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.linker2k.myembro.ui.theme.MyembroShapes
import io.github.linker2k.myembro.engine.model.EmbPattern

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    vm: ViewerViewModel,
    onOpenFile: () -> Unit,
    onSaveFile: () -> Unit,
    onShareFile: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onExportUsb: (() -> Unit)? = null,
) {
    EmbroideryTheme { ViewerContent(vm, onOpenFile, onSaveFile, onShareFile, onEdit, onExportUsb) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerContent(
    vm: ViewerViewModel,
    onOpenFile: () -> Unit,
    onSaveFile: () -> Unit,
    onShareFile: () -> Unit,
    onEdit: (() -> Unit)?,
    onExportUsb: (() -> Unit)?,
) {
    var exportSheet by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val message = vm.exportMessage
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
            vm.dismissExportMessage(message)
        }
    }
    val exportingDescription = stringResource(R.string.export_in_progress)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (vm.render != null && !vm.loading) Surface(color = MaterialTheme.colorScheme.background) {
                Button(onClick = { exportSheet = true }, enabled = vm.canExport,
                    shape = MyembroShapes.Button, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Icon(Icons.Default.SaveAlt, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.export_sheet_title))
                }
            }
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Text(
                        vm.fileName ?: stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    TextButton(
                        onClick = { onEdit?.invoke() },
                        enabled = onEdit != null && vm.render != null && !vm.loading && !vm.exporting,
                    ) {
                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.viewer_edit))
                    }
                    IconButton(onClick = onOpenFile, enabled = !vm.loading && !vm.exporting) {
                        Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.open_file))
                    }
                    IconButton(onClick = { exportSheet = true }, enabled = vm.canExport) {
                        if (vm.exporting) CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).semantics { contentDescription = exportingDescription },
                            strokeWidth = 2.dp,
                        ) else Icon(Icons.Default.SaveAlt, stringResource(R.string.export_sheet_title))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val data = vm.render
            when {
                vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                data == null -> EmptyState(vm.error, onOpenFile, vm::openSample)
                else -> Viewer(vm, data)
            }
        }
    }
    if (exportSheet) {
        val data = vm.render
        val preview by produceState<android.graphics.Bitmap?>(null, data) {
            value = data?.let { withContext(Dispatchers.Default) { Thumbnails.render(it, 240) } }
        }
        ExportSheet(
            onDismissRequest = { exportSheet = false }, onSavePes = onSaveFile, onShare = onShareFile,
            onExportUsb = onExportUsb, designName = vm.fileName, busy = vm.exporting, enabled = vm.render != null,
            preview = preview,
            designSummary = data?.pattern?.let {
                stringResource(R.string.viewer_export_summary, it.stitchCount, it.colorBlocks.size)
            },
        )
    }
}

@Composable
private fun EmptyState(error: String?, onOpenFile: () -> Unit, onOpenSample: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.empty_state),
            contentDescription = null,
            modifier = Modifier.size(160.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyMedium)
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.load_failed, error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenFile, shape = MyembroShapes.Button) { Text(stringResource(R.string.open_file)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSample, shape = MyembroShapes.Button) { Text(stringResource(R.string.open_sample)) }
    }
}

@Composable
private fun Viewer(vm: ViewerViewModel, data: RenderData) {
    val pattern = data.pattern
    var fitRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(vm.playing) {
        var last = 0L
        while (vm.playing) {
            withFrameNanos { now ->
                if (last != 0L) vm.tick((now - last) / 1e9f)
                last = now
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxPanelHeight = maxHeight * 0.54f
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                .clip(MyembroShapes.Card)) {
                StitchCanvas(
                    data = data,
                    progress = vm.progress,
                    hoop = vm.hoop,
                    showJumps = vm.showJumps,
                    fitRequest = fitRequest,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = MaterialTheme.colorScheme.background.toArgb(),
                    hoopColor = MaterialTheme.colorScheme.outline.toArgb(),
                )
                Surface(modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    shape = MyembroShapes.Chip, color = MaterialTheme.colorScheme.surface.copy(alpha = .94f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Straighten, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${vm.hoop.label} mm", style = MaterialTheme.typography.labelMedium)
                    }
                }
                IconButton(
                    onClick = { fitRequest++ },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), MyembroShapes.Button),
                ) { Icon(Icons.Default.FitScreen, contentDescription = stringResource(R.string.fit)) }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxPanelHeight)
                    .verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatsRow(pattern, vm.hoop)
                Card(
                    shape = MyembroShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.viewer_color_order), style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f))
                            Text("${(vm.progress.coerceAtLeast(0) * 100L / pattern.stitches.lastIndex.coerceAtLeast(1)).coerceAtMost(100)}%",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LinearProgressIndicator(progress = {
                            vm.progress.toFloat().coerceAtLeast(0f) / pattern.stitches.lastIndex.coerceAtLeast(1)
                        }, modifier = Modifier.fillMaxWidth())
                        ColorBlocks(pattern, vm.progress) { vm.playing = false; vm.progress = it }
                    }
                }
                Card(shape = MyembroShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        PlaybackRow(vm, pattern)
                        OptionsRow(vm, pattern)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsRow(pattern: EmbPattern, hoop: Hoop) {
    val b = pattern.bounds
    val seconds = pattern.estimatedSeconds()
    Card(
        shape = MyembroShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.stat_stitches), "%,d".format(pattern.stitchCount), Modifier.weight(1f))
                Stat(stringResource(R.string.stat_size), b?.let { "%.1f×%.1f".format(it.width / 10f, it.height / 10f) } ?: "-", Modifier.weight(1.5f))
                Stat(stringResource(R.string.stat_colors), pattern.colorBlocks.size.toString(), Modifier.weight(.7f))
                Stat(stringResource(R.string.stat_time), "%d:%02d".format(seconds / 60, seconds % 60), Modifier.weight(1f))
            }
            Text(stringResource(R.string.viewer_trims_size_note, pattern.trimCount),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (warning in pattern.warnings) {
                Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            if (b != null && !hoop.fits(b.width, b.height)) {
                Text(stringResource(R.string.hoop_too_small), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One chip per color block; tapping shows the design sewn through the end of that block. */
@Composable
private fun ColorBlocks(pattern: EmbPattern, progress: Int, onSeek: (Int) -> Unit) {
    val counts = remember(pattern) {
        pattern.colorBlocks.map { block ->
            pattern.stitches.subList(block.start, block.end).count {
                it.command == io.github.linker2k.myembro.engine.model.Command.STITCH
            }
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(pattern.colorBlocks) { i, block ->
            val active = progress >= block.start && progress < block.end
            Column(
                Modifier.width(96.dp).clip(MyembroShapes.Chip)
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    .border(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        MyembroShapes.Chip)
                    .semantics { selected = active }
                    .clickable { onSeek((block.end - 1).coerceAtLeast(0)) }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((i + 1).toString(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(32.dp).background(Color(block.thread.color), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                }
                Text(block.thread.description ?: block.thread.hex,
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.viewer_thread_stitches, counts[i]),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlaybackRow(vm: ViewerViewModel, pattern: EmbPattern) {
    val last = pattern.stitches.lastIndex.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.playing = false; vm.progress = 0 }) {
            Icon(Icons.Default.SkipPrevious, stringResource(R.string.viewer_restart))
        }
        FilledIconButton(onClick = vm::togglePlay) {
            if (vm.playing) Icon(Icons.Default.Pause, stringResource(R.string.pause))
            else Icon(Icons.Default.PlayArrow, stringResource(R.string.play))
        }
        Spacer(Modifier.width(8.dp))
        Slider(
            value = vm.progress.toFloat(),
            onValueChange = { vm.playing = false; vm.progress = it.toInt() },
            valueRange = 0f..last.toFloat(),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { vm.playing = false; vm.progress = pattern.stitches.lastIndex.coerceAtLeast(0) }) {
            Icon(Icons.Default.SkipNext, stringResource(R.string.viewer_finish))
        }
    }
}

@Composable
private fun OptionsRow(vm: ViewerViewModel, pattern: EmbPattern) {
    var hoopMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    val speeds = listOf(100, 400, 1500, 5000)
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            TextButton(onClick = { hoopMenu = true }) { Text("${stringResource(R.string.hoop)} ${vm.hoop.label}") }
            DropdownMenu(expanded = hoopMenu, onDismissRequest = { hoopMenu = false }) {
                val b = pattern.bounds
                Hoop.entries.forEach { h ->
                    val fits = b == null || h.fits(b.width, b.height)
                    DropdownMenuItem(
                        text = { Text(h.label + if (fits) "" else " ⚠") },
                        onClick = { vm.hoop = h; hoopMenu = false },
                    )
                }
            }
        }
        Box {
            TextButton(onClick = { speedMenu = true }) { Text("${stringResource(R.string.speed)} ${vm.speed}/s") }
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                speeds.forEach { s ->
                    DropdownMenuItem(text = { Text("$s/s") }, onClick = { vm.speed = s; speedMenu = false })
                }
            }
        }
        FilterChip(
            shape = MyembroShapes.Chip,
            selected = vm.showJumps,
            onClick = { vm.showJumps = !vm.showJumps },
            label = { Text(stringResource(R.string.show_jumps)) },
        )
    }
}
