// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.lettering.HersheyFont
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.fonts.FontQualitySummary
import io.github.linker2k.myembro.fonts.FontRepository
import io.github.linker2k.myembro.fonts.LetterFont
import io.github.linker2k.myembro.fonts.LetterFontGeometry
import io.github.linker2k.myembro.fonts.QualityGrade
import io.github.linker2k.myembro.ui.fonts.FontLibraryScreen
import io.github.linker2k.myembro.ui.fonts.HoopPatternPreview
import io.github.linker2k.myembro.ui.fonts.QualityBadge
import io.github.linker2k.myembro.ui.fonts.StrokeText
import io.github.linker2k.myembro.ui.fonts.mm
import io.github.linker2k.myembro.ui.fonts.reasonText
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ArcShape { STRAIGHT, ARCH, SMILE }

class LetteringViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FontRepository(app)
    private val geometry = LetterFontGeometry(app)

    var fonts by mutableStateOf<List<LetterFont>>(emptyList())
        private set
    var fontId by mutableStateOf(HersheyFont.DEFAULT_ID)
        private set
    var text by mutableStateOf("")
        private set
    var heightMm by mutableStateOf(10.0)
        private set
    var spacingMm by mutableStateOf(1.0)
        private set
    var arcShape by mutableStateOf(ArcShape.STRAIGHT)
        private set
    /** Bend amount in degrees (0–180); the sign comes from [arcShape]. */
    var curvature by mutableStateOf(60.0)
        private set
    var missing by mutableStateOf<Set<Char>>(emptySet())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    /** Live stitches for the hoop preview, regenerated shortly after each change. */
    var pattern by mutableStateOf<EmbPattern?>(null)
        private set
    /** Font-card previews of the current text, by font id. */
    val previews = mutableStateMapOf<String, List<List<Pair<Double, Double>>>>()

    /** Quality computed in this session for fonts without a stored report (built-in Jua). */
    private val computed = mutableStateMapOf<String, FontQualitySummary>()
    private var previewJob: Job? = null
    private var cardJob: Job? = null

    val font: LetterFont? get() = fonts.firstOrNull { it.id == fontId }
    val quality: FontQualitySummary? get() = font?.let { quality(it) }
    fun quality(f: LetterFont): FontQualitySummary? = f.quality ?: computed[f.id]

    /** Heights below this get the warning banner; null when the font has no report. */
    val recommendedMinHeightMm: Double? get() = quality?.recommendedMinHeightMm

    val arcDeg: Double get() = when (arcShape) {
        ArcShape.STRAIGHT -> 0.0
        ArcShape.ARCH -> curvature
        ArcShape.SMILE -> -curvature
    }

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            fonts = withContext(Dispatchers.IO) { repo.letterFonts() }
            if (font == null) fontId = HersheyFont.DEFAULT_ID
            onFontOrTextChanged()
            refreshCards()
        }
    }

    fun choose(id: String) { fontId = id; onFontOrTextChanged() }
    fun updateText(value: String) { text = value; onFontOrTextChanged(); refreshCards() }
    fun updateHeight(value: Double) { heightMm = value.coerceIn(MIN_HEIGHT, MAX_HEIGHT); schedulePreview() }
    fun updateSpacing(value: Double) { spacingMm = value.coerceIn(0.0, MAX_SPACING); schedulePreview() }
    fun updateArc(shape: ArcShape) { arcShape = shape; schedulePreview() }
    fun updateCurvature(value: Double) { curvature = value.coerceIn(0.0, 180.0); schedulePreview() }

    fun fitRecommended() {
        recommendedMinHeightMm?.let { updateHeight(ceil(it * 2) / 2) }
    }

    private fun onFontOrTextChanged() {
        val f = font ?: return
        val current = text
        schedulePreview()
        viewModelScope.launch {
            val lacking = withContext(Dispatchers.IO) { runCatching { geometry.missing(f, current) }.getOrDefault(emptySet()) }
            if (fontId == f.id && text == current) missing = lacking
            if (f.quality == null && f.kind != LetterFont.Kind.HERSHEY && f.id !in computed) {
                withContext(Dispatchers.Default) { runCatching { geometry.analyze(f) }.getOrNull() }?.let { computed[f.id] = it }
            }
        }
    }

    private fun refreshCards() {
        cardJob?.cancel()
        val sample = text.trim().lineSequence().firstOrNull().orEmpty().take(8)
        val list = fonts
        cardJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            for (f in list) {
                val strokes = withContext(Dispatchers.Default) {
                    runCatching {
                        // Show the user's text when the font can draw it, else a sample it can.
                        val candidates = listOf(sample, "가나다", "Abc").filter { it.isNotBlank() }
                        val chosen = candidates.firstOrNull { geometry.missing(f, it).isEmpty() } ?: candidates.last()
                        geometry.previewStrokes(f, chosen)
                    }.getOrDefault(emptyList())
                }
                previews[f.id] = strokes
            }
        }
    }

    private fun schedulePreview() {
        previewJob?.cancel()
        val f = font ?: return
        val params = Params(text, heightMm, spacingMm, arcDeg)
        if (params.text.isBlank()) { pattern = null; return }
        previewJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            pattern = withContext(Dispatchers.Default) {
                runCatching { build(f, "preview", params).localPattern() }.getOrNull()
            }
        }
    }

    private data class Params(val text: String, val heightMm: Double, val spacingMm: Double, val arcDeg: Double)

    private fun build(f: LetterFont, id: String, p: Params): TextObject =
        geometry.textObject(f, id, p.text, p.heightMm, p.spacingMm, p.arcDeg)

    fun add(onDone: (TextObject) -> Unit) {
        val f = font ?: return
        if (busy || text.isBlank()) return
        busy = true
        val params = Params(text, heightMm, spacingMm, arcDeg)
        viewModelScope.launch {
            try {
                val obj = withContext(Dispatchers.Default) {
                    build(f, UUID.randomUUID().toString(), params).also { it.localPattern() }
                }
                onDone(obj)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    companion object {
        const val MIN_HEIGHT = 4.0
        const val MAX_HEIGHT = 80.0
        const val MAX_SPACING = 10.0
        private const val DEBOUNCE_MS = 150L
    }
}

/**
 * Text tool for the editor (A design: hoop preview on top, then phrase, font, size and curve
 * cards). The hoop preview draws the real stitches at true scale. [onAdd] receives a ready
 * [TextObject]; the sheet closes itself after that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LetteringSheet(hoop: HoopSpec = HoopSpec.H100, onDismiss: () -> Unit, onAdd: (TextObject) -> Unit) {
    val vm: LetteringViewModel = viewModel()
    var library by remember { mutableStateOf(false) }
    var unsuitable by remember { mutableStateOf<LetterFont?>(null) }
    var pendingPick by remember { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun pick(id: String) {
        val f = vm.fonts.firstOrNull { it.id == id } ?: return
        if (vm.quality(f)?.grade == QualityGrade.UNSUITABLE && id != vm.fontId) unsuitable = f else vm.choose(id)
    }

    MyembroTheme {
        val colors = MaterialTheme.colorScheme
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = colors.background) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.lettering_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                HoopPatternPreview(
                    vm.pattern, hoop.width / 10.0, hoop.height / 10.0,
                    Modifier.fillMaxWidth().height(240.dp),
                )

                Section(stringResource(R.string.lettering_text)) {
                    OutlinedTextField(
                        value = vm.text,
                        onValueChange = vm::updateText,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        maxLines = 3,
                        supportingText = { Text(stringResource(R.string.lettering_char_count, vm.text.count { !it.isWhitespace() })) },
                    )
                    if (vm.missing.isNotEmpty()) {
                        Banner(stringResource(R.string.lettering_missing, vm.missing.joinToString(" ")), colors.tertiaryContainer, colors.onTertiaryContainer)
                    }
                }

                Section(
                    stringResource(R.string.lettering_font),
                    action = {
                        TextButton(onClick = { library = true }) {
                            Text(stringResource(R.string.lettering_more_fonts))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
                        }
                    },
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(vm.fonts, key = { it.id }) { f ->
                            FontChoiceCard(
                                font = f,
                                selected = f.id == vm.fontId,
                                strokes = vm.previews[f.id].orEmpty(),
                                grade = vm.quality(f)?.grade,
                                onClick = { pick(f.id) },
                            )
                        }
                    }
                }

                Section(stringResource(R.string.lettering_size)) {
                    SteppedSlider(
                        label = stringResource(R.string.lettering_height_label),
                        value = vm.heightMm, valueText = "${mm(vm.heightMm)} mm",
                        range = LetteringViewModel.MIN_HEIGHT..LetteringViewModel.MAX_HEIGHT, step = 0.5,
                        onChange = vm::updateHeight,
                    )
                    val recommended = vm.recommendedMinHeightMm
                    if (recommended != null && vm.heightMm < recommended) {
                        Column(
                            Modifier.fillMaxWidth().background(colors.tertiaryContainer, MaterialTheme.shapes.medium).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.WarningAmber, null, tint = colors.onTertiaryContainer, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.lettering_too_small, mm(recommended)),
                                    color = colors.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            TextButton(onClick = vm::fitRecommended, modifier = Modifier.align(Alignment.End)) {
                                Text(stringResource(R.string.lettering_fit_recommended))
                            }
                        }
                    }
                    SteppedSlider(
                        label = stringResource(R.string.lettering_spacing_label),
                        value = vm.spacingMm, valueText = "${mm(vm.spacingMm)} mm",
                        range = 0.0..LetteringViewModel.MAX_SPACING, step = 0.1,
                        onChange = vm::updateSpacing,
                    )
                }

                Section(stringResource(R.string.lettering_curve)) {
                    val shapes = listOf(
                        ArcShape.STRAIGHT to R.string.lettering_arc_straight,
                        ArcShape.ARCH to R.string.lettering_arc_arch,
                        ArcShape.SMILE to R.string.lettering_arc_smile,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        shapes.forEachIndexed { i, (shape, label) ->
                            SegmentedButton(
                                selected = vm.arcShape == shape,
                                onClick = { vm.updateArc(shape) },
                                shape = SegmentedButtonDefaults.itemShape(i, shapes.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = colors.primary, activeContentColor = colors.onPrimary,
                                    activeBorderColor = colors.primary, inactiveContainerColor = colors.surfaceVariant,
                                    inactiveBorderColor = colors.outline,
                                ),
                            ) { Text(stringResource(label)) }
                        }
                    }
                    if (vm.arcShape != ArcShape.STRAIGHT) {
                        SteppedSlider(
                            label = stringResource(R.string.lettering_curvature),
                            value = vm.curvature, valueText = "${Math.round(vm.curvature)}°",
                            range = 0.0..180.0, step = 5.0,
                            onChange = vm::updateCurvature,
                        )
                    }
                }

                vm.error?.let { Banner(stringResource(R.string.lettering_failed, it), colors.errorContainer, colors.onErrorContainer) }
                Button(
                    onClick = { vm.add { onAdd(it); onDismiss() } },
                    enabled = !vm.busy && vm.text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.lettering_add), style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (library) {
            Dialog(onDismissRequest = { library = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize()) {
                    FontLibraryScreen(
                        onBack = { library = false; vm.refresh() },
                        onPick = { id ->
                            library = false
                            vm.refresh()
                            // The list reloads asynchronously; pick once the font is known.
                            pendingPick = id
                        },
                    )
                }
            }
        }
        LaunchedEffect(vm.fonts, pendingPick) {
            pendingPick?.let { id -> if (vm.fonts.any { it.id == id }) { pendingPick = null; pick(id) } }
        }

        unsuitable?.let { f ->
            AlertDialog(
                onDismissRequest = { unsuitable = null },
                title = { Text(stringResource(R.string.lettering_unsuitable_title)) },
                text = {
                    Text(stringResource(R.string.lettering_unsuitable_body, f.name, vm.quality(f)?.reasons.orEmpty().map { reasonText(it) }.joinToString(" ")))
                },
                confirmButton = { TextButton(onClick = { vm.choose(f.id); unsuitable = null }) { Text(stringResource(R.string.lettering_use_anyway)) } },
                dismissButton = { TextButton(onClick = { unsuitable = null }) { Text(stringResource(R.string.fonts_cancel)) } },
            )
        }
    }
}

@Composable
private fun Section(title: String, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun FontChoiceCard(
    font: LetterFont, selected: Boolean, strokes: List<List<Pair<Double, Double>>>, grade: QualityGrade?, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (selected) colors.primaryContainer else colors.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline),
        modifier = Modifier.width(148.dp).height(112.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    font.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurface, modifier = Modifier.weight(1f),
                )
                RadioButton(selected = selected, onClick = null, modifier = Modifier.size(20.dp))
            }
            StrokeText(strokes, Modifier.fillMaxWidth().weight(1f))
            grade?.let { QualityBadge(it) }
        }
    }
}

@Composable
private fun SteppedSlider(
    label: String, value: Double, valueText: String, range: ClosedFloatingPointRange<Double>, step: Double, onChange: (Double) -> Unit,
) {
    fun snap(v: Double) = (Math.round(v / step) * step).coerceIn(range.start, range.endInclusive)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(snap(value - step)) }, enabled = value > range.start + abs(step) / 2) {
                Icon(Icons.Filled.Remove, stringResource(R.string.lettering_decrease, label))
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(snap(it.toDouble())) },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange(snap(value + step)) }, enabled = value < range.endInclusive - abs(step) / 2) {
                Icon(Icons.Filled.Add, stringResource(R.string.lettering_increase, label))
            }
        }
    }
}

@Composable
private fun Banner(text: String, background: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Text(
        text, color = content, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().background(background, MaterialTheme.shapes.medium).padding(12.dp),
    )
}
