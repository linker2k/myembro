// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.fonts

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.Image
import android.graphics.BitmapFactory
import android.app.Application
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.mutableStateListOf
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
import io.github.linker2k.myembro.ui.lettering.SingleLineFontBadge
import io.github.linker2k.myembro.fonts.CatalogFont
import io.github.linker2k.myembro.fonts.inkStitchPreview
import io.github.linker2k.myembro.fonts.ChecksumMismatchException
import io.github.linker2k.myembro.fonts.FontQualitySummary
import io.github.linker2k.myembro.fonts.FontRepository
import io.github.linker2k.myembro.fonts.LetterFont
import io.github.linker2k.myembro.fonts.LetterFontGeometry
import io.github.linker2k.myembro.fonts.QualityReasons
import io.github.linker2k.myembro.fonts.UserFontException
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


enum class FontFilter { KOREAN, LATIN, SCRIPT, SINGLE_LINE, OUTLINE }

/** What a card can preview once its font is installed. */
sealed interface FontPreview {
    data class Outline(val typeface: Typeface) : FontPreview
    data class Strokes(val strokes: List<List<Pair<Double, Double>>>) : FontPreview
}

class FontLibraryViewModel(app: Application) : AndroidViewModel(app) {
    val repo = FontRepository(app)
    private val geometry = LetterFontGeometry(app)
    val fonts: List<CatalogFont> = repo.catalog
    /** Stored or precomputed quality of installed catalog fonts, by catalog id. */
    val qualities = mutableStateMapOf<String, FontQualitySummary>()
    val userFonts = mutableStateListOf<LetterFont>()
    var importing by mutableStateOf(false)
        private set
    /** The font just added by the user; the screen shows its quality report, then clears it. */
    var imported by mutableStateOf<LetterFont?>(null)

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
            // The catalog's precomputed numbers show before download; installed fonts replace them below.
            fonts.forEach { f -> repo.precomputedQuality(f)?.let { qualities[f.id] = it } }
            fonts.filter { it.id in ids }.forEach { font ->
                loadPreview(font)
                // The full report replaces the catalog numbers; missing or outdated reports are recomputed.
                // Quality is advisory, so failures are ignored.
                withContext(Dispatchers.IO) {
                    runCatching { FontQualitySummary.read(repo.fontFile(font)) ?: repo.analyzeInstalled(font, geometry) }.getOrNull()
                }?.let { qualities[font.id] = it }
            }
            userFonts += withContext(Dispatchers.IO) { repo.userFonts() }
            userFonts.forEach { loadUserPreview(it) }
        }
    }

    val visibleUserFonts: List<LetterFont>
        get() = userFonts.filter { f ->
            val reasons = f.quality?.reasons.orEmpty()
            (FontFilter.KOREAN !in filters || QualityReasons.MISSING_HANGUL !in reasons) &&
                (FontFilter.LATIN !in filters || QualityReasons.MISSING_LATIN !in reasons) &&
                FontFilter.SCRIPT !in filters &&
                (FontFilter.SINGLE_LINE !in filters || f.kind == LetterFont.Kind.SINGLE_LINE_SVG) &&
                (FontFilter.OUTLINE !in filters || f.kind == LetterFont.Kind.OUTLINE)
        }

    /** Copies, validates and analyzes a user-picked font; [failed] formats the refusal reason. */
    fun importUserFont(uri: Uri, failed: (UserFontException.Reason?, String) -> String) {
        if (importing) return
        importing = true
        viewModelScope.launch {
            try {
                val font = repo.importUserFont(uri, geometry)
                userFonts += font
                loadUserPreview(font)
                imported = font
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserFontException) {
                message = failed(e.reason, e.message.orEmpty())
            } catch (e: Exception) {
                message = failed(null, e.message ?: e.javaClass.simpleName)
            } finally {
                importing = false
            }
        }
    }

    fun deleteUserFont(font: LetterFont) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteUserFont(font) }
            userFonts.removeAll { it.id == font.id }
            previews.remove(font.id)
        }
    }

    private suspend fun loadUserPreview(font: LetterFont) {
        val preview = withContext(Dispatchers.IO) {
            runCatching {
                when (font.kind) {
                    LetterFont.Kind.SINGLE_LINE_SVG -> FontPreview.Strokes(geometry.svgFont(font).layout(font.name).map { st -> st.map { p -> p.x to -p.y } })
                    else -> FontPreview.Outline(geometry.typeface(font))
                }
            }.getOrNull()
        }
        if (preview != null) previews[font.id] = preview
    }

    fun toggle(filter: FontFilter) {
        filters = if (filter in filters) filters - filter else filters + filter
    }

    fun clearFilters() {
        filters = emptySet()
    }

    val visible: List<CatalogFont>
        get() = fonts.filter { f ->
            !f.predigitized &&
            (FontFilter.KOREAN !in filters || f.korean) &&
                (FontFilter.LATIN !in filters || f.latin) &&
                (FontFilter.SCRIPT !in filters || f.script) &&
                (FontFilter.SINGLE_LINE !in filters || f.kind == CatalogFont.Kind.SINGLE_LINE_SVG) &&
                (FontFilter.OUTLINE !in filters || f.kind == CatalogFont.Kind.OUTLINE_TTF)
        }

    /** US-3: verified embroidery fonts lead the list; they are Latin-only, so script filters other than Latin hide them. */
    val embroidery: List<CatalogFont>
        get() = if ((filters - FontFilter.LATIN).isEmpty()) fonts.filter { it.predigitized } else emptyList()

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
                // Quality is advisory: a failed analysis must not undo a verified download.
                withContext(Dispatchers.IO) {
                    runCatching { FontQualitySummary.read(repo.fontFile(font)) ?: repo.analyzeInstalled(font, geometry) }.getOrNull()
                }?.let { qualities[font.id] = it }
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
            qualities.remove(font.id)
            repo.precomputedQuality(font)?.let { qualities[font.id] = it }
        }
    }

    suspend fun licenseText(font: CatalogFont): String? = withContext(Dispatchers.IO) { repo.licenseText(font) }

    private suspend fun loadPreview(font: CatalogFont) {
        val preview = withContext(Dispatchers.IO) {
            when (font.kind) {
                CatalogFont.Kind.OUTLINE_TTF -> repo.typeface(font)?.let { FontPreview.Outline(it) }
                CatalogFont.Kind.SINGLE_LINE_SVG -> repo.svgFont(font)?.let { FontPreview.Strokes(it.layout(font.name).map { st -> st.map { p -> p.x to -p.y } }) }
                CatalogFont.Kind.INKSTITCH_SVG -> runCatching { repo.inkStitchFont(font)?.let { FontPreview.Strokes(inkStitchPreview(it, font.name)) } }.getOrNull()
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
fun FontLibraryScreen(onBack: () -> Unit, onPick: (fontId: String) -> Unit, text: String = "") = MyembroTheme {
    val vm: FontLibraryViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    var licenseFor by remember { mutableStateOf<CatalogFont?>(null) }
    var askUserLicense by remember { mutableStateOf(false) }
    val checksumMessage = stringResource(R.string.fonts_checksum_failed)
    val failedTemplate = stringResource(R.string.fonts_download_failed)
    val importFailed = stringResource(R.string.fonts_import_failed)
    val reasonLabels = mapOf(
        UserFontException.Reason.TOO_LARGE to stringResource(R.string.fonts_error_too_large),
        UserFontException.Reason.UNSUPPORTED_TYPE to stringResource(R.string.fonts_error_type),
        UserFontException.Reason.UNREADABLE to stringResource(R.string.fonts_error_unreadable),
        UserFontException.Reason.NO_GLYPHS to stringResource(R.string.fonts_error_no_glyphs),
    )
    // Font MIME types are inconsistent across providers; the repository sniffs the content instead.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importUserFont(it) { reason, detail -> importFailed.format(reason?.let(reasonLabels::get) ?: detail) } }
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fonts_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.fonts_back))
                    }
                },
                actions = {
                    TextButton(onClick = { askUserLicense = true }, enabled = !vm.importing) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.fonts_add_own), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, titleContentColor = MaterialTheme.colorScheme.onSurface, navigationIconContentColor = MaterialTheme.colorScheme.onSurface),
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
                FontChip(stringResource(R.string.fonts_filter_script), FontFilter.SCRIPT in vm.filters) { vm.toggle(FontFilter.SCRIPT) }
                FontChip(stringResource(R.string.fonts_filter_single_line), FontFilter.SINGLE_LINE in vm.filters) { vm.toggle(FontFilter.SINGLE_LINE) }
                FontChip(stringResource(R.string.fonts_filter_outline), FontFilter.OUTLINE in vm.filters) { vm.toggle(FontFilter.OUTLINE) }
            }
            Text(
                stringResource(R.string.fonts_network_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            if (vm.importing) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.fonts_importing), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            val list = vm.visible
            val userList = vm.visibleUserFonts
            val embroidery = vm.embroidery
            if (list.isEmpty() && userList.isEmpty() && embroidery.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.fonts_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (embroidery.isNotEmpty()) {
                        item(key = "embroidery-header") { SectionTitle(stringResource(R.string.fonts_verified_embroidery)) }
                        items(embroidery, key = { it.id }) { font ->
                            EmbroideryFontCard(
                                font = font,
                                usable = font.covers(text),
                                installed = vm.installed[font.id] == true,
                                progress = vm.progress[font.id],
                                onDownload = { vm.download(font, checksumMessage) { failedTemplate.format(it) } },
                                onDelete = { vm.delete(font) },
                                onLicense = { licenseFor = font },
                                onUse = { onPick(font.id) },
                            )
                        }
                        if (userList.isEmpty() && list.isNotEmpty()) item(key = "other-header") { SectionTitle(stringResource(R.string.fonts_catalog)) }
                    }
                    if (userList.isNotEmpty()) {
                        item(key = "user-header") { SectionTitle(stringResource(R.string.fonts_my_fonts)) }
                        items(userList, key = { it.id }) { font ->
                            UserFontCard(
                                font = font,
                                preview = vm.previews[font.id],
                                onDelete = { vm.deleteUserFont(font) },
                                onUse = { onPick(font.id) },
                            )
                        }
                        item(key = "catalog-header") { SectionTitle(stringResource(R.string.fonts_catalog)) }
                    }
                    items(list, key = { it.id }) { font ->
                        FontCard(
                            font = font,
                            installed = vm.installed[font.id] == true,
                            progress = vm.progress[font.id],
                            preview = vm.previews[font.id],
                            quality = vm.qualities[font.id],
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
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { licenseFor = null }) { Text(stringResource(R.string.fonts_close)) } },
            containerColor = MaterialTheme.colorScheme.background,
        )
    }

    if (askUserLicense) {
        AlertDialog(
            onDismissRequest = { askUserLicense = false },
            title = { Text(stringResource(R.string.fonts_user_license_title)) },
            text = { Text(stringResource(R.string.fonts_user_license_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askUserLicense = false
                    picker.launch(arrayOf("*/*"))
                }) { Text(stringResource(R.string.fonts_user_license_confirm)) }
            },
            dismissButton = { TextButton(onClick = { askUserLicense = false }) { Text(stringResource(R.string.fonts_cancel)) } },
            containerColor = MaterialTheme.colorScheme.background,
        )
    }

    vm.imported?.let { font ->
        AlertDialog(
            onDismissRequest = { vm.imported = null },
            title = { Text(stringResource(R.string.fonts_import_done_title, font.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    font.quality?.let { q ->
                        QualityBadge(q.grade)
                        QualityDetails(q)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.imported = null }) { Text(stringResource(R.string.fonts_close)) } },
            containerColor = MaterialTheme.colorScheme.background,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun UserFontCard(font: LetterFont, preview: FontPreview?, onDelete: () -> Unit, onUse: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewBox(font.name, preview)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(font.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            font.quality?.let { QualityBadge(it.grade) }
        }
        val kind = stringResource(if (font.kind == LetterFont.Kind.SINGLE_LINE_SVG) R.string.fonts_kind_single_line else R.string.fonts_kind_outline)
        Text(kind, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        font.quality?.let { QualityDetails(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.fonts_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onUse, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Text(stringResource(R.string.fonts_use))
            }
        }
    }
}

@Composable
private fun PreviewBox(label: String, preview: FontPreview?) {
    Box(
        Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when (preview) {
            is FontPreview.Outline -> Text(
                label,
                fontFamily = FontFamily(preview.typeface),
                fontSize = 30.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            is FontPreview.Strokes -> StrokeText(preview.strokes, Modifier.fillMaxSize())
            null -> Text(stringResource(R.string.fonts_preview_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Recommended heights above this mark hairline scripts that only work as large lettering. */
private const val LARGE_ONLY_MM = 25.0

@Composable
private fun NoteBadge(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.extraSmall).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun FontChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun FontCard(
    font: CatalogFont,
    installed: Boolean,
    progress: Float?,
    preview: FontPreview?,
    quality: FontQualitySummary?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onLicense: () -> Unit,
    onUse: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewBox(font.nameKo ?: font.name, preview)
        if (font.kind == CatalogFont.Kind.SINGLE_LINE_SVG) SingleLineFontBadge()
        // Hairline outlines only; a single-line font's tight spots are gaps, not thin strokes.
        val largeOnly = font.kind == CatalogFont.Kind.OUTLINE_TTF &&
            quality?.recommendedMinHeightMm?.let { it.isFinite() && it > LARGE_ONLY_MM } == true
        if (largeOnly || font.thinStroke || font.thinThreadRequired) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (largeOnly) NoteBadge(stringResource(R.string.fonts_large_only))
                if (font.thinStroke) NoteBadge(stringResource(R.string.fonts_thin_stroke))
                if (font.thinThreadRequired) NoteBadge(stringResource(R.string.fonts_thin_thread))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(font.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                val kind = stringResource(when (font.kind) {
                    CatalogFont.Kind.SINGLE_LINE_SVG -> R.string.fonts_kind_single_line
                    CatalogFont.Kind.OUTLINE_TTF -> R.string.fonts_kind_outline
                    CatalogFont.Kind.INKSTITCH_SVG -> R.string.fonts_kind_predigitized
                })
                val scripts = buildList {
                    if (font.korean) add(stringResource(R.string.fonts_filter_korean))
                    if (font.latin) add(stringResource(R.string.fonts_filter_latin))
                }.joinToString("·")
                val size = if (font.size < 1_048_576) stringResource(R.string.fonts_size_kb, ((font.size + 1023) / 1024).toInt())
                else stringResource(R.string.fonts_size_mb, String.format(Locale.ROOT, "%.1f", font.size / 1_048_576.0))
                Text(
                    "$kind · $scripts · ${font.license} · $size",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            quality?.let {
                QualityBadge(it.grade)
                Spacer(Modifier.size(6.dp))
            }
            if (installed) {
                Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.fonts_installed), tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
        }
        quality?.let {
            minHeightText(it.recommendedMinHeightMm)?.let { line -> Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
        if (progress != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.fonts_downloading, (progress * 100).toInt()), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onLicense) { Text(stringResource(R.string.fonts_license), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            when {
                progress != null -> Unit
                installed -> {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.fonts_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onUse, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Text(stringResource(R.string.fonts_use))
                    }
                }
                else -> OutlinedButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.fonts_download), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/**
 * US-3 card: the font's name and a picture of its real stitches (bundled, so it shows before download).
 * A font without every character of the current text is dimmed and cannot be used.
 */
@Composable
private fun EmbroideryFontCard(
    font: CatalogFont,
    usable: Boolean,
    installed: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onLicense: () -> Unit,
    onUse: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val context = LocalContext.current
    val image = remember(font.id) {
        runCatching { context.assets.open("fonts/previews/${font.id}.png").use(BitmapFactory::decodeStream)?.asImageBitmap() }.getOrNull()
    }
    Column(
        Modifier.fillMaxWidth().alpha(if (usable) 1f else 0.38f)
            .background(MaterialTheme.colorScheme.surface, shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Stitches are drawn in thread colours, so the picture sits on a light fabric tone in both themes.
        Box(Modifier.fillMaxWidth().height(64.dp).background(Color(0xFFF7F4EE), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(), alignment = Alignment.CenterStart) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(font.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onLicense) { Text(stringResource(R.string.fonts_license), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            when {
                progress != null -> Text(stringResource(R.string.fonts_downloading, (progress * 100).toInt()), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                installed -> {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.fonts_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onUse, enabled = usable) { Text(stringResource(R.string.fonts_use)) }
                }
                else -> OutlinedButton(onClick = onDownload, enabled = usable) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
