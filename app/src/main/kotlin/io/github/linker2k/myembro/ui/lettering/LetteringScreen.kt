// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.fonts.FontRepository
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.lettering.Grade
import io.github.linker2k.myembro.engine.lettering.FontQualityReport
import io.github.linker2k.myembro.engine.lettering.ThreadWeight
import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.engine.lettering.FillOptions
import io.github.linker2k.myembro.engine.lettering.FillStrategy
import io.github.linker2k.myembro.engine.lettering.SatinOptions
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
    val initial = initialObject
    var threadWeightName by rememberSaveable { mutableStateOf((initial?.threadWeight ?: ThreadWeight.WT40).name) }
    val threadWeight = ThreadWeight.valueOf(threadWeightName)
    var height by rememberSaveable { mutableFloatStateOf(initial?.heightMm?.toFloat() ?: 18f) }
    var spacing by rememberSaveable { mutableFloatStateOf(initial?.spacing?.toFloat() ?: 1.2f) }
    var alignmentIndex by rememberSaveable { mutableIntStateOf(initial?.alignment?.ordinal ?: 1) }
    var curved by rememberSaveable { mutableStateOf(initial != null && (initial.arcDeg != 0.0 || initial.arcRadiusMm != null)) }
    var curveIndex by rememberSaveable { mutableIntStateOf(if (initial != null && initial.arcDeg < 0) 0 else 1) }
    // TextObject stores line spacing as a multiple of the height; the screen edits the gap in mm.
    var lineSpacing by rememberSaveable { mutableFloatStateOf(initial?.let { ((it.lineSpacing - 1) * it.heightMm).toFloat() } ?: 9f) }
    var radius by rememberSaveable { mutableFloatStateOf(initial?.arcRadiusMm?.toFloat() ?: 45f) }
    var selectedCharacter by rememberSaveable { mutableIntStateOf(-1) }
    var characterColors by rememberSaveable {
        mutableStateOf(HashMap(initial?.characterThreads?.mapValues { it.value.color } ?: emptyMap()))
    }
    var fontReports by remember { mutableStateOf<Map<String, FontQualityReport>>(emptyMap()) }
    var fontPreviews by remember { mutableStateOf<Map<String, EmbPattern>>(emptyMap()) }
    var stitchStyleName by rememberSaveable(fontId) {
        mutableStateOf(initial?.takeIf { it.fontId == fontId }?.stitchStyle?.name)
    }
    var fillOptions by rememberSaveable(stateSaver = FillOptionsSaver) { mutableStateOf(initial?.fillOptions ?: FillOptions()) }
    var satinOptions by rememberSaveable(stateSaver = SatinOptionsSaver) { mutableStateOf(initial?.satinOptions ?: SatinOptions()) }
    var threadIndex by rememberSaveable { mutableIntStateOf(0) }
    var fitText by rememberSaveable { mutableStateOf(false) }
    var result by remember { mutableStateOf<LetteringResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    val alignment = LetteringAlignment.entries[alignmentIndex]
    val settings = LetteringSettings(text, fontId, height.toDouble(), spacing.toDouble(), alignment,
        curved, if (curveIndex == 1) LetteringCurve.ARCH else LetteringCurve.BELOW, 35.0,
        lineSpacing.toDouble(), radius.coerceAtLeast(height).toDouble(), characterColors.toMap(), stitchStyleName?.let(StitchStyle::valueOf),
        fillOptions, satinOptions, threadWeight)
    val swatches = listOf(MyembroThreadColors.Terracotta, MyembroThreadColors.Ivory, MyembroThreadColors.Sage, MyembroThreadColors.Gold)
    val threadNames = listOf(stringResource(R.string.lettering_terracotta), stringResource(R.string.lettering_ivory),
        stringResource(R.string.lettering_sage), stringResource(R.string.lettering_gold))
    // An existing object keeps its own thread unless the user picks a swatch.
    var keepInitialThread by rememberSaveable { mutableStateOf(initial != null) }
    val thread = if (keepInitialThread && initial != null) initial.thread
        else EmbThread(swatches[threadIndex].toArgb(), threadNames[threadIndex])
    val threadColor = if (keepInitialThread && initial != null) Color(initial.thread.color) else swatches[threadIndex]
    // Stale results are never submitted while a newer generation is pending.
    val generationKey = settings to thread.color
    var renderedKey by remember { mutableStateOf<Pair<LetteringSettings, Int>?>(null) }
    val ready = !busy && renderedKey == generationKey && text.isNotBlank() && error == null &&
        result?.pattern?.bounds != null && result?.unsupported != true && result?.outsideHoop != true
    val add = {
        if (ready) result?.let { onAdd(it.textObject.copy(id = UUID.randomUUID().toString())) }
        Unit
    }
    BackHandler(onBack = onBack)
    // Embroidery fonts carry their own letter spacing (script joins would break): start from none.
    LaunchedEffect(fontId) {
        if (initial?.fontId != fontId && withContext(Dispatchers.IO) { FontRepository(context).find(fontId)?.predigitized } == true) spacing = 0f
    }
    LaunchedEffect(generationKey, hoop) {
        busy = true
        error = null
        if (text.isBlank()) {
            result = null
            renderedKey = generationKey
            busy = false
        } else {
            try {
                delay(100)
                result = withContext(Dispatchers.Default) { renderLettering(context, settings, thread, hoop) }
                renderedKey = generationKey
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = context.getString(R.string.lettering_failed)
                result = null
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(text, height, threadWeight) {
        fontReports = emptyMap()
        delay(150)
        val reports = mutableMapOf<String, FontQualityReport>()
        for (font in bundledLetteringFonts) {
            try { reports[font.id] = LetteringFontQuality.analyze(context, font.id, text.ifBlank { "자수봄날Aa012" }, height.toDouble(), threadWeight) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Unavailable cards retain the pending label. */ }
        }
        fontReports = reports
    }
    LaunchedEffect(text, height, thread.color, settings.stitchStyle, fillOptions, threadWeight) {
        fontPreviews = emptyMap()
        delay(150)
        val previews = mutableMapOf<String, EmbPattern>()
        for (font in bundledLetteringFonts) {
            try { previews[font.id] = LetteringFontPreview.pattern(context, font.id, text, height.toDouble(), thread, settings.stitchStyle, fillOptions, threadWeight) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Font loading errors remain visible in the main preview. */ }
        }
        fontPreviews = previews
    }
    Scaffold(
        modifier = modifier,
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.lettering_title), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.lettering_back)) } },
                actions = { Button(onClick = add, enabled = ready, contentPadding = PaddingValues(horizontal = 18.dp)) {
                    Text(stringResource(R.string.lettering_done))
                } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
            )
        },
        bottomBar = {
            Surface(color = colors.background) {
                Column {
                val filling = result?.singleLine == false && settings.stitchStyle in listOf(StitchStyle.FILL, StitchStyle.SATIN)
                val recommended = result?.quality?.let { if (filling) filledLetteringMinimumHeight(it, threadWeight) else it.recommendedMinHeightMm } ?: 0.0
                if (renderedKey == generationKey && !busy && result != null &&
                    (result!!.quality.grade != Grade.GOOD || filling && height < recommended)) {
                    val message = if (recommended.isFinite() && recommended > 0 && recommended <= 300.0)
                        stringResource(R.string.lettering_quality_height, Dimensions.length(recommended))
                    else stringResource(R.string.lettering_quality_check)
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Notice(message, detail = stringResource(R.string.lettering_quality_hint), error = false)
                    }
                }
                Button(onClick = add, enabled = ready,
                    modifier = Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(54.dp),
                    shape = MaterialTheme.shapes.large) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = colors.onPrimary, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (busy) R.string.lettering_preparing else R.string.lettering_add), style = MaterialTheme.typography.titleSmall)
                }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                HoopPreview(result?.pattern, backgroundPattern, hoop, fitText, threadColor, curved,
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
                    if (it.codePointCount(0, it.length) <= 80) {
                        characterColors = remapCharacterColors(text, it, characterColors)
                        selectedCharacter = -1
                        text = it
                    }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bundledLetteringFonts.forEach { font ->
                        FontChoice(font, fontId == font.id, Modifier.weight(1f), fontReports[font.id], fontPreviews[font.id]) { fontId = font.id }
                    }
                }
                if (bundledLetteringFonts.none { it.id == fontId }) {
                    Text(result?.takeIf { it.textObject.fontId == fontId }?.fontName ?: fontId,
                        style = MaterialTheme.typography.titleSmall, color = colors.primary)
                    val active = result?.takeIf { it.textObject.fontId == fontId }
                    if (active?.singleLine == true) SingleLineFontBadge()
                    StitchPatternPreview(active?.pattern, Modifier.fillMaxWidth().height(70.dp))
                    active?.let { FontQualityBadge(it.quality) }
                }
            }
            result?.takeIf { it.textObject.fontId == fontId && it.predigitized && it.missing.isNotEmpty() }?.let { active -> LetteringCard {
                Text(stringResource(R.string.lettering_missing_glyphs, active.missing),
                    style = MaterialTheme.typography.bodySmall, color = colors.error)
            } }
            result?.takeIf { it.textObject.fontId == fontId && !it.predigitized }?.let { active -> LetteringCard {
                val style = settings.stitchStyle ?: if (active.singleLine) StitchStyle.BEAN else StitchStyle.RUNNING
                ThreadWeightSelector(threadWeight) {
                    if (it != threadWeight) {
                        threadWeightName = it.name
                        fillOptions = fillOptions.copy(autoDensity = true)
                        satinOptions = satinOptions.copy(autoDensity = true)
                    }
                }
                LetteringModeSelector(active.singleLine, style, { stitchStyleName = it.name })
                if (active.singleLine && style == StitchStyle.BEAN) {
                    Text(stringResource(R.string.lettering_bean_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (!active.singleLine && style in listOf(StitchStyle.FILL, StitchStyle.SATIN)) {
                    val shownOptions = if (style == StitchStyle.SATIN) fillOptions.copy(strategy = FillStrategy.AUTO) else fillOptions
                    FillSettingsControls(shownOptions, settings.heightMm, threadWeight) {
                        if (style == StitchStyle.SATIN && it.strategy == FillStrategy.TATAMI)
                            stitchStyleName = StitchStyle.FILL.name
                        fillOptions = it
                    }
                    if (style == StitchStyle.SATIN) Text(stringResource(R.string.lettering_satin_hint),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } else if (active.singleLine && style == StitchStyle.SATIN) {
                    SatinSettingsControls(satinOptions, settings.heightMm, threadWeight) { satinOptions = it }
                }
            } }
            LetteringCard {
                // Some embroidery fonts are tested only above 50 mm.
                // US-3: an embroidery font moves only within the heights its digitizer recorded (font.json scales).
                val tested = result?.takeIf { it.textObject.fontId == fontId }?.testedRangeMm
                val range = tested?.let { it.start.toFloat()..it.endInclusive.toFloat() } ?: 5f..50f
                LaunchedEffect(range) { if (height !in range) height = height.coerceIn(range) }
                SettingSlider(stringResource(R.string.lettering_height), height.coerceIn(range), range, if (tested != null) 0.5f else 1f, "mm", showInches = true) { height = it }
                SettingSlider(stringResource(R.string.lettering_spacing), spacing, 0f..6f, 0.1f, "mm") { spacing = it }
                SettingSlider(stringResource(R.string.lettering_line_spacing), lineSpacing, 0f..30f, 0.5f, "mm") { lineSpacing = it }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.lettering_align), style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(listOf(stringResource(R.string.lettering_left), stringResource(R.string.lettering_center), stringResource(R.string.lettering_right)),
                        alignmentIndex, Modifier.weight(1f), icons = listOf(Icons.AutoMirrored.Filled.FormatAlignLeft, Icons.Default.FormatAlignCenter, Icons.AutoMirrored.Filled.FormatAlignRight)) {
                        alignmentIndex = it
                    }
                }
            }
            // Ink/Stitch fonts are laid out straight (along-path layout is a later port).
            if (result?.takeIf { it.textObject.fontId == fontId }?.predigitized != true) LetteringCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.lettering_curved), Modifier.weight(1f))
                    Switch(curved, onCheckedChange = { curved = it }, modifier = Modifier.semantics {
                        contentDescription = context.getString(R.string.lettering_curved)
                    })
                }
                if (curved) {
                    ChoiceRow(listOf(stringResource(R.string.lettering_arc_below), stringResource(R.string.lettering_arc_above)),
                        curveIndex, Modifier.fillMaxWidth()) { curveIndex = it }
                    SettingSlider(stringResource(R.string.lettering_radius), radius.coerceAtLeast(height), height.coerceAtLeast(15f)..150f, 5f, "mm") { radius = it }
                }
            }
            LetteringCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.lettering_thread), Modifier.weight(1f))
                    Text(stringResource(R.string.lettering_thread_count), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = selectedCharacter == -1, onClick = { selectedCharacter = -1 },
                        label = { Text(stringResource(R.string.lettering_all_characters)) })
                    normalizedLetteringText(text).codePoints().toArray().forEachIndexed { index, code ->
                        if (!Character.isWhitespace(code)) FilterChip(selected = selectedCharacter == index,
                            onClick = { selectedCharacter = index },
                            label = { Text("${index + 1} · ${String(Character.toChars(code))}") },
                            leadingIcon = { Box(Modifier.size(10.dp).background(Color(characterColors[index] ?: thread.color), CircleShape)) })
                    }
                }
                Text(stringResource(if (selectedCharacter < 0) R.string.lettering_apply_all else R.string.lettering_apply_character),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    swatches.forEachIndexed { index, color ->
                        val selectedColor = if (selectedCharacter < 0) thread.color else characterColors[selectedCharacter] ?: thread.color
                        ThreadChip(color, threadNames[index], selectedColor == color.toArgb()) {
                            if (selectedCharacter < 0) { threadIndex = index; keepInitialThread = false; characterColors = hashMapOf() }
                            else characterColors = HashMap(characterColors).apply { put(selectedCharacter, color.toArgb()) }
                        }
                    }
                }
            }
            val current = result.takeIf { renderedKey == generationKey && !busy }
            if (error != null) Notice(error ?: "", error = true)
            if (current?.unsupported == true) Notice(stringResource(R.string.lettering_unsupported), error = true)
            if (current?.outsideHoop == true) Notice(stringResource(R.string.lettering_outside), error = true)
            if (current?.pattern?.warnings?.isNotEmpty() == true) Notice(stringResource(R.string.lettering_satin_fallback), error = false)
            if (current != null) {
                Text(stringResource(R.string.lettering_stats, current.pattern.stitchCount, current.pattern.jumpCount),
                    Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
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
private fun FontChoice(font: LetteringFont, selected: Boolean, modifier: Modifier, quality: FontQualityReport?, preview: EmbPattern?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Column(modifier.height(140.dp).clip(shape).background(if (selected) colors.primaryContainer.copy(alpha = 0.45f) else colors.surface, shape)
        .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
        .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(9.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(font.name, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null,
                Modifier.size(16.dp), tint = if (selected) colors.primary else colors.outline)
        }
        StitchPatternPreview(preview, Modifier.fillMaxWidth().height(58.dp))
        if (quality != null) FontQualityBadge(quality) else Text(stringResource(R.string.lettering_quality_pending),
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
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

/** Reusable catalog/installed-font card badge; reports come from LetteringFontQuality or FontQualityCache. */
@Composable
fun FontQualityBadge(report: FontQualityReport, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(when (report.grade) {
        Grade.GOOD -> R.string.lettering_grade_good
        Grade.CAUTION -> R.string.lettering_grade_caution
        Grade.UNSUITABLE -> R.string.lettering_grade_unsuitable
    })
    val minimum = if (report.recommendedMinHeightMm.isFinite() && report.recommendedMinHeightMm in 0.001..300.0)
        stringResource(R.string.lettering_card_minimum, Dimensions.length(report.recommendedMinHeightMm)) else ""
    Surface(modifier, shape = MaterialTheme.shapes.extraSmall, color = when (report.grade) {
        Grade.GOOD -> colors.secondaryContainer
        Grade.CAUTION -> colors.tertiaryContainer
        Grade.UNSUITABLE -> colors.errorContainer
    }) {
        Text(listOf(label, minimum).filter { it.isNotEmpty() }.joinToString(" · "), Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = when (report.grade) {
                Grade.GOOD -> colors.onSecondaryContainer
                Grade.CAUTION -> colors.onTertiaryContainer
                Grade.UNSUITABLE -> colors.onErrorContainer
            })
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
private fun ChoiceRow(labels: List<String>, selected: Int, modifier: Modifier, icons: List<androidx.compose.ui.graphics.vector.ImageVector> = emptyList(), onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.border(1.dp, colors.outlineVariant, MaterialTheme.shapes.small)) {
        labels.forEachIndexed { index, label ->
            Row(Modifier.weight(1f).heightIn(min = 46.dp)
                .background(if (selected == index) colors.primaryContainer else colors.surface, MaterialTheme.shapes.small)
                .selectable(selected == index, role = Role.RadioButton, onClick = { onSelect(index) }).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                icons.getOrNull(index)?.let { Icon(it, null, Modifier.size(16.dp), tint = if (selected == index) colors.primary else colors.onSurfaceVariant) }
                Text(label, Modifier.padding(horizontal = 3.dp), style = MaterialTheme.typography.labelSmall,
                    color = if (selected == index) colors.primary else colors.onSurfaceVariant)
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

@Composable
private fun Notice(message: String, detail: String? = null, error: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if (error) colors.errorContainer else colors.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Info, null, Modifier.size(19.dp), tint = if (error) colors.onErrorContainer else colors.onTertiaryContainer)
            Column {
                Text(message, style = MaterialTheme.typography.labelMedium, color = if (error) colors.onErrorContainer else colors.onTertiaryContainer)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onTertiaryContainer)
            }
        }
    }
}

/** Theme-token fabric and hoop; every sewn line comes from the actual output pattern. */
@Composable
private fun HoopPreview(pattern: EmbPattern?, background: EmbPattern?, hoop: HoopSpec, fitText: Boolean, thread: Color, curved: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        val radius = minOf(size.width * 0.42f, size.height * 0.45f)
        val center = Offset(size.width / 2, size.height / 2 + 5.dp.toPx())
        val wood = lerp(colors.outline, colors.primary, 0.32f)
        drawCircle(colors.onSurface.copy(alpha = 0.05f), radius + 5.dp.toPx(), center + Offset(0f, 4.dp.toPx()))
        drawCircle(colors.surface, radius, center)
        val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center - Offset(radius, radius), Size(radius * 2, radius * 2))) }
        clipPath(clip) {
            val grid = 5.dp.toPx()
            var x = center.x - radius
            while (x < center.x + radius) { drawLine(colors.outline.copy(alpha = 0.18f), Offset(x, center.y - radius), Offset(x, center.y + radius), 0.5.dp.toPx()); x += grid }
            var y = center.y - radius
            while (y < center.y + radius) { drawLine(colors.outline.copy(alpha = 0.18f), Offset(center.x - radius, y), Offset(center.x + radius, y), 0.5.dp.toPx()); y += grid }
            val b = pattern?.bounds
            val viewport = letteringViewport(radius * 1.35f, pattern, hoop, fitText)
            val scale = viewport.scale
            fun point(x: Double, y: Double) = center + Offset(((x - viewport.centerX) * scale).toFloat(), ((y - viewport.centerY) * scale).toFloat())
            // Decorative round frame and the actual rectangular sewing area are distinct.
            drawRect(colors.outline, point(-hoop.width / 2.0, -hoop.height / 2.0),
                Size(hoop.width * scale, hoop.height * scale),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
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
            if (curved && b != null) {
                drawLine(colors.secondary.copy(alpha = 0.6f), point(b.minX.toDouble(), b.maxY.toDouble()) + Offset(0f, 10.dp.toPx()),
                    point(b.maxX.toDouble(), b.maxY.toDouble()) + Offset(0f, 10.dp.toPx()), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            }
        }
        drawCircle(wood, radius, center, style = Stroke(9.dp.toPx()))
        drawCircle(colors.surface.copy(alpha = 0.45f), radius - 3.dp.toPx(), center, style = Stroke(1.dp.toPx()))
        drawCircle(colors.onSurface.copy(alpha = 0.12f), radius + 3.dp.toPx(), center, style = Stroke(1.dp.toPx()))
        drawRoundRect(wood, center + Offset(-18.dp.toPx(), -radius - 14.dp.toPx()), Size(36.dp.toPx(), 12.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        drawLine(colors.tertiary, center + Offset(-23.dp.toPx(), -radius - 10.dp.toPx()), center + Offset(23.dp.toPx(), -radius - 10.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
    }
}

private fun decimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

@Preview(showBackground = true, widthDp = 411, heightDp = 1000)
@Composable
private fun LetteringLightPreview() { MyembroTheme(darkTheme = false) { LetteringScreen({}, {}, onOpenFonts = { _ -> }) } }

@Preview(showBackground = true, widthDp = 411, heightDp = 1000)
@Composable
private fun LetteringDarkPreview() { MyembroTheme(darkTheme = true) { LetteringScreen({}, {}, onOpenFonts = { _ -> }) } }
