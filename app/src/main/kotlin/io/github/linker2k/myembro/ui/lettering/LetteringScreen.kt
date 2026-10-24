// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import androidx.compose.material.icons.filled.Download
import io.github.linker2k.myembro.fonts.CatalogFont
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.settings.SettingsPreferences
import io.github.linker2k.myembro.fonts.FontRepository
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.lettering.ThreadWeight
import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.engine.lettering.SatinOptions
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import kotlin.math.abs
import io.github.linker2k.myembro.ui.format.Dimensions
import io.github.linker2k.myembro.ui.ThreadRendering
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import io.github.linker2k.myembro.ui.theme.MyembroThreadColors
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Standalone lettering route. The host adds [onAdd]'s object to its editor/history and
 * closes the route. [backgroundPattern] can show the current project in the hoop.
 * Return a picked installed font through [initialFontId]; [onOpenFonts] is navigation only and gets
 * the current text, so the library can dim embroidery fonts that lack its characters.
 * Download/import UI stays with the host; every host must provide its font-library route.
 * A supplied [initialFontId] takes precedence over the object font after a library pick.
 * [initialObject] reopens an existing text object with its settings; the host keeps its id
 * and placement when it replaces the object. Omitting it is add mode.
 */
@Composable
fun LetteringScreen(
    onBack: () -> Unit,
    onAdd: (TextObject) -> Unit,
    onOpenFonts: (text: String) -> Unit,
    modifier: Modifier = Modifier,
    hoop: HoopSpec = HoopSpec.H100,
    initialText: String = "봄날",
    initialFontId: String? = null,
    backgroundPattern: EmbPattern? = null,
    initialObject: TextObject? = null,
) {
    MyembroTheme {
        LetteringContent(onBack, onAdd, modifier, hoop, initialObject?.text ?: initialText,
            initialFontId ?: initialObject?.fontId ?: "gowun-batang", backgroundPattern, onOpenFonts, initialObject)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LetteringContent(
    onBack: () -> Unit, onAdd: (TextObject) -> Unit, modifier: Modifier,
    hoop: HoopSpec, initialText: String, initialFontId: String,
    backgroundPattern: EmbPattern?, onOpenFonts: (text: String) -> Unit, initialObject: TextObject?,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var text by rememberSaveable { mutableStateOf(initialText) }
    var fontId by rememberSaveable(initialFontId) { mutableStateOf(initialFontId) }
    var height by rememberSaveable { mutableDoubleStateOf((initialObject?.heightMm ?: 18.0).coerceAtMost(LetteringHeightLimits.MAX_MM)) }
    var heightDraft by rememberSaveable { mutableStateOf((initialObject?.heightMm ?: 18.0).coerceAtMost(LetteringHeightLimits.MAX_MM).toString()) }
    var threadIndex by rememberSaveable { mutableIntStateOf(0) }
    var keepInitialThread by rememberSaveable { mutableStateOf(initialObject != null) }
    var fitText by rememberSaveable { mutableStateOf(false) }
    var automatic by remember(fontId, text) { mutableStateOf<AutomaticLetteringState?>(null) }
    var automaticHeight by remember(fontId, text) { mutableDoubleStateOf(Double.NaN) }
    var automaticLoaded by remember(fontId, text) { mutableStateOf(false) }
    var fontPreviews by remember { mutableStateOf<Map<String, EmbPattern>>(emptyMap()) }
    var catalogFonts by remember { mutableStateOf<List<CatalogFont>>(emptyList()) }
    var installedCatalog by remember { mutableStateOf<Set<String>>(emptySet()) }
    val favoritePreferences = remember { SettingsPreferences(context) }
    var favorites by remember { mutableStateOf(favoritePreferences.read().favoriteFontIds) }
    androidx.compose.runtime.DisposableEffect(favoritePreferences) {
        val stop = favoritePreferences.observe { favorites = favoritePreferences.read().favoriteFontIds }
        onDispose { stop() }
    }
    var userFontOptions by remember { mutableStateOf<List<io.github.linker2k.myembro.fonts.LetterFont>>(emptyList()) }
    var userQuickFonts by remember { mutableStateOf<List<QuickFont>>(emptyList()) }
    // Re-read after every font change: a library pick may have just downloaded it.
    LaunchedEffect(fontId, favorites) {
        val data = withContext(Dispatchers.IO) {
            val repo = FontRepository(context)
            val users = repo.userFonts().map { f ->
                val cmap = f.file?.takeIf { f.outline }?.let(io.github.linker2k.myembro.fonts.FontCmap::read)
                val svg = if (f.kind == io.github.linker2k.myembro.fonts.LetterFont.Kind.SINGLE_LINE_SVG) repo.userSvgFont(f) else null
                f to QuickFont(f.id, false, cmap?.has("가") == true, null, covers = { t ->
                    val letters = t.filterNot { it.isWhitespace() }
                    cmap?.has(letters) ?: letters.all { svg?.supports(it) == true }
                })
            }
            Triple(repo.catalog, repo.installedIds(), users)
        }
        catalogFonts = data.first; installedCatalog = data.second
        userFontOptions = data.third.map { it.first }; userQuickFonts = data.third.map { it.second }
    }
    val recentFonts = remember { RecentLetteringFonts(context) }
    var recent by remember { mutableStateOf(recentFonts.list()) }
    // First open with no history: Latin text starts in a downloaded verified embroidery font.
    val firstOpen = remember { recent.isEmpty() && initialObject == null && initialFontId == "gowun-batang" }
    LaunchedEffect(catalogFonts, installedCatalog) {
        if (!firstOpen || catalogFonts.isEmpty() || fontId != "gowun-batang") return@LaunchedEffect
        val installed = catalogFonts.filter { it.predigitized && it.id in installedCatalog }
            .map { f -> QuickFont(f.id, true, f.korean, f.themeKey, covers = f::covers) }
        fontId = defaultLetteringFont(emptyList(), text, installed)
    }
    // Every font the sheet shows becomes the most recent; the one before moves to the second slot.
    LaunchedEffect(fontId) { recentFonts.use(fontId); recent = recentFonts.list() }
    val quickFonts = remember(catalogFonts, installedCatalog, userQuickFonts) {
        bundledLetteringFonts.map { QuickFont(it.id, embroidery = false, hangul = true, theme = BUNDLED_THEMES[it.id], covers = { true }) } +
            catalogFonts.filter { it.predigitized || it.id in installedCatalog }.map { f ->
                QuickFont(f.id, f.predigitized, f.korean, f.themeKey,
                    covers = { t -> if (f.predigitized) f.covers(t) else t.none { it in '\uAC00'..'\uD7A3' } || f.korean })
            } + userQuickFonts
    }
    val slotIds = quickFontSlots(fontId, recent, quickFonts, text, favorites)
    var result by remember { mutableStateOf<LetteringResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val swatches = listOf(MyembroThreadColors.Terracotta, MyembroThreadColors.Ivory, MyembroThreadColors.Sage, MyembroThreadColors.Gold)
    val threadNames = listOf(stringResource(R.string.lettering_terracotta), stringResource(R.string.lettering_ivory),
        stringResource(R.string.lettering_sage), stringResource(R.string.lettering_gold))
    val thread = if (keepInitialThread && initialObject != null) initialObject.thread
        else EmbThread(swatches[threadIndex].toArgb(), threadNames[threadIndex])
    val hoopHeightLimit=io.github.linker2k.myembro.engine.sewout.SewoutLimits.maxDesignHeightUnits0p1mm(hoop)/10.0
    val ranges = automatic?.heightRanges.orEmpty().mapNotNull { range ->
        minOf(range.maxMm,hoopHeightLimit).takeIf { it>=range.minMm }?.let { range.copy(maxMm=it) }
    }
    LaunchedEffect(hoop) {
        val capped=height.coerceAtMost(minOf(LetteringHeightLimits.MAX_MM,hoopHeightLimit))
        if(capped!=height) { height=capped; heightDraft=capped.toString() }
    }
    val parsedHeight = heightDraft.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val heightInputValid = ranges.isNotEmpty() || parsedHeight == height
    val permittedHeight = automaticHeight == height && heightInputValid && letteringHeightPermitted(automatic, height)
    val settings = LetteringSettings(text = text, fontId = fontId, heightMm = height,
        spacingMm = 0.0, alignment = LetteringAlignment.CENTER, curved = false,
        curve = LetteringCurve.STRAIGHT, curvature = 0.0, lineSpacingMm = 0.0,
        characterColors = emptyMap(), stitchStyle = automatic?.style ?: StitchStyle.RUNNING,
        satinOptions = SatinOptions(parameters = SatinParameters(), autoDensity = false),
        threadWeight = ThreadWeight.WT40)
    val generationKey = settings to thread.color
    var renderedKey by remember { mutableStateOf<Pair<LetteringSettings, Int>?>(null) }
    val ready = permittedHeight && !busy && renderedKey == generationKey && text.isNotBlank() && error == null &&
        result?.pattern?.bounds != null && result?.unsupported != true && result?.outsideHoop != true
    val add = {
        if (ready) result?.let { onAdd(it.textObject.copy(id = UUID.randomUUID().toString())) }
        Unit
    }
    val initialDraft = remember { LetteringDraftSnapshot(initialText, initialFontId, heightDraft, thread.color) }
    val draftChanged = LetteringDraftSnapshot(text, fontId, heightDraft, thread.color) != initialDraft
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestBack = { if (draftChanged) confirmDiscard = true else onBack() }
    BackHandler(onBack = requestBack)
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.lettering_discard_title)) },
        text = { Text(stringResource(R.string.lettering_discard_body)) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) {
            Text(stringResource(R.string.editor_discard)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) {
            Text(stringResource(R.string.editor_cancel)) } })
    LaunchedEffect(fontId, text, height) {
        try {
            automatic = automaticLetteringState(context, fontId, text, height)
            automaticHeight = height
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { automatic = null
        } finally { automaticLoaded = true }
    }
    LaunchedEffect(automatic, height) {
        if (ranges.isNotEmpty()) {
            height = ranges.minBy { abs(it.clamp(height) - height) }.clamp(height)
            heightDraft = height.toString()
        }
    }
    LaunchedEffect(generationKey, automatic, automaticHeight, heightInputValid, hoop) {
        error = null
        result = null
        if (text.isBlank() || !permittedHeight) {
            busy = false
            renderedKey = null
        } else {
            busy = true
            try {
                delay(100)
                result = withContext(Dispatchers.Default) { renderLettering(context, settings, thread, hoop) }
                renderedKey = generationKey
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = context.getString(R.string.lettering_failed)
            } finally { busy = false }
        }
    }
    // Cards display actual automatic stitches, without claiming a validated sewing range.
    LaunchedEffect(text, height, thread.color, fontId, slotIds) {
        fontPreviews = emptyMap()
        delay(150)
        val previews = mutableMapOf<String, EmbPattern>()
        for (id in (slotIds + fontId).distinct().filter { id -> catalogFonts.none { it.id == id && it.predigitized } }) {
            try { previews[id] = LetteringFontPreview.pattern(context, id, text, height, thread, StitchStyle.RUNNING) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* An unavailable font has an empty preview. */ }
        }
        fontPreviews = previews
    }
    val current = result.takeIf { renderedKey == generationKey && !busy && permittedHeight }
    val previewPattern = remember(current, initialObject?.transform) {
        current?.let { letteringPreviewObject(it.textObject, initialObject).placedPattern() }
    }
    Scaffold(modifier = modifier, containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.lettering_title), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(requestBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.lettering_back)) } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background))
        },
        bottomBar = {
            Surface(color = colors.background) {
                Button(onClick = add, enabled = ready,
                    modifier = Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(54.dp),
                    shape = MaterialTheme.shapes.large) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = colors.onPrimary, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (busy) R.string.lettering_preparing else R.string.lettering_add), style = MaterialTheme.typography.titleSmall)
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().height(280.dp)) {
                HoopPreview(previewPattern, backgroundPattern, hoop, fitText,
                    Modifier.fillMaxSize().semantics { contentDescription = context.getString(R.string.lettering_preview) })
                Surface(Modifier.align(Alignment.TopStart).padding(6.dp), shape = MaterialTheme.shapes.small,
                    color = colors.surface.copy(alpha = 0.9f)) {
                    Text(Dimensions.hoopSize(hoop.width / 10, hoop.height / 10), Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = { fitText = !fitText }, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = colors.surface, contentColor = colors.onSurfaceVariant)) {
                    Icon(Icons.Default.CenterFocusStrong, stringResource(R.string.lettering_fit))
                }
                if (busy) LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 40.dp))
            }
            LetteringCard {
                SectionLabel(stringResource(R.string.lettering_text))
                OutlinedTextField(value = text, onValueChange = {
                    if (it.codePointCount(0, it.length) <= 80) text = it
                }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                    placeholder = { Text(stringResource(R.string.lettering_placeholder)) },
                    trailingIcon = { Text(stringResource(R.string.lettering_character_count, text.codePointCount(0, text.length)),
                        Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) },
                    shape = MaterialTheme.shapes.medium)
            }
            LetteringCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.lettering_fonts), Modifier.weight(1f))
                    TextButton(onClick = { onOpenFonts(text) },
                        contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(stringResource(R.string.lettering_all_fonts), style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                    }
                }
                // Quick slots: current font, the one used before it, and a same-theme neighbour (QuickFontSlots).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (id in slotIds) {
                        val catalogFont = catalogFonts.firstOrNull { it.id == id }
                        val usable = quickFonts.firstOrNull { it.id == id }?.covers?.invoke(text) ?: true
                        val pick = { if (catalogFont?.predigitized == true && id !in installedCatalog) onOpenFonts(text) else fontId = id }
                        if (catalogFont?.predigitized == true) {
                            EmbroideryFontChoice(catalogFont, fontId == id, id in installedCatalog, usable, Modifier.weight(1f), pick)
                        } else {
                            val name = bundledLetteringFonts.firstOrNull { it.id == id }?.name ?: catalogFont?.let { it.nameKo ?: it.name }
                                ?: userFontOptions.firstOrNull { it.id == id }?.name ?: automatic?.fontName ?: id
                            FontChoice(LetteringFont(id, name), fontId == id, Modifier.weight(1f).alpha(if (usable) 1f else 0.38f),
                                fontPreviews[id]) { if (usable) pick() }
                        }
                    }
                }
                Text(stringResource(when (automatic?.style) {
                    StitchStyle.RUNNING -> R.string.lettering_automatic_running
                    StitchStyle.SATIN -> R.string.lettering_automatic_satin
                    StitchStyle.FILL -> R.string.lettering_automatic_fill
                    else -> R.string.lettering_automatic_unconfirmed
                }), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            LetteringCard {
                SectionLabel(stringResource(R.string.lettering_height))
                if (ranges.isNotEmpty() && automatic?.style in listOf(StitchStyle.RUNNING, StitchStyle.SATIN, StitchStyle.FILL)) {
                    val minimum = ranges.minOf { it.minMm }
                    val maximum = ranges.maxOf { it.maxMm }
                    Text(Dimensions.length(height), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(if(automatic?.style==StitchStyle.FILL) R.string.lettering_fill_size_range else R.string.lettering_validated_range, ranges.joinToString(" · ") {
                        if (it.minMm == it.maxMm) Dimensions.length(it.minMm)
                        else "${Dimensions.length(it.minMm)} – ${Dimensions.length(it.maxMm)}"
                    }), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    if (minimum < maximum) Slider(height.toFloat(), onValueChange = { value ->
                        val proposed = value.toDouble()
                        height = ranges.minBy { abs(it.clamp(proposed) - proposed) }.clamp(proposed)
                    }, valueRange = minimum.toFloat()..maximum.toFloat(), modifier = Modifier.semantics {
                        contentDescription = context.getString(R.string.lettering_height)
                        stateDescription = Dimensions.length(height)
                    })
                } else if (ranges.isEmpty() && (automatic?.style == StitchStyle.RUNNING || automatic?.centerlineSatin==true)) {
                    Text(stringResource(if(automatic?.centerlineSatin==true) R.string.lettering_component_satin_note else R.string.lettering_satin_unconfirmed_running),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    OutlinedTextField(heightDraft, onValueChange = { value ->
                        heightDraft = value
                        value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let {
                            height = it.coerceAtMost(minOf(LetteringHeightLimits.MAX_MM,hoopHeightLimit))
                            if (it > minOf(LetteringHeightLimits.MAX_MM,hoopHeightLimit)) heightDraft = height.toString()
                        }
                    }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.lettering_height)) }, suffix = { Text("mm") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = parsedHeight == null,
                        supportingText = {
                            Text(if (parsedHeight == null) stringResource(R.string.lettering_height_invalid)
                                else Dimensions.length(parsedHeight))
                        })
                } else Text(stringResource(if (automaticLoaded) R.string.lettering_range_unconfirmed else R.string.lettering_checking_range),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            LetteringCard {
                SectionLabel(stringResource(R.string.lettering_thread))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    swatches.forEachIndexed { index, color ->
                        ThreadChip(color, threadNames[index], thread.color == color.toArgb()) {
                            threadIndex = index
                            keepInitialThread = false
                        }
                    }
                }
            }
            val status = when {
                error != null -> error
                current?.unsupported == true -> stringResource(R.string.lettering_unsupported)
                current?.outsideHoop == true -> stringResource(R.string.lettering_outside)
                else -> null
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
            current?.let { Text(stringResource(R.string.lettering_stats, it.pattern.stitchCount, it.pattern.jumpCount),
                Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun LetteringCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun FontChoice(font: LetteringFont, selected: Boolean, modifier: Modifier, preview: EmbPattern?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Column(modifier.height(106.dp).clip(shape).background(if (selected) colors.primaryContainer.copy(alpha = 0.45f) else colors.surface, shape)
        .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
        .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(9.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(font.name, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null,
                Modifier.size(16.dp), tint = if (selected) colors.primary else colors.outline)
        }
        StitchPatternPreview(preview, Modifier.fillMaxWidth().height(58.dp))
    }
}

/** Theme of each bundled TTF, for the same-theme quick slot. */
private val BUNDLED_THEMES = mapOf("gowun-batang" to "serif", "jua" to "display", "noto-sans-kr" to "sans")

/** A verified embroidery font: its bundled stitch picture; not yet downloaded opens the library, missing glyphs dim it. */
@Composable
private fun EmbroideryFontChoice(font: CatalogFont, selected: Boolean, installed: Boolean, usable: Boolean,
    modifier: Modifier = Modifier.width(132.dp), onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val context = LocalContext.current
    val image = remember(font.id) {
        runCatching { context.assets.open("fonts/previews/${font.id}.png").use(android.graphics.BitmapFactory::decodeStream)?.asImageBitmap() }.getOrNull()
    }
    Column(modifier.height(106.dp).alpha(if (usable) 1f else 0.38f).clip(shape)
        .background(if (selected) colors.primaryContainer.copy(alpha = 0.45f) else colors.surface, shape)
        .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
        .selectable(selected, enabled = usable, role = Role.RadioButton, onClick = onClick).padding(9.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(font.name, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (installed) (if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked) else Icons.Default.Download,
                null, Modifier.size(16.dp), tint = if (selected) colors.primary else colors.outline)
        }
        Box(Modifier.fillMaxWidth().height(58.dp).background(androidx.compose.ui.graphics.Color(0xFFF7F4EE), MaterialTheme.shapes.extraSmall)) {
            image?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize().padding(4.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
        }
    }
}

/** Shared description badge for catalog cards and the currently selected single-line font. */
@Composable
fun SingleLineFontBadge(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = MaterialTheme.shapes.small, color = colors.secondaryContainer) {
        Text(stringResource(R.string.lettering_single_line_badge), Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, unit: String, showInches: Boolean = false, onChange: (Float) -> Unit) {
    val formatted = if (showInches) Dimensions.length(value.toDouble()) else if (unit == "%") "${value.toInt()}%" else if (step < 0.1f) String.format(Locale.getDefault(), "%.2f %s", value, unit) else "${decimal(value.toDouble())} $unit"
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(formatted, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((value - step).coerceIn(range)) }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Remove, "$label −", Modifier.size(18.dp))
            }
            val colors = MaterialTheme.colorScheme
            Slider(value, onValueChange = onChange, valueRange = range,
                thumb = {
                    Box(Modifier.size(24.dp).shadow(2.dp, CircleShape).background(colors.surface, CircleShape)
                        .padding(3.dp).background(colors.primary, CircleShape))
                },
                track = { state ->
                    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                        val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                        drawLine(colors.outlineVariant, Offset(0f, center.y), Offset(size.width, center.y), 4.dp.toPx(), StrokeCap.Round)
                        drawLine(colors.primary, Offset(0f, center.y), Offset(size.width * fraction, center.y), 4.dp.toPx(), StrokeCap.Round)
                    }
                },
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = label
                    stateDescription = formatted
                })
            IconButton(onClick = { onChange((value + step).coerceIn(range)) }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Add, "$label +", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ThreadChip(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.widthIn(min = 58.dp).selectable(selected, role = Role.RadioButton, onClick = onClick)
        .padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.size(44.dp)) {
            drawCircle(colors.surface, radius = size.minDimension / 2)
            drawCircle(if (selected) colors.primary else colors.outlineVariant, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(if (selected) 2.dp.toPx() else 1.dp.toPx()))
            val radius = size.minDimension / 2 - 5.dp.toPx()
            drawCircle(color, radius)
            val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center - Offset(radius, radius), Size(radius * 2, radius * 2))) }
            clipPath(clip) {
                for (i in -8..16) {
                    val x = i * 5.dp.toPx()
                    drawLine(colors.onSurface.copy(alpha = 0.16f), Offset(x, 0f), Offset(x - size.width, size.height), 0.6.dp.toPx())
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

/** Theme-token fabric and hoop; every sewn line comes from the actual output pattern. */
@Composable
private fun HoopPreview(pattern: EmbPattern?, background: EmbPattern?, hoop: HoopSpec, fitText: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val viewport = letteringViewport(minOf(size.width, size.height) * 0.82f, pattern, hoop, fitText)
        val scale = viewport.scale
        fun point(x: Double, y: Double) = center + Offset(((x - viewport.centerX) * scale).toFloat(), ((y - viewport.centerY) * scale).toFloat())
        val topLeft = point(-hoop.width / 2.0, -hoop.height / 2.0)
        val hoopSize = Size(hoop.width * scale, hoop.height * scale)
        val corner = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
        drawRoundRect(colors.surface, topLeft, hoopSize, cornerRadius = corner)
        val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(
            androidx.compose.ui.geometry.Rect(topLeft, hoopSize), corner)) }
        clipPath(clip) {
            for (x in (-hoop.width / 2)..(hoop.width / 2) step 100) {
                drawLine(colors.outline.copy(alpha = 0.35f), point(x.toDouble(), -hoop.height / 2.0), point(x.toDouble(), hoop.height / 2.0))
            }
            for (y in (-hoop.height / 2)..(hoop.height / 2) step 100) {
                drawLine(colors.outline.copy(alpha = 0.35f), point(-hoop.width / 2.0, y.toDouble()), point(hoop.width / 2.0, y.toDouble()))
            }
            fun drawPattern(p: EmbPattern, color: Color?) {
                var previous = p.stitches.firstOrNull() ?: return
                var colorIndex = 0
                for (s in p.stitches.drop(1)) {
                    if (s.command == Command.COLOR_CHANGE) colorIndex++
                    if (s.command == Command.STITCH && previous.command == Command.STITCH) {
                        drawLine(color ?: Color(p.threadAt(colorIndex).color), point(previous.x.toDouble(), previous.y.toDouble()),
                            point(s.x.toDouble(), s.y.toDouble()), ThreadRendering.widthPx(scale, 1.dp.toPx()), StrokeCap.Round)
                    }
                    previous = s
                }
            }
            background?.let { drawPattern(it, colors.secondary.copy(alpha = 0.55f)) }
            pattern?.let { drawPattern(it, null) }

        }
        drawRoundRect(colors.outline, topLeft, hoopSize, cornerRadius = corner, style = Stroke(5.dp.toPx()))

    }
}

private fun decimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

@Preview(showBackground = true, widthDp = 411, heightDp = 1000)
@Composable
private fun LetteringLightPreview() { MyembroTheme(darkTheme = false) { LetteringScreen({}, {}, onOpenFonts = { _ -> }) } }

@Preview(showBackground = true, widthDp = 411, heightDp = 1000)
@Composable
private fun LetteringDarkPreview() { MyembroTheme(darkTheme = true) { LetteringScreen({}, {}, onOpenFonts = { _ -> }) } }
