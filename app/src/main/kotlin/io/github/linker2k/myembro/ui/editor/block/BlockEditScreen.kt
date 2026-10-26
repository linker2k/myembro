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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Block editing draft: tap regions of one object (multi-select adds), then set stitch type, texture,
 * direction, density and order in the sheet. Folded/phone: the sheet sits under the canvas; unfolded or
 * tablet (>= 600dp): it docks on the right. Works on [BlockEditState] until ShapeObject replaces it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockEditScreen(objectName: String, onClose: () -> Unit, state: BlockEditState = remember { BlockEditState.sample() }) {
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
                    key(version) { RegionCanvas(state, Modifier.fillMaxWidth().weight(1f).padding(12.dp)) { x, y ->
                        state.tap(x, y, multi); changed() } }
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
                sheet(Modifier.fillMaxWidth().heightIn(max = sheetMax))
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

@Composable
private fun RegionCanvas(state: BlockEditState, modifier: Modifier, onTap: (Float, Float) -> Unit) {
    val outline = MaterialTheme.colorScheme.primary
    val hoop = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.pointerInput(Unit) {
        detectTapGestures { p ->
            val side = min(size.width, size.height).toFloat()
            val left = (size.width - side) / 2f; val top = (size.height - side) / 2f
            onTap((p.x - left) / side, (p.y - top) / side)
        }
    }) {
        val side = min(size.width, size.height)
        val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        drawRect(hoop, origin, Size(side, side), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
        val dim = state.selected.isNotEmpty()
        state.regions.forEach { r ->
            val chosen = r.id in state.selected
            val color = Color(r.color).copy(alpha = if (!dim || chosen) 1f else 0.35f)
            drawShape(r.shape, origin, side, color, null)
            if (r.stitch.type == BlockType.SATIN) drawShape(r.shape, origin, side, Color.White.copy(alpha = 0.5f), Stroke(1.dp.toPx()))
        }
        state.regions.filter { it.id in state.selected }.forEach { r ->
            drawShape(r.shape, origin, side, Color.White, Stroke(5.dp.toPx()))
            drawShape(r.shape, origin, side, outline, Stroke(3.dp.toPx()))
        }
    }
}

private fun DrawScope.drawShape(shape: FakeShape, origin: Offset, side: Float, color: Color, stroke: Stroke?) {
    val style = stroke ?: androidx.compose.ui.graphics.drawscope.Fill
    when (shape) {
        is FakeShape.Ellipse -> drawOval(color, Offset(origin.x + (shape.cx - shape.rx) * side, origin.y + (shape.cy - shape.ry) * side),
            Size(shape.rx * 2 * side, shape.ry * 2 * side), style = style)
        is FakeShape.Rect -> drawRect(color, Offset(origin.x + shape.left * side, origin.y + shape.top * side),
            Size((shape.right - shape.left) * side, (shape.bottom - shape.top) * side), style = style)
    }
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
                        TextureTile(t, 0, t == draft.texture, Modifier.weight(1f), TEXTURE_NAMES[t.ordinal]) { onDraft(draft.copy(texture = t)) }
                    }
                }
                if (draft.texture == BlockTexture.PATTERN) {
                    Text(stringResource(R.string.block_edit_tiles), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    (0 until TILE_COUNT).chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { i -> TextureTile(BlockTexture.PATTERN, i, i == draft.tile, Modifier.weight(1f), null) {
                                onDraft(draft.copy(tile = i)) } }
                        }
                    }
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
private const val TILE_COUNT = 8

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
private fun TextureTile(texture: BlockTexture, tile: Int, chosen: Boolean, modifier: Modifier, name: Int?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)
            .background(colors.surface, RoundedCornerShape(8.dp))
            .border(BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) colors.primary else colors.outlineVariant), RoundedCornerShape(8.dp))
            .padding(6.dp)) { drawTexture(texture, tile, colors.onSurface) }
        if (name != null) Text(stringResource(name), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Thumbnails only, drawn as plain lines; not stitch output. */
private fun DrawScope.drawTexture(texture: BlockTexture, tile: Int, ink: Color) {
    val w = size.width; val h = size.height; val stroke = Stroke(1.2.dp.toPx())
    when (texture) {
        BlockTexture.BASIC -> for (i in 1..6) drawLine(ink, Offset(0f, h * i / 7), Offset(w, h * i / 7), stroke.width)
        BlockTexture.GRADIENT -> { var y = 2f; var gap = 2f; while (y < h) { drawLine(ink, Offset(0f, y), Offset(w, y), stroke.width); y += gap; gap *= 1.45f } }
        BlockTexture.CONTOUR -> for (i in 0..3) { val inset = i * w / 9; drawRoundRect(ink, Offset(inset, inset), Size(w - 2 * inset, h - 2 * inset),
            androidx.compose.ui.geometry.CornerRadius(w / 5 - inset / 2), style = stroke) }
        BlockTexture.CIRCULAR -> for (i in 1..4) drawCircle(ink, w / 2 * i / 4.2f, Offset(w / 2, h / 2), style = stroke)
        BlockTexture.PATTERN -> drawPath(meander(tile, w, h), ink, style = stroke)
    }
}

/** A few simple meander-like tile drawings (Greek key, zigzag, waves, steps...) to stand in for Ink/Stitch tiles. */
private fun meander(tile: Int, w: Float, h: Float): Path = Path().apply {
    val n = 3; val cw = w / n; val ch = h / n
    for (row in 0 until n) for (col in 0 until n) {
        val x = col * cw; val y = row * ch
        when (tile % TILE_COUNT) {
            0 -> { moveTo(x, y + ch); lineTo(x, y); lineTo(x + cw * .8f, y); lineTo(x + cw * .8f, y + ch * .6f); lineTo(x + cw * .35f, y + ch * .6f); lineTo(x + cw * .35f, y + ch * .3f) }
            1 -> { moveTo(x, y + ch * .8f); lineTo(x + cw / 2, y + ch * .2f); lineTo(x + cw, y + ch * .8f) }
            2 -> { moveTo(x, y + ch / 2); quadraticTo(x + cw / 4, y, x + cw / 2, y + ch / 2); quadraticTo(x + cw * .75f, y + ch, x + cw, y + ch / 2) }
            3 -> { moveTo(x, y + ch); lineTo(x, y + ch / 2); lineTo(x + cw / 2, y + ch / 2); lineTo(x + cw / 2, y); lineTo(x + cw, y) }
            4 -> { addOval(androidx.compose.ui.geometry.Rect(x + cw * .2f, y + ch * .2f, x + cw * .8f, y + ch * .8f)) }
            5 -> { moveTo(x + cw / 2, y); lineTo(x + cw, y + ch / 2); lineTo(x + cw / 2, y + ch); lineTo(x, y + ch / 2); close() }
            6 -> { moveTo(x, y + ch * .25f); lineTo(x + cw, y + ch * .25f); moveTo(x + cw * .5f, y + ch * .25f); lineTo(x + cw * .5f, y + ch * .75f); moveTo(x, y + ch * .75f); lineTo(x + cw, y + ch * .75f) }
            else -> { moveTo(x, y); lineTo(x + cw, y + ch); moveTo(x + cw, y); lineTo(x, y + ch) }
        }
    }
}
