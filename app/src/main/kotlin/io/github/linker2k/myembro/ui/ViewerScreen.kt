// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import io.github.linker2k.myembro.ui.format.Dimensions
import io.github.linker2k.myembro.ui.format.dimensions
import io.github.linker2k.myembro.ui.machines.FrameOption
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.export.ExportSheet
import io.github.linker2k.myembro.library.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.linker2k.myembro.ui.theme.MyembroLayout
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
    LaunchedEffect(vm) { vm.ensureSourceCurrent() }
    val report = vm.sewoutReport
    var confirmUsb by remember(report, vm.render, vm.checkingSewout) { mutableStateOf(false) }
    val checkedUsb: (() -> Unit)? = onExportUsb?.let { transfer -> {
        val current = vm.sewoutReport
        if (!vm.ensureSourceCurrent() || !vm.sewoutReady || current == null) vm.openSewout()
        else if (sewoutNeedsConfirmation(current)) confirmUsb = true
        else transfer()
    } }
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
            if (vm.render != null && !vm.loading) Surface(modifier = Modifier.navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { checkedUsb?.invoke() }, enabled = vm.canExport && vm.sewoutReady && checkedUsb != null,
                            shape = MyembroShapes.Button, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Usb, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.viewer_send_usb))
                        }
                        OutlinedButton(onClick = { onEdit?.invoke() }, enabled = vm.canExport && onEdit != null,
                            shape = MyembroShapes.Button, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Edit, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.viewer_review_edit))
                        }
                    }
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
    if (vm.sewoutSheetOpen) ViewerSewoutSheet(vm)
    if (confirmUsb && report != null) SewoutTransferConfirmation(report,
        onCancel = { confirmUsb = false }, onReview = { confirmUsb = false; vm.openSewout() },
        onContinue = {
            confirmUsb = false
            if (vm.canExport && vm.sewoutReady) {
                if (vm.ensureSourceCurrent()) onExportUsb?.invoke() else vm.openSewout()
            }
        })
    if (exportSheet) {
        val data = vm.render
        var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(data) {
            preview = data?.let { withContext(Dispatchers.Default) { Thumbnails.render(it, 240) } }
        }
        ExportSheet(
            onDismissRequest = { exportSheet = false }, onSavePes = onSaveFile, onShare = onShareFile,
            onExportUsb = checkedUsb, designName = vm.fileName, busy = vm.exporting, enabled = vm.render != null,
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
    var fitRequest by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.pausePlayback() }
    DisposableEffect(vm) { onDispose { vm.pausePlayback() } }
    LaunchedEffect(vm.playing, data) {
        var last = 0L
        while (vm.playing) {
            withFrameNanos { now ->
                if (last != 0L) vm.tick((now - last) / 1e9f)
                last = now
            }
        }
    }

    val detailsScroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= MyembroLayout.ExpandedWidth) {
            val panelWidth = (maxWidth * .42f).coerceIn(280.dp, 400.dp)
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ViewerCanvas(vm, data, fitRequest, { vm.clearSewoutFocus(); fitRequest++ }, Modifier.weight(1f).fillMaxSize())
                ViewerDetails(vm, data,
                    Modifier.width(panelWidth).fillMaxSize().verticalScroll(detailsScroll).padding(vertical = 12.dp),
                    compactStats = true)
            }
        } else {
            val maxPanelHeight = maxHeight * .54f
            Column(Modifier.fillMaxSize()) {
                ViewerCanvas(vm, data, fitRequest, { vm.clearSewoutFocus(); fitRequest++ },
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp))
                ViewerDetails(vm, data, Modifier.fillMaxWidth().heightIn(max = maxPanelHeight)
                    .verticalScroll(detailsScroll).padding(12.dp))
            }
        }
    }
}

@Composable
private fun ViewerCanvas(vm: ViewerViewModel, data: RenderData, fitRequest: Int, onFit: () -> Unit, modifier: Modifier) {
    val blockers = remember(data, vm.sewoutReport) { viewerBlockingHighlights(data.pattern, vm.sewoutReport) }
    Box(modifier.clip(MyembroShapes.Card)) {
        StitchCanvas(
            data = data,
            progress = vm.progress,
            hoop = vm.hoop, frame = vm.frame.spec, preservePlacement = vm.preservePlacement,
            highlight = vm.focusedHighlight, focusRequest = vm.focusRequest,
            blockerHighlights = blockers, onBlockerTap = vm::focusSewout,
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
                Text(vm.frame.dimensions, style = MaterialTheme.typography.labelMedium)
            }
        }
        ViewerCheckBadge(vm, modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, end = 12.dp, top = 58.dp))
        if (vm.focusedHighlight != null) TextButton(onClick = vm::clearSewoutFocus,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                .background(MaterialTheme.colorScheme.surface, MyembroShapes.Button)) {
            Text(stringResource(R.string.sewout_clear_highlight))
        }
        IconButton(
            onClick = onFit,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), MyembroShapes.Button),
        ) { Icon(Icons.Default.FitScreen, contentDescription = stringResource(R.string.fit)) }
    }
}

@Composable
private fun ViewerDetails(vm: ViewerViewModel, data: RenderData, modifier: Modifier, compactStats: Boolean = false) {
    val pattern = data.pattern
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(shape = MyembroShapes.Card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(12.dp)) {
                ViewerPlaybackControls(vm, data)
            }
        }
        StatsRow(pattern, vm.frame, vm.machineProfile, compactStats, vm.preservePlacement, vm.sewoutReport)
        Card(
            shape = MyembroShapes.Card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.viewer_color_order), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    Text("${(vm.progress.coerceAtLeast(0) * 100L / data.playback.endIndex.coerceAtLeast(1)).coerceAtMost(100)}%",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(progress = {
                    vm.progress.toFloat().coerceAtLeast(0f) / data.playback.endIndex.coerceAtLeast(1)
                }, modifier = Modifier.fillMaxWidth())
                ColorBlocks(pattern, vm.progress, vm::seekPlayback)
            }
        }

    }
}

@Composable
private fun StatsRow(pattern: EmbPattern, hoop: FrameOption, machine: io.github.linker2k.myembro.ui.machines.MachineProfile?, compact: Boolean = false, preservePlacement: Boolean = false,
    sewout: io.github.linker2k.myembro.engine.sewout.SewoutReport? = null) {
    val b = viewerBounds(pattern, preservePlacement)
    val seconds = sewout?.time?.totalSeconds?.let { kotlin.math.ceil(it).toLong() } ?: pattern.estimatedSeconds().toLong()
    Card(
        shape = MyembroShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (compact) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(stringResource(R.string.stat_stitches), "%,d".format(pattern.stitchCount), Modifier.weight(1f))
                    Stat(stringResource(R.string.stat_size), b?.let { Dimensions.size(it.width / 10.0, it.height / 10.0) } ?: "-", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(stringResource(R.string.stat_colors), pattern.colorBlocks.size.toString(), Modifier.weight(1f))
                    Stat(stringResource(R.string.stat_time), "%d:%02d".format(seconds / 60, seconds % 60), Modifier.weight(1f))
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.stat_stitches), "%,d".format(pattern.stitchCount), Modifier.weight(1f))

                Stat(stringResource(R.string.stat_colors), pattern.colorBlocks.size.toString(), Modifier.weight(.7f))
                Stat(stringResource(R.string.stat_time), "%d:%02d".format(seconds / 60, seconds % 60), Modifier.weight(1f))
            }
            if (!compact) Stat(stringResource(R.string.stat_size), b?.let { Dimensions.size(it.width / 10.0, it.height / 10.0) } ?: "-", Modifier.fillMaxWidth())
            Text(if (sewout == null) stringResource(R.string.viewer_trims_size_note, pattern.trimCount)
                else stringResource(R.string.sewout_cuts, sewout.statistics.requiredManualCuts, sewout.statistics.possibleManualCuts),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (warning in pattern.warnings) {
                Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            if (b != null) {
                Text(stringResource(R.string.viewer_position,
                    Dimensions.length((b.minX.toDouble() + b.maxX) / 20),
                    Dimensions.length((b.minY.toDouble() + b.maxY) / 20)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val fits = viewerFits(pattern, hoop.spec, preservePlacement) && machine?.fits(b) != false
                Text(stringResource(if (fits) R.string.viewer_fits_hoop else R.string.hoop_too_small),
                    color = if (fits) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
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
                Text(block.thread.displayName,
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.viewer_thread_stitches, counts[i]),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
