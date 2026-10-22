// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.ui.Hoop

/** Proportional hoop outline shared by settings and export summaries. */
@Composable
fun HoopPreview(hoop: Hoop, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.outline
    val clasp = MaterialTheme.colorScheme.primary
    val fabric = MaterialTheme.colorScheme.background
    Canvas(modifier) {
        val scale = minOf(size.width * .78f / hoop.widthMm, size.height * .78f / hoop.heightMm)
        val w = hoop.widthMm * scale
        val h = hoop.heightMm * scale
        val start = Offset((size.width - w) / 2, (size.height - h) / 2 + 2.dp.toPx())
        val radius = CornerRadius(minOf(w, h) * .3f)
        drawRoundRect(fabric, start, Size(w, h), radius)
        drawRoundRect(ring, start, Size(w, h), radius, style = Stroke(4.dp.toPx()))
        drawRoundRect(clasp, Offset(size.width / 2 - 6.dp.toPx(), start.y - 5.dp.toPx()),
            Size(12.dp.toPx(), 5.dp.toPx()), CornerRadius(1.dp.toPx()))
    }
}

@Composable
fun FormatBadge(format: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MyembroShapes.Chip,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(format, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}
