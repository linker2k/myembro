// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.ui.format.Dimensions

/**
 * "Embroidery size · width" slider shared by photo and emoji: actual stitched width in mm, aspect
 * kept. Dragging only moves the label; [onCommit] runs once on release (no live recalculation).
 */
@Composable
internal fun EmbroiderySizeSlider(widthMm: Double, range: ClosedFloatingPointRange<Double>, onCommit: (Double) -> Unit) {
    var draft by remember(widthMm) { mutableFloatStateOf(widthMm.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat())) }
    val label = stringResource(R.string.photo_size, Dimensions.length(Math.round(draft * 10) / 10.0))
    Text(label, style = MaterialTheme.typography.titleSmall)
    Slider(draft, { draft = it }, valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        onValueChangeFinished = { onCommit(draft.toDouble()) }, enabled = range.endInclusive > range.start,
        modifier = Modifier.semantics { contentDescription = label })
    Text(stringResource(R.string.photo_size_hint, Dimensions.length(range.start), Dimensions.length(range.endInclusive)),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Width range for a whole picture whose height is [aspect] × width: from the photo sweep minimum
 * up to what fits the hoop's 0..(units−1) range in both directions (P2).
 */
internal fun pictureWidthRange(hoop: HoopSpec, aspect: Double): ClosedFloatingPointRange<Double> {
    val max = kotlin.math.floor(minOf(hoop.width - 1.0, (hoop.height - 1.0) / aspect)) / 10.0
    return minOf(PHOTO_MIN_WIDTH_MM, max)..max
}
