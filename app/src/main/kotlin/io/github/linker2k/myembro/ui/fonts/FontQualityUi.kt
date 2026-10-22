// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.fonts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.fonts.FontQualitySummary
import io.github.linker2k.myembro.fonts.QualityGrade
import io.github.linker2k.myembro.fonts.QualityReasons
import io.github.linker2k.myembro.fonts.QualityReport
import java.util.Locale

/** Grade badge: green / amber / red-brown, each with text so color is never the only signal. */
@Composable
fun QualityBadge(grade: QualityGrade, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val (bg, fg, label) = when (grade) {
        QualityGrade.GOOD -> Triple(c.secondaryContainer, c.onSecondaryContainer, R.string.fonts_grade_good)
        QualityGrade.CAUTION -> Triple(c.tertiaryContainer, c.onTertiaryContainer, R.string.fonts_grade_caution)
        QualityGrade.UNSUITABLE -> Triple(c.errorContainer, c.onErrorContainer, R.string.fonts_grade_unsuitable)
    }
    Text(
        stringResource(label),
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.background(bg, MaterialTheme.shapes.extraSmall).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** App keys are localized; the engine's reasons are already sentences. */
@Composable
fun reasonText(key: String): String = when (key) {
    QualityReasons.MISSING_HANGUL -> stringResource(R.string.fonts_reason_missing_hangul)
    QualityReasons.MISSING_LATIN -> stringResource(R.string.fonts_reason_missing_latin)
    QualityReasons.NOT_SCALABLE -> stringResource(R.string.fonts_reason_not_scalable)
    else -> key
}

fun mm(value: Double): String = if (value.isFinite()) String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0") else "—"

/** "Recommended height" line: null when any height works, a warning when scaling cannot help. */
@Composable
fun minHeightText(recommendedMm: Double): String? = when {
    recommendedMm <= 0.0 -> null
    !recommendedMm.isFinite() -> stringResource(R.string.fonts_min_height_unfixable)
    else -> stringResource(R.string.fonts_min_height, mm(recommendedMm))
}

/** Recommended height, reasons and the per-size sample numbers. */
@Composable
fun QualityDetails(summary: FontQualitySummary, modifier: Modifier = Modifier) {
    Column(modifier) {
        minHeightText(summary.recommendedMinHeightMm)?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        // The heading already says when scaling cannot help.
        summary.reasons.filterNot { it == QualityReasons.NOT_SCALABLE }
            .forEach { Text("• " + reasonText(it), style = MaterialTheme.typography.bodySmall) }
        listOfNotNull(summary.at10?.let { 10 to it }, summary.at20?.let { 20 to it }).forEach { (h, r) -> SampleLine(h, r) }
    }
}

@Composable
private fun SampleLine(heightMm: Int, r: QualityReport) {
    Text(
        stringResource(R.string.fonts_sample_report, heightMm, mm(r.minStrokeMm), r.stitchCount, r.jumpCount, r.tightSpots),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
