// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.ui.format.Dimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val FillOptionsSaver = listSaver<FillOptions, Any>(
    save = { listOf(it.densityMm, it.angleDeg, it.pullCompensationMm, it.underlay, it.border.name, it.strategy.name, it.autoDensity) },
    restore = { FillOptions(it[0] as Double, it[1] as Double, it[2] as Double, it[3] as Boolean, FillBorder.valueOf(it[4] as String),
        (it.getOrNull(5) as? String)?.let(FillStrategy::valueOf) ?: FillStrategy.TATAMI, it.getOrNull(6) as? Boolean ?: false) },
)
internal val SatinOptionsSaver = listSaver<SatinOptions, Any>(
    save = { listOf(it.widthMm, it.densityMm, it.maxStitchLengthMm, it.pullCompensationMm, it.centerUnderlay,
        it.zigzagUnderlay, it.underlayStitchLengthMm, it.underlayDensityMm, it.autoDensity) },
    restore = { SatinOptions(it[0] as Double, it[1] as Double, it[2] as Double, it[3] as Double,
        it[4] as Boolean, it[5] as Boolean, it[6] as Double, it[7] as Double, it.getOrNull(8) as? Boolean ?: false) },
)

/** The editor's direct mode picker carries the same small-letter warning as the full route. */
@Composable
fun TextFillWarning(text: TextObject, style: StitchStyle) {
    if (text.singleLine || style !in listOf(StitchStyle.FILL, StitchStyle.SATIN)) return
    var report by remember { mutableStateOf<FontQualityReport?>(null) }
    LaunchedEffect(text) {
        report = withContext(Dispatchers.Default) { outlineTextQuality(text) }
    }
    report?.let { quality ->
        val height = filledLetteringMinimumHeight(quality, text.threadWeight)
        if (quality.grade != Grade.GOOD || text.heightMm < height) {
            val message = if (height.isFinite() && height <= 300.0)
                stringResource(R.string.lettering_quality_height, Dimensions.length(height))
            else stringResource(R.string.lettering_quality_check)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Shared by the lettering route and the editor's text properties. */
@Composable
fun LetteringModeSelector(singleLine: Boolean, style: StitchStyle, onChange: (StitchStyle) -> Unit, enabled: Boolean = true) {
    val modes = if (singleLine) listOf(StitchStyle.RUNNING, StitchStyle.BEAN, StitchStyle.SATIN)
        else listOf(StitchStyle.RUNNING, StitchStyle.FILL, StitchStyle.SATIN)
    Column {
        Text(stringResource(R.string.lettering_sewing_mode), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            modes.forEach { mode ->
                FilterChip(selected = style == mode || !singleLine && mode == StitchStyle.RUNNING && style == StitchStyle.BEAN,
                    onClick = { onChange(mode) }, enabled = enabled, label = {
                        Text(stringResource(when (mode) {
                            StitchStyle.RUNNING -> if (singleLine) R.string.lettering_running else R.string.lettering_outline_mode
                            StitchStyle.BEAN -> R.string.lettering_bean
                            StitchStyle.FILL -> R.string.lettering_fill_mode
                            StitchStyle.SATIN -> R.string.lettering_satin_mode
                        }))
                    })
            }
        }
    }
}

@Composable
internal fun FillSettingsControls(options: FillOptions, heightMm: Double, threadWeight: ThreadWeight, onChange: (FillOptions) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FillStrategy.entries.forEach { strategy ->
            FilterChip(options.strategy == strategy, { onChange(options.copy(strategy = strategy)) }, label = {
                Text(stringResource(if (strategy == FillStrategy.AUTO) R.string.lettering_auto_fill else R.string.lettering_fixed_tatami))
            })
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.lettering_auto_density), style = MaterialTheme.typography.bodyMedium)
        Switch(options.autoDensity, { onChange(options.copy(autoDensity = it)) })
    }
    SettingSlider(stringResource(R.string.lettering_density), options.densityForHeight(heightMm, threadWeight).toFloat(), 0.3f..1f, 0.05f, "mm") {
        onChange(options.copy(densityMm = it.toDouble().coerceIn(0.3, 1.0), autoDensity = false))
    }
    if (options.strategy == FillStrategy.TATAMI) SettingSlider(stringResource(R.string.lettering_fill_angle), options.angleDeg.toFloat(), -180f..180f, 15f, "°") {
        onChange(options.copy(angleDeg = it.toDouble()))
    }
    SettingSlider(stringResource(R.string.lettering_pull), options.pullCompensationMm.toFloat(), 0f..0.8f, 0.05f, "mm") {
        onChange(options.copy(pullCompensationMm = it.toDouble()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(if (options.strategy == FillStrategy.AUTO) R.string.lettering_auto_underlay else R.string.lettering_underlay), style = MaterialTheme.typography.bodyMedium)
        Switch(options.underlay, { onChange(options.copy(underlay = it)) })
    }
    Text(stringResource(R.string.lettering_border), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FillBorder.entries.forEach { border ->
            FilterChip(options.border == border, { onChange(options.copy(border = border)) }, label = {
                Text(stringResource(when (border) {
                    FillBorder.NONE -> R.string.lettering_border_none
                    FillBorder.RUNNING -> R.string.lettering_running
                    FillBorder.BEAN -> R.string.lettering_bean
                }))
            })
        }
    }
}

@Composable
internal fun SatinSettingsControls(options: SatinOptions, heightMm: Double, threadWeight: ThreadWeight, onChange: (SatinOptions) -> Unit) {
    SettingSlider(stringResource(R.string.lettering_satin_width), options.widthMm.toFloat(), 0.6f..6f, 0.2f, "mm") {
        onChange(options.copy(widthMm = it.toDouble()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.lettering_auto_density), style = MaterialTheme.typography.bodyMedium)
        Switch(options.autoDensity, { onChange(options.copy(autoDensity = it)) })
    }
    SettingSlider(stringResource(R.string.lettering_density), options.densityForHeight(heightMm, threadWeight).toFloat(), 0.3f..1f, 0.05f, "mm") {
        onChange(options.copy(densityMm = it.toDouble(), autoDensity = false))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.lettering_center_underlay), style = MaterialTheme.typography.bodyMedium)
        Switch(options.centerUnderlay, { onChange(options.copy(centerUnderlay = it)) })
    }
}

/** Shared by creation and existing-text properties; changing weight restores automatic spacing. */
@Composable
fun ThreadWeightSelector(weight: ThreadWeight, onChange: (ThreadWeight) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.lettering_thread_weight), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThreadWeight.entries.forEach { choice ->
                FilterChip(weight == choice, { onChange(choice) }, label = {
                    Text(stringResource(when (choice) {
                        ThreadWeight.WT40 -> R.string.lettering_weight_40
                        ThreadWeight.WT50 -> R.string.lettering_weight_50
                        ThreadWeight.WT60 -> R.string.lettering_weight_60
                    }))
                })
            }
        }
        Text(stringResource(R.string.lettering_weight_min_feature, weight.minimumFeatureMm),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (weight != ThreadWeight.WT40) Text(stringResource(R.string.lettering_fine_thread_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
