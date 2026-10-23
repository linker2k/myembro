// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.StitchCanvas
import io.github.linker2k.myembro.ui.sewoutMinutes
import io.github.linker2k.myembro.ui.format.Dimensions
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoScreen(vm: PhotoViewModel, settings: EmbroiderySettings, onBack: () -> Unit,
    onEdit: () -> Unit, onSend: (File) -> Unit) {
    LaunchedEffect(settings) { vm.configure(settings) }
    val back = { if (vm.step > 0) vm.back() else onBack() }
    BackHandler(onBack = back)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.load(uri) }
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    LaunchedEffect(vm.step) { scroll.scrollTo(0) }
    var showCheck by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.photo_title)) }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.photo_back)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.photo_step, vm.step + 1), style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Text(stringResource(when(vm.step) { 0 -> R.string.photo_crop_title; 1 -> R.string.photo_preview_title; else -> R.string.photo_result_title }),
                style = MaterialTheme.typography.headlineSmall)
            Text(Dimensions.size(vm.hoop.width / 10.0, vm.hoop.height / 10.0), style = MaterialTheme.typography.bodySmall)
            if (vm.step == 0) {
                if (vm.source != null) CropPreview(vm) else Surface(Modifier.fillMaxWidth().height(260.dp), color = colors.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(56.dp), tint = colors.primary) }
                }
                Text(stringResource(R.string.photo_crop_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (vm.source == null) R.string.photo_pick else R.string.photo_pick_again))
                }
            } else {
                val data = vm.render
                if (data != null) StitchCanvas(data, Int.MAX_VALUE, Hoop.H100, false, vm.detail,
                    Modifier.fillMaxWidth().height(300.dp), colors.background.toArgb(), colors.outline.toArgb(), frame = vm.hoop, preservePlacement = true)
                else Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { if (vm.busy) CircularProgressIndicator() }
                if (vm.step == 1) {
                    Text(stringResource(R.string.photo_detail), style = MaterialTheme.typography.titleSmall)
                    val detailLabel = stringResource(R.string.photo_detail)
                    Slider(vm.detail.toFloat(), { vm.changeDetail(it.toInt()) }, valueRange = 0f..2f, steps = 1,
                        modifier = Modifier.semantics { contentDescription = detailLabel })
                    Text(stringResource(R.string.photo_detail_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } else vm.report?.let { report ->
                    AssistChip(onClick = { showCheck = true }, label = {
                        Text(stringResource(if (vm.passed) R.string.photo_pass else R.string.photo_blocked))
                    }, colors = AssistChipDefaults.assistChipColors(labelColor = if (vm.passed) colors.primary else colors.error))
                    Text(stringResource(R.string.photo_stats, report.statistics.stitchCount, sewoutMinutes(report.time.totalSeconds)),
                        style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.photo_time_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            vm.error?.let { Text(stringResource(it), color = colors.error) }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.step < 2) Button(onClick = vm::next,
                enabled = !vm.busy && (if (vm.step == 0) vm.source != null else vm.render != null), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.photo_next))
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onEdit, enabled = !vm.busy && vm.render != null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.photo_edit)) }
                Button(onClick = { vm.send(onSend) }, enabled = vm.canSend, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.photo_send)) }
            }
        }
    }
    if (showCheck) AlertDialog(onDismissRequest = { showCheck = false }, title = { Text(stringResource(R.string.photo_check_title)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(if (vm.report?.hasBlockingIssues == true) R.string.photo_blocked_hint else R.string.photo_check_hint))
        } }, confirmButton = { TextButton(onClick = { showCheck = false }) { Text(stringResource(R.string.photo_close)) } })
}

@Composable
private fun CropPreview(vm: PhotoViewModel) {
    val bitmap = vm.source ?: return
    val aspect = vm.hoop.width.toDouble() / vm.hoop.height
    val crop = photoCrop(bitmap.width, bitmap.height, aspect, vm.zoom, vm.panX, vm.panY)
    val accent = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.photo_crop_accessibility)
    BoxWithConstraints(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
        val w = minOf(maxWidth, 300.dp * aspect.toFloat())
        Canvas(Modifier.width(w).height(w / aspect.toFloat()).semantics { contentDescription = description }
            .pointerInput(vm) { detectTransformGestures { _, pan, zoom, _ -> vm.transform(zoom, pan.x / size.width, pan.y / size.height) } }) {
            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawBitmap(bitmap,
                Rect(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height), RectF(0f, 0f, size.width, size.height),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
            drawRect(accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawCircle(accent, 5.dp.toPx(), androidx.compose.ui.geometry.Offset(7.dp.toPx(), 7.dp.toPx()))
        }
    }
}
