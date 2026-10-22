// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.lettering.Grade
import io.github.linker2k.myembro.engine.lettering.HersheyFont
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.fonts.FontRepository
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
 * Return a picked installed font through [initialFontId]; no font download happens here.
 * [initialObject] opens an existing editable text object with its size, spacing, curve,
 * saved contours, thread identity and placement intact. Omitting it preserves add mode.
 */
@Composable
fun LetteringScreen(
    onBack: () -> Unit,
    onAdd: (TextObject) -> Unit,
    modifier: Modifier = Modifier,
    hoop: HoopSpec = HoopSpec.H100,
    initialText: String = "봄날",
    initialFontId: String = "gowun-batang",
    backgroundPattern: EmbPattern? = null,
    onOpenFonts: (() -> Unit)? = null,
    initialObject: TextObject? = null,
) {
    MyembroTheme {
        LetteringContent(onBack, onAdd, modifier, hoop, initialText, initialFontId, backgroundPattern, onOpenFonts, initialObject)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LetteringContent(
    onBack: () -> Unit, onAdd: (TextObject) -> Unit, modifier: Modifier,
    hoop: HoopSpec, initialText: String, initialFontId: String,
    backgroundPattern: EmbPattern?, onOpenFonts: (() -> Unit)?, initialObject: TextObject?,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val originalSettings = remember(initialObject) { initialObject?.letteringSettings() }
    var curveEdited by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(initialObject?.text ?: initialText) }
    var fontId by rememberSaveable(initialFontId) { mutableStateOf(initialObject?.fontId ?: initialFontId) }
    var height by rememberSaveable { mutableFloatStateOf(initialObject?.heightMm?.toFloat() ?: 18f) }
    var spacing by rememberSaveable { mutableFloatStateOf(initialObject?.spacing?.toFloat() ?: 1.2f) }
    var alignmentIndex by rememberSaveable { mutableIntStateOf(1) }
    var curved by rememberSaveable { mutableStateOf(originalSettings?.curved ?: false) }
    var curveIndex by rememberSaveable { mutableIntStateOf(originalSettings?.curve?.ordinal ?: 1) }
    var curvature by rememberSaveable { mutableFloatStateOf(originalSettings?.curvature?.toFloat() ?: 35f) }
    var threadIndex by rememberSaveable { mutableIntStateOf(if (initialObject == null) 0 else -1) }
    var fitText by rememberSaveable { mutableStateOf(false) }
    var showFonts by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LetteringResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    val alignment = LetteringAlignment.entries[alignmentIndex]
    val settings = LetteringSettings(text, fontId,
        initialObject?.heightMm?.takeIf { it.toFloat() == height } ?: height.toDouble(),
        initialObject?.spacing?.takeIf { it.toFloat() == spacing } ?: spacing.toDouble(), alignment,
        curved, LetteringCurve.entries[curveIndex], curvature.toDouble(), originalSettings?.arcDirection ?: 1.0,
        originalSettings?.arcOverride?.takeIf { !curveEdited })
    val swatches = listOf(MyembroThreadColors.Terracotta, MyembroThreadColors.Ivory, MyembroThreadColors.Sage, MyembroThreadColors.Gold)
    val threadNames = listOf(stringResource(R.string.lettering_terracotta), stringResource(R.string.lettering_ivory),
        stringResource(R.string.lettering_sage), stringResource(R.string.lettering_gold))
    val thread = if (threadIndex < 0) checkNotNull(initialObject).thread else EmbThread(swatches[threadIndex].toArgb(), threadNames[threadIndex])
    val threadColor = Color(thread.color)
    // Stale results are never submitted while a newer generation is pending.
    val generationKey = settings to thread
    var renderedKey by remember { mutableStateOf<Pair<LetteringSettings, EmbThread>?>(null) }
    val ready = !busy && renderedKey == generationKey && text.isNotBlank() && error == null &&
        result?.pattern?.bounds != null && result?.unsupported != true && result?.outsideHoop != true
    val add = {
        if (ready) result?.let { onAdd(it.textObject.copy(id = initialObject?.id ?: UUID.randomUUID().toString())) }
        Unit
    }
    BackHandler(onBack = onBack)
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
                result = withContext(Dispatchers.Default) { renderLettering(context, settings, thread, hoop, initialObject) }
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
    Scaffold(
        modifier = modifier,
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.lettering_title), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.lettering_back)) } },
                actions = { Button(onClick = add, enabled = ready, contentPadding = PaddingValues(horizontal = 18.dp)) {
                    Text(stringResource(if (initialObject == null) R.string.lettering_done else R.string.editor_apply))
                } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
            )
        },
        bottomBar = {
            Surface(color = colors.background) {
                Column {
                if (renderedKey == generationKey && !busy && result?.quality?.grade != Grade.GOOD && result != null) {
                    val recommended = result!!.quality.recommendedMinHeightMm
                    val message = if (recommended.isFinite() && recommended > 0)
                        stringResource(R.string.lettering_quality_height, decimal(recommended))
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
                    Text(stringResource(if (busy) R.string.lettering_preparing else if (initialObject == null) R.string.lettering_add else R.string.editor_apply), style = MaterialTheme.typography.titleSmall)
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
                    Text("${hoop.width / 10} × ${hoop.height / 10} mm", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
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
                }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    placeholder = { Text(stringResource(R.string.lettering_placeholder)) },
                    trailingIcon = { Text(stringResource(R.string.lettering_character_count, text.codePointCount(0, text.length)),
                        Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) },
                    shape = MaterialTheme.shapes.medium)
            }
            LetteringCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.lettering_fonts), Modifier.weight(1f))
                    TextButton(onClick = { if (onOpenFonts != null) onOpenFonts() else showFonts = true },
                        contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(stringResource(R.string.lettering_all_fonts), style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bundledLetteringFonts.forEach { font ->
                        FontChoice(font, text, fontId == font.id, Modifier.weight(1f)) { fontId = font.id }
                    }
                }
                if (bundledLetteringFonts.none { it.id == fontId }) Text(fontId,
                    style = MaterialTheme.typography.labelMedium, color = colors.primary)
            }
            LetteringCard {
                SettingSlider(stringResource(R.string.lettering_height), height, minOf(5f, initialObject?.heightMm?.toFloat() ?: 5f)..maxOf(50f, initialObject?.heightMm?.toFloat() ?: 50f), 1f, "mm") { height = it }
                SettingSlider(stringResource(R.string.lettering_spacing), spacing, minOf(0f, initialObject?.spacing?.toFloat() ?: 0f)..maxOf(6f, initialObject?.spacing?.toFloat() ?: 6f), 0.1f, "mm") { spacing = it }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.lettering_align), style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(listOf(stringResource(R.string.lettering_left), stringResource(R.string.lettering_center), stringResource(R.string.lettering_right)),
                        alignmentIndex, Modifier.weight(1f), icons = listOf(Icons.AutoMirrored.Filled.FormatAlignLeft, Icons.Default.FormatAlignCenter, Icons.AutoMirrored.Filled.FormatAlignRight)) {
                        alignmentIndex = it
                    }
                }
            }
            LetteringCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.lettering_curved), Modifier.weight(1f))
                    Switch(curved, onCheckedChange = { curved = it; curveEdited = true }, modifier = Modifier.semantics {
                        contentDescription = context.getString(R.string.lettering_curved)
                    })
                }
                if (curved) {
                    ChoiceRow(listOf(stringResource(R.string.lettering_straight), stringResource(R.string.lettering_arch), stringResource(R.string.lettering_circle)),
                        curveIndex, Modifier.fillMaxWidth()) { curveIndex = it; curveEdited = true }
                    if (curveIndex != 0) SettingSlider(stringResource(R.string.lettering_curvature), curvature, 0f..100f, 5f, "%") { curvature = it; curveEdited = true }
                }
            }
            LetteringCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.lettering_thread), Modifier.weight(1f))
                    Text(stringResource(R.string.lettering_thread_count), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    swatches.forEachIndexed { index, color ->
                        ThreadChip(color, threadNames[index], threadIndex == index) { threadIndex = index }
                    }
                }
            }
            val current = result.takeIf { renderedKey == generationKey && !busy }
            if (error != null) Notice(error ?: "", error = true)
            if (current?.unsupported == true) Notice(stringResource(R.string.lettering_unsupported), error = true)
            if (current?.outsideHoop == true) Notice(stringResource(R.string.lettering_outside), error = true)
            if (current != null) {
                Text(stringResource(R.string.lettering_stats, current.pattern.stitchCount, current.pattern.jumpCount),
                    Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    if (showFonts) {
        var fonts by remember { mutableStateOf(bundledLetteringFonts + LetteringFont(HersheyFont.DEFAULT_ID, "Hershey 단선 · ABC")) }
        LaunchedEffect(Unit) {
            val installed = withContext(Dispatchers.IO) {
                val repo = FontRepository(context)
                repo.catalog.filter(repo::isInstalled).map { LetteringFont(it.id, it.nameKo ?: it.name) }
            }
            fonts = fonts + installed
        }
        AlertDialog(onDismissRequest = { showFonts = false }, title = { Text(stringResource(R.string.lettering_fonts)) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                fonts.forEach { font -> TextButton(onClick = { fontId = font.id; showFonts = false }, modifier = Modifier.fillMaxWidth()) {
                    if (font.id == fontId) Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                    Text(font.name, Modifier.weight(1f).padding(start = 8.dp))
                } }
            } }, confirmButton = { TextButton(onClick = { showFonts = false }) { Text(stringResource(R.string.lettering_close)) } })
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
private fun FontChoice(font: LetteringFont, sample: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Column(modifier.height(104.dp).clip(shape).background(if (selected) colors.primaryContainer.copy(alpha = 0.45f) else colors.surface, shape)
        .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
        .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(9.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(font.name, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null,
                Modifier.size(16.dp), tint = if (selected) colors.primary else colors.outline)
        }
        Text(sample.lineSequence().firstOrNull()?.take(4)?.ifBlank { "봄날" } ?: "봄날",
            Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.headlineMedium,
            fontFamily = remember(font.resource) { FontFamily(Font(font.resource)) }, color = colors.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, unit: String, onChange: (Float) -> Unit) {
    val formatted = if (unit == "%") "${value.toInt()}%" else "${decimal(value.toDouble())} $unit"
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
            val fitWidth = if (fitText && b != null) (b.width * 1.4f).coerceAtLeast(200f) else hoop.width.toFloat()
            val fitHeight = if (fitText && b != null) (b.height * 1.8f).coerceAtLeast(200f) else hoop.height.toFloat()
            val scale = minOf(radius * 1.75f / fitWidth, radius * 1.75f / fitHeight)
            fun drawPattern(p: EmbPattern, color: Color?) {
                var previous = p.stitches.firstOrNull() ?: return
                for (s in p.stitches.drop(1)) {
                    if (s.command == Command.STITCH && previous.command == Command.STITCH) {
                        drawLine(color ?: Color(p.threadAt(0).color), center + Offset(previous.x * scale, previous.y * scale),
                            center + Offset(s.x * scale, s.y * scale), maxOf(1.4.dp.toPx(), 3.5f * scale), StrokeCap.Round)
                    }
                    previous = s
                }
            }
            background?.let { drawPattern(it, colors.secondary.copy(alpha = 0.55f)) }
            pattern?.let { drawPattern(it, thread) }
            if (curved && b != null) {
                drawLine(colors.secondary.copy(alpha = 0.6f), center + Offset(b.minX * scale, b.maxY * scale + 10.dp.toPx()),
                    center + Offset(b.maxX * scale, b.maxY * scale + 10.dp.toPx()), 1.dp.toPx(),
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
private fun LetteringLightPreview() { MyembroTheme(darkTheme = false) { LetteringScreen({}, {}) } }

@Preview(showBackground = true, widthDp = 411, heightDp = 1000)
@Composable
private fun LetteringDarkPreview() { MyembroTheme(darkTheme = true) { LetteringScreen({}, {}) } }
