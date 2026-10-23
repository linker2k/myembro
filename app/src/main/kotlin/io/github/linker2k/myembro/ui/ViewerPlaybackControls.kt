// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.ui.theme.MyembroShapes
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerPlaybackControls(vm: ViewerViewModel, data: RenderData) {
    val timeline = data.playback
    val sliderInteraction = remember { MutableInteractionSource() }
    var thumbWidth by remember { mutableIntStateOf(0) }
    val rulerInset = with(LocalDensity.current) { (thumbWidth / 2f).toDp() }
    val enabled = timeline.needleCount > 0
    val count = stringResource(R.string.playback_stitch_count, timeline.needlesThrough(vm.progress), timeline.needleCount)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.playback_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(count, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.seekPlayback(-1) }, enabled = enabled) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.viewer_restart))
            }
            FilledIconButton(onClick = vm::togglePlay, enabled = enabled) {
                Icon(if (vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (vm.playing) R.string.pause else R.string.play))
            }
            val sliderLabel = stringResource(R.string.playback_seek)
            Slider(
                value = (vm.progress + 1).coerceAtLeast(0).toFloat(),
                onValueChange = { vm.seekPlayback(it.roundToInt() - 1) },
                valueRange = 0f..(timeline.endIndex + 1).coerceAtLeast(1).toFloat(),
                enabled = enabled,
                interactionSource = sliderInteraction,
                thumb = { SliderDefaults.Thumb(sliderInteraction, enabled = enabled,
                    modifier = Modifier.onSizeChanged { thumbWidth = it.width }) },
                modifier = Modifier.weight(1f).semantics { contentDescription = sliderLabel; stateDescription = count },
            )
            IconButton(onClick = { vm.seekPlayback(timeline.endIndex) }, enabled = enabled) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.viewer_finish))
            }
        }
        // Align the event ruler with the slider's thumb travel, excluding the three buttons.
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(96.dp))
            PlaybackRuler(timeline, Modifier.weight(1f).padding(horizontal = rulerInset).height(10.dp))
            Spacer(Modifier.width(48.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(1, 4, 16).forEach { multiplier ->
                val label = stringResource(R.string.playback_multiplier, multiplier)
                val description = stringResource(R.string.playback_speed_description, multiplier)
                FilterChip(selected = vm.playbackMultiplier == multiplier, onClick = { vm.selectPlaybackSpeed(multiplier) },
                    label = { Text(label) }, shape = MyembroShapes.Chip,
                    modifier = Modifier.semantics { contentDescription = description })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendMarker(true)
            Text(stringResource(R.string.playback_color_marks, timeline.colorChanges), style = MaterialTheme.typography.labelSmall)
            LegendMarker(false)
            Text(stringResource(R.string.playback_trim_marks, timeline.trims), style = MaterialTheme.typography.labelSmall)
        }
        Text(stringResource(R.string.playback_rate, vm.playbackSpm), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaybackRuler(timeline: ViewerPlaybackTimeline, modifier: Modifier) {
    val colorChange = MaterialTheme.colorScheme.secondary
    val trim = MaterialTheme.colorScheme.tertiary
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val diamond = Path()
        for (mark in timeline.marks) {
            val x = size.width * if (rtl) 1 - mark.fraction else mark.fraction
            if (mark.command == Command.COLOR_CHANGE) {
                val r = 3.dp.toPx()
                drawPath(diamond.apply { reset(); moveTo(x, 0f); lineTo(x + r, r); lineTo(x, r * 2); lineTo(x - r, r); close() }, colorChange)
            } else drawLine(trim, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun LegendMarker(colorChange: Boolean) {
    val color = if (colorChange) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.size(8.dp)) {
        if (colorChange) drawPath(Path().apply { moveTo(size.width / 2, 0f); lineTo(size.width, size.height / 2)
            lineTo(size.width / 2, size.height); lineTo(0f, size.height / 2); close() }, color)
        else drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
    }
}
