// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoCorrection
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
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
import androidx.core.graphics.PathParser
import io.github.linker2k.myembro.engine.inkstitch.svg.image.RedworkOutline

import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhotoScreen(vm: PhotoViewModel, settings: EmbroiderySettings, onBack: () -> Unit,
    onEdit: () -> Unit, onSend: (File) -> Unit) {
    LaunchedEffect(settings) { vm.configure(settings) }
    val back = { if (vm.step > 0) vm.back() else { vm.cancel(); onBack() } }
    BackHandler(onBack = back)
    // Android photo picker, images only; androidx activity falls back to its own legacy picker on older devices.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.load(uri) }
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    LaunchedEffect(vm.step) { scroll.scrollTo(0) }
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.busy, vm.step) {
        elapsed = 0
        while (vm.busy) { delay(1000); elapsed++ }
    }
    var showCheck by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.photo_title)) }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.photo_back)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.photo_step, vm.step + 1), style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Text(stringResource(when(vm.step) { 0 -> R.string.photo_crop_title; 1 -> when(vm.style) { PhotoMode.OUTLINE -> R.string.photo_outline_title; PhotoMode.COLOR -> R.string.photo_color_title; else -> R.string.photo_preview_title }; else -> R.string.photo_result_title }),
                style = MaterialTheme.typography.headlineSmall)
            Text(Dimensions.size(vm.hoop.width / 10.0, vm.hoop.height / 10.0), style = MaterialTheme.typography.bodySmall)
            if (vm.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(when (vm.progressStage) {
                    0 -> R.string.photo_progress_decode
                    1 -> R.string.photo_progress_quantize
                    2 -> R.string.photo_progress_trace
                    else -> R.string.photo_progress_stitches
                }))
                Text(stringResource(R.string.photo_elapsed,elapsed),style=MaterialTheme.typography.bodySmall)
                Text(stringResource(if(vm.step==2 && vm.style==PhotoMode.OUTLINE) R.string.photo_estimate_outline else if(vm.step==2) R.string.photo_estimate_result else R.string.photo_estimate),style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick=vm::cancel) { Text(stringResource(R.string.photo_cancel)) }
            }
            if (elapsed >= 10 || vm.error == R.string.photo_too_complex || vm.error == R.string.photo_timed_out || vm.error == R.string.photo_stitches_failed) {
                Text(stringResource(R.string.photo_slow_hint))
                TextButton(onClick=vm::lowerDetail) { Text(stringResource(R.string.photo_lower_detail)) }
            }
            if (vm.step == 0) {
                if (vm.source != null) CropPreview(vm) else Surface(Modifier.fillMaxWidth().height(260.dp), color = colors.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(56.dp), tint = colors.primary) }
                }
                Text(stringResource(R.string.photo_crop_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (vm.source == null) R.string.photo_pick else R.string.photo_pick_again))
                }
            } else {
                if(vm.step==1) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PhotoMode.entries.forEachIndexed { index, value ->
                        SegmentedButton(selected=vm.style==value,onClick={ vm.changeStyle(value) },
                            shape=SegmentedButtonDefaults.itemShape(index,PhotoMode.entries.size),enabled=!vm.busy) {
                            Text(stringResource(when(value) { PhotoMode.SILHOUETTE -> R.string.photo_style_silhouette; PhotoMode.OUTLINE -> R.string.photo_style_outline; PhotoMode.COLOR -> R.string.photo_style_color }))
                        }
                    }
                }
                if (vm.step == 1) {
                    Text(stringResource(R.string.photo_correction),style=MaterialTheme.typography.titleSmall)
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        PhotoCorrection.entries.filter { vm.style != PhotoMode.COLOR || it != PhotoCorrection.OTSU }.forEach { value ->
                            FilterChip(selected=vm.correction==value,onClick={vm.changeCorrection(value)},
                                enabled=!vm.busy && (!vm.correctionAlphaBypassed || value==PhotoCorrection.ORIGINAL),
                                label={Text(stringResource(when(value) {
                                    PhotoCorrection.ORIGINAL -> R.string.photo_correction_original
                                    PhotoCorrection.CLEAN -> R.string.photo_correction_clean
                                    PhotoCorrection.CONTRAST -> R.string.photo_correction_contrast
                                    PhotoCorrection.OTSU -> R.string.photo_correction_otsu
                                }))})
                        }
                    }
                    if(vm.correctionAlphaBypassed) Text(stringResource(R.string.photo_correction_alpha_hint),style=MaterialTheme.typography.bodySmall)
                }
                val data = vm.render
                if (vm.step == 1 && vm.style != PhotoMode.COLOR && vm.outline != null) OutlinePreview(vm.outline!!,vm.style)
                else if (vm.step == 1 && vm.separatedPreview != null) Image(vm.separatedPreview!!.asImageBitmap(),
                    contentDescription=stringResource(if(vm.style==PhotoMode.COLOR) R.string.photo_color_preview_accessibility else R.string.photo_separated_preview),
                    modifier=Modifier.fillMaxWidth().height(300.dp),contentScale=ContentScale.Fit)
                else if (data != null) StitchCanvas(data, Int.MAX_VALUE, Hoop.H100, false, vm.detail,
                    Modifier.fillMaxWidth().height(300.dp), colors.background.toArgb(), colors.outline.toArgb(), frame = vm.hoop, preservePlacement = true)
                else Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { if (vm.busy) CircularProgressIndicator() }
                if (vm.step == 1) {
                    if(vm.style==PhotoMode.COLOR) {
                        var colorDraft by remember(vm.colorCount) { mutableFloatStateOf(vm.colorCount.toFloat()) }
                        val colorLabel=stringResource(R.string.photo_color_count,colorDraft.toInt())
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(colorLabel,style=MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                val grayscaleLabel=stringResource(R.string.photo_grayscale)
                                Text(grayscaleLabel,style=MaterialTheme.typography.labelLarge)
                                Switch(checked=vm.grayscale,onCheckedChange=vm::changeGrayscale,enabled=!vm.busy,
                                    modifier=Modifier.semantics { contentDescription=grayscaleLabel })
                            }
                        }
                        Slider(colorDraft,{ colorDraft=it },valueRange=2f..6f,steps=3,
                            onValueChangeFinished={ vm.changeColorCount(colorDraft.toInt()) },enabled=!vm.busy,
                            modifier=Modifier.semantics { contentDescription=colorLabel })
                        Text(stringResource(R.string.photo_color_hint),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                    } else {
                    Text(stringResource(R.string.photo_detail), style = MaterialTheme.typography.titleSmall)
                    val detailLabel = stringResource(R.string.photo_detail)
                    Slider(vm.detail.toFloat(), { vm.changeDetail(it.toInt()) }, valueRange = 0f..2f, steps = 1,
                        modifier = Modifier.semantics { contentDescription = detailLabel })
                    Text(stringResource(R.string.photo_detail_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    // Embroidery (ink) width in mm, aspect kept. The drag only moves the label; tracing reruns once on release.
                    val range = vm.inkWidthRange
                    val ink = vm.inkWidthMm
                    if (range != null && ink != null) EmbroiderySizeSlider(ink, range, vm::changeSize)
                } else vm.report?.let { report ->
                    AssistChip(onClick = { showCheck = true }, label = {
                        Text(stringResource(if (vm.passed) R.string.photo_pass else R.string.photo_blocked))
                    }, colors = AssistChipDefaults.assistChipColors(labelColor = if (vm.passed) colors.primary else colors.error))
                    Text(stringResource(R.string.photo_stats, report.statistics.stitchCount, sewoutMinutes(report.time.totalSeconds)),
                        style = MaterialTheme.typography.titleMedium)
                    vm.resultSizeMm?.let { (w, h) ->
                        Text(stringResource(R.string.photo_result_size, Dimensions.size(w, h)), style = MaterialTheme.typography.bodyMedium)
                    }
                    if(vm.style==PhotoMode.COLOR) {
                        Text(stringResource(R.string.photo_threads_title),style=MaterialTheme.typography.titleMedium)
                        vm.requiredThreads.forEach { thread ->
                            Text(stringResource(R.string.photo_thread_row,thread.catalogNumber.orEmpty(),thread.description.orEmpty()),
                                style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(stringResource(R.string.photo_time_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if(vm.step==2 && vm.excludedCount>0) Text(stringResource(R.string.photo_excluded_regions,vm.excludedCount),color=colors.onSurfaceVariant)
            vm.error?.let { Text(stringResource(it), color = colors.error) }
            if (vm.step < 2) Button(onClick = vm::next,
                enabled = !vm.busy && (if (vm.step == 0) vm.source != null else vm.outline != null), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.photo_next))
            } else {
                if (!vm.busy && vm.render == null) Button(onClick=vm::next) { Text(stringResource(R.string.photo_retry)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onEdit, enabled = !vm.busy && (vm.render?.pattern?.stitchCount ?: 0)>0, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.photo_edit)) }
                Button(onClick = { vm.send(onSend) }, enabled = vm.canSend, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.photo_send)) }
                }
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


@Composable
private fun OutlinePreview(outline: RedworkOutline,style: PhotoMode) {
    val shape = remember(outline) {
        android.graphics.Path().apply {
            fillType = android.graphics.Path.FillType.EVEN_ODD
            outline.paths.mapNotNull { PathParser.createPathFromPathData(it) }.forEach { addPath(it) }
        }.asComposePath()
    }
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(300.dp)) {
        val w = Units.mmToPixels(outline.widthMm).toFloat()
        val h = Units.mmToPixels(outline.heightMm).toFloat()
        val factor = minOf(size.width / w, size.height / h) * .95f
        withTransform({
            translate(size.width / 2, size.height / 2)
            scale(factor, factor, pivot = androidx.compose.ui.geometry.Offset.Zero)
        }) {
            if(style==PhotoMode.OUTLINE) drawPath(shape,ink,
                style=androidx.compose.ui.graphics.drawscope.Stroke(Units.mmToPixels(0.3).toFloat()))
            else drawPath(shape, ink)
        }
    }
}
