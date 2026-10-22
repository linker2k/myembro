// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.fonts

import android.app.Application
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.lettering.Polyline
import io.github.linker2k.myembro.ui.ThreadRendering
import io.github.linker2k.myembro.ui.lettering.SingleLineFontBadge
import io.github.linker2k.myembro.fonts.CatalogFont
import io.github.linker2k.myembro.fonts.ChecksumMismatchException
import io.github.linker2k.myembro.fonts.FontRepository
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Linen = Color(0xFFF6F0E6)
private val Ink = Color(0xFF2B2420)
private val Terracotta = Color(0xFFC4552D)
private val Muted = Color(0xFF6B5C4E)
private val Line = Color(0xFFEADFCF)

enum class FontFilter { KOREAN, LATIN, SINGLE_LINE, OUTLINE }

/** What a card can preview once its font is installed. */
sealed interface FontPreview {
    data class Outline(val typeface: Typeface) : FontPreview
    data class Strokes(val strokes: List<Polyline>, val referenceHeight: Double = 1000.0) : FontPreview
}

class FontLibraryViewModel(app: Application) : AndroidViewModel(app) {
    val repo = FontRepository(app)
    val fonts: List<CatalogFont> = repo.catalog

    var filters by mutableStateOf<Set<FontFilter>>(emptySet())
        private set
    val installed = mutableStateMapOf<String, Boolean>()
    /** Download progress 0..1 for fonts currently downloading. */
    val progress = mutableStateMapOf<String, Float>()
    val previews = mutableStateMapOf<String, FontPreview>()

    /** One-shot snackbar text; the screen clears it after showing. */
    var message by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) { repo.installedIds() }
            fonts.forEach { installed[it.id] = it.id in ids }
            fonts.filter { it.id in ids }.forEach { loadPreview(it) }
        }
    }

    fun toggle(filter: FontFilter) {
        filters = if (filter in filters) filters - filter else filters + filter
    }

    fun clearFilters() {
        filters = emptySet()
    }

    val visible: List<CatalogFont>
        get() = fonts.filter { f ->
            (FontFilter.KOREAN !in filters || f.korean) &&
                (FontFilter.LATIN !in filters || f.latin) &&
                (FontFilter.SINGLE_LINE !in filters || f.kind == CatalogFont.Kind.SINGLE_LINE_SVG) &&
                (FontFilter.OUTLINE !in filters || f.kind == CatalogFont.Kind.OUTLINE_TTF)
        }

    fun download(font: CatalogFont, checksumMessage: String, failedMessage: (String) -> String) {
        if (font.id in progress) return
        progress[font.id] = 0f
        viewModelScope.launch {
            try {
                var shown = 0f
                repo.download(font) { p ->
                    // Called on the IO thread for every chunk; publish whole percents only.
                    if (p - shown >= 0.01f) {
                        shown = p
                        progress[font.id] = p
                    }
                }
                installed[font.id] = true
                loadPreview(font)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChecksumMismatchException) {
                message = checksumMessage
            } catch (e: Exception) {
                message = failedMessage(e.message ?: e.javaClass.simpleName)
            } finally {
                progress.remove(font.id)
            }
        }
    }

    fun delete(font: CatalogFont) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(font) }
            installed[font.id] = false
            previews.remove(font.id)
        }
    }

    suspend fun licenseText(font: CatalogFont): String? = withContext(Dispatchers.IO) { repo.licenseText(font) }

    private suspend fun loadPreview(font: CatalogFont) {
        val preview = withContext(Dispatchers.IO) {
            when (font.kind) {
                CatalogFont.Kind.OUTLINE_TTF -> repo.typeface(font)?.let { FontPreview.Outline(it) }
                CatalogFont.Kind.SINGLE_LINE_SVG -> repo.svgFont(font)?.let { FontPreview.Strokes(it.layout(font.name), it.unitsPerEm) }
            }
        }
        if (preview != null) previews[font.id] = preview
    }
}

/**
 * Catalog of downloadable open-source fonts. [onPick] receives the catalog id of an installed
 * font chosen for lettering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontLibraryScreen(onBack: () -> Unit, onPick: (fontId: String) -> Unit) {
    val vm: FontLibraryViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    var licenseFor by remember { mutableStateOf<CatalogFont?>(null) }
    val checksumMessage = stringResource(R.string.fonts_checksum_failed)
    val failedTemplate = stringResource(R.string.fonts_download_failed)

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    Scaffold(
        containerColor = Linen,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fonts_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.fonts_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Linen, titleContentColor = Ink, navigationIconContentColor = Ink),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FontChip(stringResource(R.string.fonts_filter_all), vm.filters.isEmpty()) { vm.clearFilters() }
                FontChip(stringResource(R.string.fonts_filter_korean), FontFilter.KOREAN in vm.filters) { vm.toggle(FontFilter.KOREAN) }
                FontChip(stringResource(R.string.fonts_filter_latin), FontFilter.LATIN in vm.filters) { vm.toggle(FontFilter.LATIN) }
                FontChip(stringResource(R.string.fonts_filter_single_line), FontFilter.SINGLE_LINE in vm.filters) { vm.toggle(FontFilter.SINGLE_LINE) }
                FontChip(stringResource(R.string.fonts_filter_outline), FontFilter.OUTLINE in vm.filters) { vm.toggle(FontFilter.OUTLINE) }
            }
            Text(
                stringResource(R.string.fonts_network_note),
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            val list = vm.visible
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.fonts_empty), color = Muted)
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.id }) { font ->
                        FontCard(
                            font = font,
                            installed = vm.installed[font.id] == true,
                            progress = vm.progress[font.id],
                            preview = vm.previews[font.id],
                            onDownload = { vm.download(font, checksumMessage) { failedTemplate.format(it) } },
                            onDelete = { vm.delete(font) },
                            onLicense = { licenseFor = font },
                            onUse = { onPick(font.id) },
                        )
                    }
                }
            }
        }
    }

    licenseFor?.let { font ->
        var text by remember(font.id) { mutableStateOf<String?>(null) }
        LaunchedEffect(font.id) { text = vm.licenseText(font) }
        val fallback = stringResource(R.string.fonts_license_not_installed, font.license, font.designer, font.licenseUrl)
        AlertDialog(
            onDismissRequest = { licenseFor = null },
            title = { Text(font.name) },
            text = {
                Text(
                    text ?: fallback,
                    fontSize = 12.sp,
                    modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { licenseFor = null }) { Text(stringResource(R.string.fonts_close)) } },
            containerColor = Linen,
        )
    }
}

@Composable
private fun FontChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White,
            labelColor = Ink,
            selectedContainerColor = Ink,
            selectedLabelColor = Linen,
        ),
    )
}

@Composable
private fun FontCard(
    font: CatalogFont,
    installed: Boolean,
    progress: Float?,
    preview: FontPreview?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onLicense: () -> Unit,
    onUse: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().background(Color.White, shape).border(1.dp, Line, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(64.dp).background(Color(0xFFFBF8F2), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val label = font.nameKo ?: font.name
            when (preview) {
                is FontPreview.Outline -> Text(
                    label,
                    fontFamily = FontFamily(preview.typeface),
                    fontSize = 30.sp,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                is FontPreview.Strokes -> StrokePreview(preview.strokes, Modifier.fillMaxSize(), preview.referenceHeight)
                null -> Text(stringResource(R.string.fonts_preview_hint), color = Muted, fontSize = 13.sp)
            }
        }
        if (font.kind == CatalogFont.Kind.SINGLE_LINE_SVG) SingleLineFontBadge()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(font.name, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val kind = stringResource(
                    if (font.kind == CatalogFont.Kind.SINGLE_LINE_SVG) R.string.fonts_kind_single_line else R.string.fonts_kind_outline,
                )
                val scripts = buildList {
                    if (font.korean) add(stringResource(R.string.fonts_filter_korean))
                    if (font.latin) add(stringResource(R.string.fonts_filter_latin))
                }.joinToString("·")
                val mb = String.format(Locale.ROOT, "%.1f", font.size / 1_048_576.0)
                Text(
                    "$kind · $scripts · ${font.license} · ${stringResource(R.string.fonts_size_mb, mb)}",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
            if (installed) {
                Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.fonts_installed), tint = Color(0xFF2F6B3A), modifier = Modifier.size(20.dp))
            }
        }
        if (progress != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = Terracotta,
                    trackColor = Line,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.fonts_downloading, (progress * 100).toInt()), color = Muted, fontSize = 12.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onLicense) { Text(stringResource(R.string.fonts_license), color = Muted) }
            Spacer(Modifier.weight(1f))
            when {
                progress != null -> Unit
                installed -> {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.fonts_delete), tint = Muted)
                    }
                    Button(onClick = onUse, colors = ButtonDefaults.buttonColors(containerColor = Terracotta)) {
                        Text(stringResource(R.string.fonts_use))
                    }
                }
                else -> OutlinedButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.fonts_download), color = Ink)
                }
            }
        }
    }
}

/** Draws single-line glyph strokes (font units, y up) scaled to fit, left-aligned. */
@Composable
private fun StrokePreview(strokes: List<Polyline>, modifier: Modifier, referenceHeight: Double) {
    val threadColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val pts = strokes.flatten()
        if (pts.isEmpty()) return@Canvas
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val w = (maxX - minX).coerceAtLeast(1.0)
        val h = (maxY - minY).coerceAtLeast(1.0)
        val scale = minOf(size.width / w, size.height / h).toFloat()
        val top = (size.height - h.toFloat() * scale) / 2
        val path = Path()
        for (stroke in strokes) {
            stroke.forEachIndexed { i, p ->
                val o = Offset(((p.x - minX) * scale).toFloat(), top + ((maxY - p.y) * scale).toFloat())
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
        }
        drawPath(path, threadColor, style = Stroke(width = ThreadRendering.widthPx((scale * referenceHeight / (18.0 * 10)).toFloat(), 1.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
