// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.ShapeObject
import io.github.linker2k.myembro.engine.edit.BlockOrderMove
import io.github.linker2k.myembro.ui.editor.EditorHistoryButton
import io.github.linker2k.myembro.ui.editor.EditorViewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Block editing: tap a ShapeObject's regions (topmost wins where they overlap; multi-select adds), then set
 * stitch type, texture, direction and density in the sheet. Apply runs the engine's SetBlockStitch in the
 * background (one undo step in the editor history); the badge shows the real hoop and sewing check.
 * Folded/phone: the sheet sits under the canvas; unfolded or tablet (>= 600dp): it docks on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockEditScreen(vm: EditorViewModel, shape: ShapeObject, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    // Leaving the screen cancels a running generation; the design keeps its last committed settings.
    DisposableEffect(Unit) { onDispose { vm.cancelBlockWork() } }
    var selected by remember(shape.id) { mutableStateOf(emptySet<String>()) }
    val state = remember(shape,vm.project.threadColors) { BlockEditState.of(shape,vm.project).also { it.restore(selected) } }
    var multi by remember { mutableStateOf(false) }
    var draft by remember(shape.id) { mutableStateOf(BlockStitchDraft()) }
    // Engine prechecks per region, off the main thread (satin probing generates stitches).
    val byRegion by produceState<Map<String, BlockOptions>?>(null, shape) {
        value = withContext(Dispatchers.Default) { shape.regions.associate { it.id to BlockApply.options(it, shape.transform) } }
    }
    fun picked() { selected = state.selected; draft = state.draft() }
    // Every committed change (apply, undo, redo) gets a fresh check of the whole design.
    LaunchedEffect(shape) { if (vm.blockStatus !is BlockStatus.Generating) vm.checkBlockSewout() }
    Scaffold(topBar = {
        TopAppBar(title = { Column {
            Text(stringResource(R.string.block_edit_title), style = MaterialTheme.typography.titleMedium)
            Text(shape.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.block_edit_done)) } },
            actions = { EditorHistoryButton(vm, redo = false); EditorHistoryButton(vm, redo = true) })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 600.dp
            val sheetMax = maxHeight * 0.58f
            val status = vm.blockStatus
            val canvas = @Composable { modifier: Modifier ->
                Column(modifier) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(multi, onClick = { multi = !multi }, label = { Text(stringResource(R.string.block_edit_multi)) },
                            leadingIcon = { Icon(Icons.Default.Checklist, null, Modifier.size(16.dp)) })
                        TextButton(onClick = { state.selectAll(); picked() }) { Text(stringResource(R.string.editor_select_all)) }
                        Spacer(Modifier.weight(1f))
                        SewoutBadge(status)
                    }
                    if (status is BlockStatus.Checking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                    if (status is BlockStatus.Generating) GeneratingRow(vm, status)
                    key(state, selected) { RegionCanvas(state, Modifier.fillMaxWidth().weight(1f).padding(12.dp),
                        vm.project.fabricColorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.background) { x, y, lineMm ->
                        state.tap(x, y, multi, lineMm); picked() } }
                }
            }
            val sheet = @Composable { modifier: Modifier ->
                BlockSheet(selected.size, draft, { draft = it }, status, state.options(byRegion),
                    order = BlockOrderInfo.of(shape.regionOrder, selected),
                    onOrder = { move -> vm.applyBlockOrder(shape.id, selected, move) },
                    onApply = { vm.applyBlockStitch(shape.id, selected, BlockApply.patch(draft)) }, modifier)
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                canvas(Modifier.weight(1f).fillMaxHeight())
                sheet(Modifier.width(360.dp).fillMaxHeight())
            } else Column(Modifier.fillMaxSize()) {
                canvas(Modifier.fillMaxWidth().weight(1f))
                // Fixed height: the canvas keeps its size (and the regions their place) when a selection opens the settings.
                sheet(Modifier.fillMaxWidth().height(sheetMax))
            }
        }
    }
}

/** Generation in progress: elapsed seconds and Cancel (nothing is committed, previous settings stay). */
@Composable
private fun GeneratingRow(vm: EditorViewModel, status: BlockStatus.Generating) {
    var elapsed by remember(status) { mutableLongStateOf(vm.blockElapsedMs(status)) }
    LaunchedEffect(status) { while (true) { elapsed = vm.blockElapsedMs(status); delay(500) } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(Modifier.weight(1f))
        Text(stringResource(R.string.block_edit_elapsed, elapsed / 1000), Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = vm::cancelBlockApply) { Text(stringResource(R.string.block_edit_cancel)) }
    }
}

/** Short localized reason; generation failures and sewing-check blocks read differently. Details show the engine text. */
@Composable
private fun FailureNote(status: BlockStatus.Blocked) {
    var details by remember(status) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val reason = failureText(status.failure)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(if (status.applyFailed) stringResource(R.string.block_edit_kept, reason) else reason,
            style = MaterialTheme.typography.bodySmall, color = colors.error)
        TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(if (details) R.string.block_edit_details_hide else R.string.block_edit_details), style = MaterialTheme.typography.labelSmall)
        }
        if (details) Text(status.failure.detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
internal fun failureText(f: BlockFailure): String = when (f.kind) {
    BlockFailure.Kind.MEANDER_REGION_TOO_SMALL -> stringResource(R.string.block_fail_meander_small)
    BlockFailure.Kind.MEANDER_FAILED -> stringResource(R.string.block_fail_meander_failed)
    BlockFailure.Kind.SATIN_UNSUPPORTED -> stringResource(R.string.block_fail_satin)
    BlockFailure.Kind.SATIN_TOO_LONG -> stringResource(R.string.block_fail_satin_long)
    BlockFailure.Kind.NO_STITCHES -> stringResource(R.string.block_fail_no_stitches)
    BlockFailure.Kind.UNSUPPORTED_METHOD -> stringResource(R.string.block_fail_unsupported)
    BlockFailure.Kind.GENERATION_FAILED -> stringResource(R.string.block_fail_generation)
    BlockFailure.Kind.SETTINGS_INVALID -> stringResource(R.string.block_fail_settings)
    BlockFailure.Kind.DEVICE_FAILED -> stringResource(R.string.block_fail_device)
    BlockFailure.Kind.OUT_OF_MEMORY -> stringResource(R.string.block_fail_memory)
    BlockFailure.Kind.DESIGN_CHANGED -> stringResource(R.string.block_fail_changed)
    BlockFailure.Kind.EDITOR_BUSY -> stringResource(R.string.block_fail_busy)
    BlockFailure.Kind.SEWOUT_BLOCKED -> stringResource(R.string.block_fail_sewout,
        f.sewoutCodes.map { sewoutText(it) }.distinct().ifEmpty { listOf(stringResource(R.string.block_sewout_other)) }.joinToString(", "))
}

@Composable
private fun sewoutText(code: String): String = stringResource(when (code) {
    "HOOP_SAFETY_MARGIN", "HOOP_EXCEEDED", "MACHINE_AREA_EXCEEDED" -> R.string.block_sewout_hoop
    "DENSITY_HOTSPOT" -> R.string.block_sewout_density
    "LONG_STITCH", "MACHINE_STITCH_LENGTH_EXCEEDED", "PEC_DELTA_EXCEEDED" -> R.string.block_sewout_long
    "REPEATED_PENETRATION", "ZERO_LENGTH_MOTION" -> R.string.block_sewout_repeated
    "MISSING_LOCK_STITCH" -> R.string.block_sewout_lock
    "EMPTY_DESIGN" -> R.string.block_sewout_empty
    "ANALYSIS_LIMIT", "ANALYSIS_INCOMPLETE" -> R.string.block_sewout_incomplete
    "INVALID_PES", "PEC_ORIGIN" -> R.string.block_sewout_file
    else -> R.string.block_sewout_other
})

/** The whole design's real result: placed inside the hoop and SewoutCheck without blockers. */
@Composable
private fun SewoutBadge(status: BlockStatus) {
    val colors = MaterialTheme.colorScheme
    val (text, icon, tint) = when (status) {
        BlockStatus.Idle, BlockStatus.Checking -> Triple(R.string.block_edit_sewout_checking, Icons.Default.HourglassEmpty, colors.onSurfaceVariant)
        is BlockStatus.Generating -> Triple(R.string.block_edit_generating, Icons.Default.HourglassEmpty, colors.onSurfaceVariant)
        BlockStatus.Passed -> Triple(R.string.block_edit_sewout_pass, Icons.Default.CheckCircle, colors.primary)
        BlockStatus.Cancelled -> Triple(R.string.block_edit_cancelled_badge, Icons.Default.Info, colors.onSurfaceVariant)
        is BlockStatus.Blocked -> if (status.applyFailed) Triple(R.string.block_edit_apply_failed, Icons.Default.ErrorOutline, colors.error)
            else Triple(R.string.block_edit_sewout_blocked, Icons.Default.ErrorOutline, colors.error)
    }
    AssistChip(onClick = {}, label = { Text(stringResource(text), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp), tint = tint) })
}

/** Object-local mm to canvas pixels: the regions' bounds fitted and centered, y down. */
internal class BlockCanvasFit(bounds: RegionBounds?, width: Float, height: Float) {
    private val b = bounds ?: RegionBounds(-1.0, -1.0, 1.0, 1.0)
    val scale = minOf(width / (b.maxX - b.minX), height / (b.maxY - b.minY)).toFloat() * 0.92f
    private val ox = width / 2f - ((b.minX + b.maxX) / 2).toFloat() * scale
    private val oy = height / 2f - ((b.minY + b.maxY) / 2).toFloat() * scale
    fun x(mm: Double) = ox + mm.toFloat() * scale
    fun y(mm: Double) = oy + mm.toFloat() * scale
    fun mmX(px: Float) = ((px - ox) / scale).toDouble()
    fun mmY(px: Float) = ((px - oy) / scale).toDouble()
}

@Composable
internal fun RegionCanvas(state: BlockEditState, modifier: Modifier, fabricColor: Color, onTap: (Double, Double, Double) -> Unit) {
    val outline = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.block_edit_canvas)
    val touchPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    // The tap handler outlives recompositions; read the latest one (multi-select may have changed).
    val latestTap by rememberUpdatedState(onTap)
    Canvas(modifier.clipToBounds().semantics { contentDescription = description }.pointerInput(state) {
        detectTapGestures { p ->
            val fit = BlockCanvasFit(state.bounds, size.width.toFloat(), size.height.toFloat())
            latestTap(fit.mmX(p.x), fit.mmY(p.y), maxOf(0.5, (touchPx / fit.scale).toDouble()))
        }
    }) {
        drawRect(fabricColor)
        val fit = BlockCanvasFit(state.bounds, size.width, size.height)
        val dim = state.selected.isNotEmpty()
        state.regions.forEach { r ->
            val chosen = r.id in state.selected
            val color = Color(r.color).copy(alpha = if (!dim || chosen) 1f else 0.3f)
            val (rings, lines) = regionPaths(r, fit)
            drawPath(rings, color)
            drawPath(lines, color, style = Stroke(1.5.dp.toPx()))
        }
        state.regions.filter { it.id in state.selected }.forEach { r ->
            val (rings, lines) = regionPaths(r, fit)
            listOf(rings, lines).forEach { path ->
                drawPath(path, Color.White, style = Stroke(5.dp.toPx()))
                drawPath(path, outline, style = Stroke(2.5.dp.toPx()))
            }
        }
    }
}

/** Closed rings as one even-odd path (holes stay open), open paths as lines. */
private fun regionPaths(r: BlockRegion, fit: BlockCanvasFit): Pair<Path, Path> {
    val rings = Path().apply { fillType = PathFillType.EvenOdd }
    val lines = Path()
    r.paths.forEach { p ->
        if (p.xs.isEmpty()) return@forEach
        val target = if (p.closed) rings else lines
        target.moveTo(fit.x(p.xs[0]), fit.y(p.ys[0]))
        for (i in 1 until p.xs.size) target.lineTo(fit.x(p.xs[i]), fit.y(p.ys[i]))
        if (p.closed) target.close()
    }
    return rings to lines
}

@Composable
private fun BlockSheet(count: Int, draft: BlockStitchDraft, onDraft: (BlockStitchDraft) -> Unit, status: BlockStatus, loaded: BlockOptions?,
                       order: BlockOrderInfo, onOrder: (BlockOrderMove) -> Unit,
                       onApply: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    // Until the prechecks arrive nothing is disabled, but Apply waits for them.
    val options = loaded ?: BlockOptions()
    Surface(modifier, color = colors.surfaceContainerLow, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), tonalElevation = 2.dp) {
        if (count == 0) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.block_edit_pick_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            return@Surface
        }
        Column {
        // Apply stays on screen while the settings below scroll.
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.block_edit_selected, count, count), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Button(onClick = onApply, enabled = status !is BlockStatus.Generating && loaded != null && options.allows(draft)) { Text(stringResource(R.string.block_edit_apply)) }
        }
        // A refused apply keeps the previous settings: a short localized reason; the engine text only in details.
        if (status is BlockStatus.Blocked) FailureNote(status)
        if (status is BlockStatus.Cancelled) Text(stringResource(R.string.block_edit_cancelled),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(R.string.block_edit_type)
            // Options a selected region does not allow stay visible but disabled.
            if (!options.allows(draft)) Text(stringResource(R.string.block_edit_option_unavailable), style = MaterialTheme.typography.labelSmall, color = colors.error)
            // Short reasons for what the selected regions cannot take, e.g. "Satin: this shape cannot be satin".
            options.unavailable.forEach { (type, code) ->
                Text(stringResource(R.string.block_edit_type_unavailable, stringResource(TYPE_NAMES[type.ordinal]), reasonText(code)),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Segmented(listOf(R.string.block_edit_type_fill, R.string.block_edit_type_satin, R.string.block_edit_type_running),
                draft.type.ordinal, enabled = { BlockType.entries[it] in options.types }) { onDraft(draft.copy(type = BlockType.entries[it])) }
            if (draft.type == BlockType.FILL) {
                Label(R.string.block_edit_texture)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BlockTexture.entries.forEach { t ->
                        TextureTile(t, draft.tile, t == draft.texture, Modifier.weight(1f), TEXTURE_NAMES[t.ordinal], enabled = t in options.textures) {
                            onDraft(draft.copy(texture = t)) }
                    }
                }
                if (draft.texture == BlockTexture.PATTERN) {
                    Text(stringResource(R.string.block_edit_tiles), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    MeanderGrid(draft.tile) { onDraft(draft.copy(tile = it)) }
                }
            }
            if (draft.type != BlockType.RUNNING) {
                Label(R.string.block_edit_direction)
                Segmented(listOf(R.string.block_edit_direction_auto, R.string.block_edit_direction_manual), if (draft.autoAngle) 0 else 1) {
                    onDraft(draft.copy(autoAngle = it == 0))
                }
                if (!draft.autoAngle) {
                    val angleLabel = stringResource(R.string.block_edit_angle_label)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(draft.angleDeg.toFloat(), { onDraft(draft.copy(angleDeg = it.roundToInt().coerceIn(0, 179))) },
                            valueRange = 0f..179f, modifier = Modifier.weight(1f).semantics { contentDescription = angleLabel })
                        Text(stringResource(R.string.block_edit_angle, draft.angleDeg), Modifier.width(52.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    if (count > 1) Text(stringResource(R.string.block_edit_same_angle, count), style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                }
                Label(R.string.block_edit_density)
                // Only steps with an Ink/Stitch basis are offered (BlockApply.ROW_SPACING_MM).
                Segmented(listOf(R.string.block_edit_density_dense, R.string.block_edit_density_normal, R.string.block_edit_density_sparse),
                    draft.density.ordinal, enabled = { BlockApply.ROW_SPACING_MM[BlockDensity.entries[it]] != null }) {
                    onDraft(draft.copy(density = BlockDensity.entries[it])) }
                // Normal = Ink/Stitch default row_spacing_mm 0.25 (fill_stitch.py:364–380, BlockApply.ROW_SPACING_MM);
                // Dense/Sparse have no upstream preset and stay disabled until measured (docs/agent/block-stitch.md).
                Text(stringResource(R.string.block_edit_density_later), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            // Sewing order (SetBlockOrder): earlier = sewn first; several selected regions move together.
            Label(R.string.block_edit_order)
            Text(if (count == 1) stringResource(R.string.block_edit_order_position, order.first + 1, order.total)
                else stringResource(R.string.block_edit_order_range, order.first + 1, order.last + 1, order.total),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            val idle = status !is BlockStatus.Generating
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(BlockOrderMove.FIRST to R.string.block_edit_order_first, BlockOrderMove.FORWARD to R.string.block_edit_order_forward,
                    BlockOrderMove.BACKWARD to R.string.block_edit_order_backward, BlockOrderMove.LAST to R.string.block_edit_order_last).forEach { (move, label) ->
                    OutlinedButton(onClick = { onOrder(move) }, enabled = idle && order.canMove(move), modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)) { Text(stringResource(label), maxLines = 1) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        }
    }
}

private val TYPE_NAMES = listOf(R.string.block_edit_type_fill, R.string.block_edit_type_satin, R.string.block_edit_type_running)

/** Engine reason codes in a few words; an unknown code is shown as is. */
@Composable
private fun reasonText(code: String): String = when (code) {
    "SATIN_GEOMETRY_UNSUPPORTED" -> stringResource(R.string.block_reason_satin_geometry)
    "SATIN_GENERATION_FAILED" -> stringResource(R.string.block_reason_satin_failed)
    "SATIN_STITCH_TOO_LONG" -> stringResource(R.string.block_reason_satin_too_long)
    "NO_RUNNING_PATH" -> stringResource(R.string.block_reason_no_running)
    "EMPTY_FILL_REGION" -> stringResource(R.string.block_reason_empty_fill)
    else -> code
}

private val TEXTURE_NAMES = listOf(R.string.block_edit_texture_basic, R.string.block_edit_texture_pattern, R.string.block_edit_texture_contour,
    R.string.block_edit_texture_circular)

@Composable
private fun Label(text: Int) = Text(stringResource(text), style = MaterialTheme.typography.labelLarge)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(labels: List<Int>, selected: Int, enabled: (Int) -> Boolean = { true }, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(i == selected, { onSelect(i) }, SegmentedButtonDefaults.itemShape(i, labels.size), enabled = enabled(i)) {
                // Dim explicitly: the theme's disabled label color is too close to the enabled one to read.
                Text(stringResource(label), Modifier.alpha(if (enabled(i)) 1f else 0.38f)) }
        }
    }
}

@Composable
private fun TextureTile(texture: BlockTexture, tile: String, chosen: Boolean, modifier: Modifier, name: Int?, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.alpha(if (enabled) 1f else 0.38f).clickable(enabled = enabled, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)
            .background(colors.surface, RoundedCornerShape(8.dp))
            .border(BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) colors.primary else colors.outlineVariant), RoundedCornerShape(8.dp))
            .padding(6.dp)) { drawTexture(texture, tile, colors.onSurface) }
        if (name != null) Text(stringResource(name), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Every bundled meander tile, rendered from its SVG (cached), in a fixed-height grid inside the scrolling sheet. */
@Composable
private fun MeanderGrid(selected: String, onSelect: (String) -> Unit) {
    val ids by produceState(emptyList<String>()) { value = withContext(Dispatchers.IO) { MeanderThumbnails.ids() } }
    LazyVerticalGrid(GridCells.Adaptive(64.dp), Modifier.fillMaxWidth().height(232.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(ids, key = { it }) { id ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextureTile(BlockTexture.PATTERN, id, id == selected, Modifier.fillMaxWidth(), null) { onSelect(id) }
                Text(id, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/** Thumbnails only, drawn as plain lines; not stitch output. */
private fun DrawScope.drawTexture(texture: BlockTexture, tile: String, ink: Color) {
    val w = size.width; val h = size.height; val stroke = Stroke(1.2.dp.toPx())
    when (texture) {
        BlockTexture.BASIC -> for (i in 1..6) drawLine(ink, Offset(0f, h * i / 7), Offset(w, h * i / 7), stroke.width)
        BlockTexture.CONTOUR -> for (i in 0..3) { val inset = i * w / 9; drawRoundRect(ink, Offset(inset, inset), Size(w - 2 * inset, h - 2 * inset),
            androidx.compose.ui.geometry.CornerRadius(w / 5 - inset / 2), style = stroke) }
        BlockTexture.CIRCULAR -> for (i in 1..4) drawCircle(ink, w / 2 * i / 4.2f, Offset(w / 2, h / 2), style = stroke)
        BlockTexture.PATTERN -> drawMeander(tile, ink, stroke.width)
    }
}

/** The tile's real lines, loaded off the main thread on first use and cached by [MeanderThumbnails]. */
private fun DrawScope.drawMeander(id: String, ink: Color, width: Float) {
    val lines = thumbnails[id] ?: run { loadThumbnail(id); return }
    val c = lines.coords
    clipRect {
        var i = 0
        while (i + 3 < c.size) {
            drawLine(ink, Offset(c[i] * size.width, c[i + 1] * size.height), Offset(c[i + 2] * size.width, c[i + 3] * size.height), width)
            i += 4
        }
    }
}

private val thumbnails = androidx.compose.runtime.snapshots.SnapshotStateMap<String, MeanderThumbnails.Lines>()
private val loading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
private fun loadThumbnail(id: String) {
    if (!loading.add(id)) return
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
        val lines = runCatching { MeanderThumbnails.lines(id) }
            .onFailure { android.util.Log.w("BlockEdit", "Meander tile $id", it) }.getOrNull() ?: return@launch
        withContext(Dispatchers.Main) { thumbnails[id] = lines }
    }
}
