// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.model.EmbPattern

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: ViewerViewModel, onOpenFile: () -> Unit, onSaveFile: () -> Unit, onShareFile: () -> Unit) {
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
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        vm.fileName ?: stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenFile, enabled = !vm.loading && !vm.exporting) {
                        Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.open_file))
                    }
                    IconButton(onClick = onSaveFile, enabled = vm.canExport) {
                        if (vm.exporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).semantics { contentDescription = exportingDescription },
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.SaveAlt, contentDescription = stringResource(R.string.save_pes))
                        }
                    }
                    IconButton(onClick = onShareFile, enabled = vm.canExport) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
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
        Button(onClick = onOpenFile) { Text(stringResource(R.string.open_file)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSample) { Text(stringResource(R.string.open_sample)) }
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

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            StitchCanvas(
                data = data,
                progress = vm.progress,
                hoop = vm.hoop,
                showJumps = vm.showJumps,
                fitRequest = fitRequest,
                modifier = Modifier.fillMaxSize(),
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant.toArgb(),
                hoopColor = 0xFF8D6E63.toInt(),
            )
            IconButton(
                onClick = { fitRequest++ },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) { Icon(Icons.Default.FitScreen, contentDescription = stringResource(R.string.fit)) }
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                StatsRow(pattern, vm.hoop)
                Spacer(Modifier.height(4.dp))
                ColorBlocks(pattern, vm.progress) { vm.playing = false; vm.progress = it }
                PlaybackRow(vm, pattern)
                OptionsRow(vm, pattern)
            }
        }
    }
}

@Composable
private fun StatsRow(pattern: EmbPattern, hoop: Hoop) {
    val b = pattern.bounds
    val seconds = pattern.estimatedSeconds()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat(stringResource(R.string.stat_stitches), "%,d".format(pattern.stitchCount))
        Stat(stringResource(R.string.stat_size), b?.let { "%.1f×%.1f mm".format(it.width / 10f, it.height / 10f) } ?: "-")
        Stat(stringResource(R.string.stat_colors), pattern.colorBlocks.size.toString())
        Stat(stringResource(R.string.stat_trims), pattern.trimCount.toString())
        Stat(stringResource(R.string.stat_time), "%d:%02d".format(seconds / 60, seconds % 60))
    }
    for (warning in pattern.warnings) {
        Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
    }
    if (b != null && !hoop.fits(b.width, b.height)) {
        Text(
            stringResource(R.string.hoop_too_small),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One chip per color block; tapping shows the design sewn through the end of that block. */
@Composable
private fun ColorBlocks(pattern: EmbPattern, progress: Int, onSeek: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(pattern.colorBlocks) { i, block ->
            val active = progress >= block.start && progress < block.end
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        if (active) 2.dp else 1.dp,
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(16.dp),
                    )
                    .clickable { onSeek(block.end - 1) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(14.dp).background(Color(block.thread.color), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${i + 1}. ${block.thread.description ?: block.thread.hex}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun PlaybackRow(vm: ViewerViewModel, pattern: EmbPattern) {
    val last = pattern.stitches.lastIndex.coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically) {
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
        Spacer(Modifier.width(8.dp))
        Text("${vm.progress}/$last", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun OptionsRow(vm: ViewerViewModel, pattern: EmbPattern) {
    var hoopMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    val speeds = listOf(100, 400, 1500, 5000)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            selected = vm.showJumps,
            onClick = { vm.showJumps = !vm.showJumps },
            label = { Text(stringResource(R.string.show_jumps)) },
        )
    }
}
