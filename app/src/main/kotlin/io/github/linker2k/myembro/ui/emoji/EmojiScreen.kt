// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.emoji

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.emoji.CatalogEmoji
import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.StitchCanvas
import io.github.linker2k.myembro.ui.format.Dimensions
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import io.github.linker2k.myembro.ui.sewoutMinutes
import io.github.linker2k.myembro.ui.theme.MyembroShapes

/** Emoji → design: search and categories over the Fluent Flat catalog, bundled pictures first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiScreen(vm: EmojiViewModel, settings: EmbroiderySettings, onBack: () -> Unit, onEdit: () -> Unit) {
    LaunchedEffect(settings) { vm.configure(settings) }
    val picked = vm.selected
    BackHandler(enabled = picked != null) { vm.close() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.emoji_title)) }, navigationIcon = {
            IconButton(onClick = { if (picked != null) vm.close() else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.photo_back))
            }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (picked == null) Picker(vm) else Result(vm, picked, onEdit)
        }
    }
}

@Composable
private fun ColumnScope.Picker(vm: EmojiViewModel) {
    OutlinedTextField(vm.query, vm::search, Modifier.fillMaxWidth(), singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(stringResource(R.string.emoji_search)) })
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(vm.group == null && vm.query.isEmpty(), { vm.showGroup(null) }, { Text(stringResource(R.string.emoji_bundled)) }) }
        items(vm.groups) { g -> FilterChip(vm.group == g && vm.query.isEmpty(), { vm.showGroup(g) }, { Text(groupLabel(g)) }) }
    }
    val list = vm.visible
    if (list.isEmpty()) Text(stringResource(R.string.emoji_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyVerticalGrid(GridCells.Adaptive(72.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(list, key = { it.id }) { e -> Cell(e, vm.art[e.id], vm.isAvailable(e)) { vm.pick(e) } }
    }
}

@Composable
private fun Cell(e: CatalogEmoji, art: VectorArt.Art?, available: Boolean, onClick: () -> Unit) {
    val description = if (available) displayName(e) else stringResource(R.string.emoji_download_name, displayName(e))
    Surface(Modifier.aspectRatio(1f).clip(MyembroShapes.Card).clickable(onClick = onClick).semantics { contentDescription = description },
        color = MaterialTheme.colorScheme.surface, shape = MyembroShapes.Card) {
        Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
            if (art != null) Picture(art, Modifier.fillMaxSize())
            else Text(e.glyph, fontSize = 30.sp, textAlign = TextAlign.Center)
            if (!available) Icon(Icons.Default.CloudDownload, null, Modifier.align(Alignment.BottomEnd).size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The SVG picture itself (flattened paths in source colors), drawn fit to the cell. */
@Composable
private fun Picture(art: VectorArt.Art, modifier: Modifier) {
    val shapes = remember(art) {
        art.shapes.map { s ->
            val path = Path().apply {
                fillType = if (s.fillRule == FillRule.EVEN_ODD) PathFillType.EvenOdd else PathFillType.NonZero
                for (line in SvgPath.flatten(s.d, s.transform, tolerance = 0.05)) line.points.forEachIndexed { i, p ->
                    if (i == 0) moveTo(p.x.toFloat(), p.y.toFloat()) else lineTo(p.x.toFloat(), p.y.toFloat())
                }
            }
            Triple(s, path, s.fill)
        }
    }
    Canvas(modifier) {
        val box = art.viewBox
        val k = minOf(size.width / box[2].toFloat(), size.height / box[3].toFloat())
        withTransform({ translate((size.width - box[2].toFloat() * k) / 2, (size.height - box[3].toFloat() * k) / 2); scale(k, k, androidx.compose.ui.geometry.Offset.Zero); translate(-box[0].toFloat(), -box[1].toFloat()) }) {
            for ((s, path, fill) in shapes) {
                fill?.let { drawPath(path, Color(0xFF000000.toInt() or it)) }
                s.stroke?.let { drawPath(path, Color(0xFF000000.toInt() or it), style = Stroke(s.strokeWidth.toFloat(),
                    cap = if (s.lineCap == "round") StrokeCap.Round else StrokeCap.Butt)) }
            }
        }
    }
}

@Composable
private fun ColumnScope.Result(vm: EmojiViewModel, e: CatalogEmoji, onEdit: () -> Unit) {
    Text(displayName(e), style = MaterialTheme.typography.titleLarge)
    val data = vm.render
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
        if (data != null) StitchCanvas(data, Int.MAX_VALUE, Hoop.H100, false, 0, Modifier.fillMaxSize(),
            colors.background.toArgb(), colors.outline.toArgb(), frame = vm.hoop, preservePlacement = false)
        else if (vm.busy) CircularProgressIndicator()
    }
    if (vm.busy && !vm.isAvailable(e)) Text(stringResource(R.string.emoji_downloading), color = colors.onSurfaceVariant)
    vm.report?.let { report ->
        AssistChip(onClick = {}, label = { Text(stringResource(if (vm.passed) R.string.photo_pass else R.string.photo_blocked)) },
            colors = AssistChipDefaults.assistChipColors(labelColor = if (vm.passed) colors.primary else colors.error))
        Text(stringResource(R.string.photo_stats, report.statistics.stitchCount, sewoutMinutes(report.time.totalSeconds)),
            style = MaterialTheme.typography.titleMedium)
        vm.sizeMm?.let { (w, h) -> Text(stringResource(R.string.photo_result_size, Dimensions.size(w, h)), style = MaterialTheme.typography.bodyMedium) }
    }
    vm.widthRange?.let { range -> vm.sizeMm?.let { (w, _) ->
        io.github.linker2k.myembro.ui.photo.EmbroiderySizeSlider(w, range, vm::changeSize)
    } }
    vm.error?.let { Text(stringResource(it), color = colors.error) }
    Spacer(Modifier.weight(1f))
    Button(onClick = onEdit, enabled = !vm.busy && vm.passed, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(stringResource(R.string.emoji_edit))
    }
}

/** CLDR Korean name in a Korean UI, the Fluent (English CLDR) name otherwise. */
@Composable
private fun displayName(e: CatalogEmoji): String =
    if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ko" && e.ko.isNotEmpty()) e.ko
    else e.name.replaceFirstChar { it.uppercase() }

@Composable
private fun groupLabel(group: String): String = when (group) {
    "Smileys & Emotion" -> stringResource(R.string.emoji_group_smileys)
    "People & Body" -> stringResource(R.string.emoji_group_people)
    "Animals & Nature" -> stringResource(R.string.emoji_group_animals)
    "Food & Drink" -> stringResource(R.string.emoji_group_food)
    "Travel & Places" -> stringResource(R.string.emoji_group_travel)
    "Activities" -> stringResource(R.string.emoji_group_activities)
    "Objects" -> stringResource(R.string.emoji_group_objects)
    "Symbols" -> stringResource(R.string.emoji_group_symbols)
    "Flags" -> stringResource(R.string.emoji_group_flags)
    else -> group
}
