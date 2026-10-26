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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Block editing: tap a ShapeObject's regions (topmost wins where they overlap; multi-select adds), then set
 * stitch type, texture, direction, density and order in the sheet. Folded/phone: the sheet sits under the
 * canvas; unfolded or tablet (>= 600dp): it docks on the right. Settings stay in [BlockEditState] until the
 * engine's region command exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockEditScreen(objectName: String, state: BlockEditState, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var version by remember { mutableIntStateOf(0) }
    var multi by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(BlockStitchDraft()) }
    fun changed() { version++; draft = state.draft() }
    Scaffold(topBar = {
        TopAppBar(title = { Column {
            Text(stringResource(R.string.block_edit_title), style = MaterialTheme.typography.titleMedium)
            Text(objectName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.block_edit_done)) } },
            actions = {
                key(version) {
                    IconButton(onClick = { state.undo(); changed() }, enabled = state.undoSteps > 0) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo)) }
                    IconButton(onClick = { state.redo(); changed() }, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
                }
            })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 600.dp
            val sheetMax = maxHeight * 0.58f
            val canvas = @Composable { modifier: Modifier ->
                Column(modifier) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(multi, onClick = { multi = !multi }, label = { Text(stringResource(R.string.block_edit_multi)) },
                            leadingIcon = { Icon(Icons.Default.Checklist, null, Modifier.size(16.dp)) })
                        TextButton(onClick = { state.selectAll(); changed() }) { Text(stringResource(R.string.editor_select_all)) }
                        Spacer(Modifier.weight(1f))
                        SewoutBadge()
                    }
                    key(version) { RegionCanvas(state, Modifier.fillMaxWidth().weight(1f).padding(12.dp)) { x, y, lineMm ->
                        state.tap(x, y, multi, lineMm); changed() } }
                }
            }
            val sheet = @Composable { modifier: Modifier ->
                key(version) { BlockSheet(state.selected.size, draft, { draft = it },
                    onApply = { state.apply(draft); changed() },
                    onOrder = { state.reorder(it); changed() }, modifier) }
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

/** Placeholder for the sewing check verdict of the object after an apply; filled when the engine is connected. */
@Composable
private fun SewoutBadge() {
    AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(R.string.block_edit_sewout_pending)) },
        leadingIcon = { Icon(Icons.Default.HourglassEmpty, null, Modifier.size(16.dp)) })
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
private fun RegionCanvas(state: BlockEditState, modifier: Modifier, onTap: (Double, Double, Double) -> Unit) {
    val outline = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.block_edit_canvas)
    val touchPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    // The tap handler outlives recompositions; read the latest one (multi-select may have changed).
    val latestTap by rememberUpdatedState(onTap)
    Canvas(modifier.semantics { contentDescription = description }.pointerInput(state) {
        detectTapGestures { p ->
            val fit = BlockCanvasFit(state.bounds, size.width.toFloat(), size.height.toFloat())
            latestTap(fit.mmX(p.x), fit.mmY(p.y), maxOf(0.5, (touchPx / fit.scale).toDouble()))
        }
    }) {
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
private fun BlockSheet(count: Int, draft: BlockStitchDraft, onDraft: (BlockStitchDraft) -> Unit, onApply: () -> Unit,
                       onOrder: (Boolean) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
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
            Text(stringResource(R.string.block_edit_selected, count), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Button(onClick = onApply) { Text(stringResource(R.string.block_edit_apply)) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(R.string.block_edit_type)
            Segmented(listOf(R.string.block_edit_type_fill, R.string.block_edit_type_satin, R.string.block_edit_type_running),
                draft.type.ordinal) { onDraft(draft.copy(type = BlockType.entries[it])) }
            if (draft.type == BlockType.FILL) {
                Label(R.string.block_edit_texture)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BlockTexture.entries.forEach { t ->
                        TextureTile(t, draft.tile, t == draft.texture, Modifier.weight(1f), TEXTURE_NAMES[t.ordinal]) { onDraft(draft.copy(texture = t)) }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(draft.angleDeg.toFloat(), { onDraft(draft.copy(angleDeg = it.roundToInt().coerceIn(0, 179))) },
                            valueRange = 0f..179f, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.block_edit_angle, draft.angleDeg), Modifier.width(52.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    if (count > 1) Text(stringResource(R.string.block_edit_same_angle, count), style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                }
                Label(R.string.block_edit_density)
                Segmented(listOf(R.string.block_edit_density_dense, R.string.block_edit_density_normal, R.string.block_edit_density_sparse),
                    draft.density.ordinal) { onDraft(draft.copy(density = BlockDensity.entries[it])) }
                Text(stringResource(R.string.block_edit_density_unverified), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Label(R.string.block_edit_order)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOrder(false) }) {
                    Icon(Icons.Default.ArrowDownward, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.block_edit_back)) }
                OutlinedButton(onClick = { onOrder(true) }) {
                    Icon(Icons.Default.ArrowUpward, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.block_edit_forward)) }
            }
            Spacer(Modifier.height(8.dp))
        }
        }
    }
}

private val TEXTURE_NAMES = listOf(R.string.block_edit_texture_basic, R.string.block_edit_texture_pattern, R.string.block_edit_texture_contour,
    R.string.block_edit_texture_circular, R.string.block_edit_texture_gradient)

@Composable
private fun Label(text: Int) = Text(stringResource(text), style = MaterialTheme.typography.labelLarge)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(labels: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(i == selected, { onSelect(i) }, SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun TextureTile(texture: BlockTexture, tile: String, chosen: Boolean, modifier: Modifier, name: Int?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
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
        BlockTexture.GRADIENT -> { var y = 2f; var gap = 2f; while (y < h) { drawLine(ink, Offset(0f, y), Offset(w, y), stroke.width); y += gap; gap *= 1.45f } }
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
