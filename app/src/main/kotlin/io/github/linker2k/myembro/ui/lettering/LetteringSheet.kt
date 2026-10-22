// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.lettering.HersheyFont
import io.github.linker2k.myembro.fonts.FontQualitySummary
import io.github.linker2k.myembro.fonts.FontRepository
import io.github.linker2k.myembro.fonts.LetterFont
import io.github.linker2k.myembro.fonts.LetterFontGeometry
import io.github.linker2k.myembro.fonts.QualityGrade
import io.github.linker2k.myembro.ui.fonts.FontLibraryScreen
import io.github.linker2k.myembro.ui.fonts.QualityBadge
import io.github.linker2k.myembro.ui.fonts.mm
import io.github.linker2k.myembro.ui.fonts.reasonText
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF2B2420)
private val Terracotta = Color(0xFFC4552D)
private val Muted = Color(0xFF6B5C4E)

class LetteringViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FontRepository(app)
    private val geometry = LetterFontGeometry(app)

    var fonts by mutableStateOf<List<LetterFont>>(emptyList())
        private set
    var fontId by mutableStateOf(HersheyFont.DEFAULT_ID)
        private set
    var text by mutableStateOf("")
    var heightMm by mutableStateOf(10.0)
    var spacingMm by mutableStateOf(1.0)
    var arcDeg by mutableStateOf(0.0)
    var missing by mutableStateOf<Set<Char>>(emptySet())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** Quality computed in this session for fonts without a stored report (built-in Jua). */
    private val computed = mutableStateMapOf<String, FontQualitySummary>()

    val font: LetterFont? get() = fonts.firstOrNull { it.id == fontId }
    val quality: FontQualitySummary? get() = font?.let { it.quality ?: computed[it.id] }

    /** Heights below this get the warning banner; null when the font has no report. */
    val recommendedMinHeightMm: Double? get() = quality?.recommendedMinHeightMm

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            fonts = withContext(Dispatchers.IO) { repo.letterFonts() }
            if (font == null) fontId = HersheyFont.DEFAULT_ID
            onFontOrTextChanged()
        }
    }

    fun choose(id: String) {
        fontId = id
        onFontOrTextChanged()
    }

    fun updateText(value: String) {
        text = value
        onFontOrTextChanged()
    }

    fun fitRecommended() {
        recommendedMinHeightMm?.let { heightMm = ceil(it * 2) / 2 }
    }

    private fun onFontOrTextChanged() {
        val f = font ?: return
        val current = text
        viewModelScope.launch {
            val lacking = withContext(Dispatchers.IO) { runCatching { geometry.missing(f, current) }.getOrDefault(emptySet()) }
            if (fontId == f.id && text == current) missing = lacking
            if (f.quality == null && f.kind != LetterFont.Kind.HERSHEY && f.id !in computed) {
                withContext(Dispatchers.IO) { runCatching { geometry.analyze(f) }.getOrNull() }?.let { computed[f.id] = it }
            }
        }
    }

    fun build(onDone: (TextObject) -> Unit) {
        val f = font ?: return
        if (busy || text.isBlank()) return
        busy = true
        viewModelScope.launch {
            try {
                val obj = withContext(Dispatchers.Default) {
                    geometry.textObject(f, UUID.randomUUID().toString(), text, heightMm, spacingMm, arcDeg).also { it.localPattern() }
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
}

/**
 * Text tool for the editor: font, size, spacing and arc, with quality warnings from the font
 * report. [onAdd] receives a ready [TextObject]; the sheet closes itself after that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LetteringSheet(onDismiss: () -> Unit, onAdd: (TextObject) -> Unit) {
    val vm: LetteringViewModel = viewModel()
    var library by remember { mutableStateOf(false) }
    var fontMenu by remember { mutableStateOf(false) }
    var unsuitable by remember { mutableStateOf<LetterFont?>(null) }
    var pendingPick by remember { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun pick(id: String) {
        val f = vm.fonts.firstOrNull { it.id == id } ?: return
        if (f.quality?.grade == QualityGrade.UNSUITABLE) unsuitable = f else vm.choose(id)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Color(0xFFF6F0E6)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.lettering_title), style = MaterialTheme.typography.titleLarge, color = Ink)
            OutlinedTextField(
                value = vm.text,
                onValueChange = vm::updateText,
                label = { Text(stringResource(R.string.lettering_text)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 3,
            )

            Text(stringResource(R.string.lettering_font), fontWeight = FontWeight.Bold, color = Ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { fontMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(vm.font?.name ?: "", Modifier.weight(1f), color = Ink, maxLines = 1)
                        vm.quality?.let { QualityBadge(it.grade) }
                        Icon(Icons.Filled.ExpandMore, null, tint = Ink)
                    }
                    DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                        vm.fonts.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.name) },
                                trailingIcon = { f.quality?.let { QualityBadge(it.grade) } },
                                onClick = { fontMenu = false; pick(f.id) },
                            )
                        }
                    }
                }
                TextButton(onClick = { library = true }) { Text(stringResource(R.string.lettering_more_fonts), color = Terracotta) }
            }

            if (vm.missing.isNotEmpty()) {
                Notice(stringResource(R.string.lettering_missing, vm.missing.joinToString(" ")))
            }

            Text(stringResource(R.string.lettering_height, mm(vm.heightMm)), color = Ink)
            Slider(value = vm.heightMm.toFloat(), onValueChange = { vm.heightMm = Math.round(it * 2) / 2.0 }, valueRange = 4f..80f)
            val recommended = vm.recommendedMinHeightMm
            if (recommended != null && vm.heightMm < recommended) {
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFFFBE3D8), RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WarningAmber, null, tint = Terracotta, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.lettering_too_small, mm(recommended)), color = Color(0xFF8A2E12), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = vm::fitRecommended) { Text(stringResource(R.string.lettering_fit_recommended), color = Terracotta, fontWeight = FontWeight.Bold) }
                }
            }

            Text(stringResource(R.string.lettering_spacing, mm(vm.spacingMm)), color = Ink)
            Slider(value = vm.spacingMm.toFloat(), onValueChange = { vm.spacingMm = Math.round(it * 10) / 10.0 }, valueRange = 0f..10f)
            Text(stringResource(R.string.lettering_arc, Math.round(vm.arcDeg).toString()), color = Ink)
            Slider(value = vm.arcDeg.toFloat(), onValueChange = { vm.arcDeg = Math.round(it / 5) * 5.0 }, valueRange = -180f..180f)

            vm.error?.let { Notice(stringResource(R.string.lettering_failed, it)) }
            Button(
                onClick = { vm.build { onAdd(it); onDismiss() } },
                enabled = !vm.busy && vm.text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Terracotta),
            ) { Text(stringResource(R.string.lettering_add)) }
            Spacer(Modifier.height(16.dp))
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
                Text(stringResource(R.string.lettering_unsuitable_body, f.name, f.quality?.reasons.orEmpty().map { reasonText(it) }.joinToString(" ")))
            },
            confirmButton = { TextButton(onClick = { vm.choose(f.id); unsuitable = null }) { Text(stringResource(R.string.lettering_use_anyway)) } },
            dismissButton = { TextButton(onClick = { unsuitable = null }) { Text(stringResource(R.string.fonts_cancel)) } },
        )
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        color = Muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().background(Color(0xFFFBF8F2), RoundedCornerShape(10.dp)).padding(10.dp),
    )
}
