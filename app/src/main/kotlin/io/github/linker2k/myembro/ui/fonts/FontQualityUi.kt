// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.fonts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val (bg, fg, label) = when (grade) {
        QualityGrade.GOOD -> Triple(Color(0xFFE3EFE0), Color(0xFF1F4D28), R.string.fonts_grade_good)
        QualityGrade.CAUTION -> Triple(Color(0xFFFBEBC8), Color(0xFF6B4A00), R.string.fonts_grade_caution)
        QualityGrade.UNSUITABLE -> Triple(Color(0xFFFBE3D8), Color(0xFF8A2E12), R.string.fonts_grade_unsuitable)
    }
    Text(
        stringResource(label),
        color = fg,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun reasonText(key: String): String = when (key) {
    QualityReasons.THIN_STROKES -> stringResource(R.string.fonts_reason_thin_strokes)
    QualityReasons.TIGHT_SPOTS -> stringResource(R.string.fonts_reason_tight_spots)
    QualityReasons.MANY_JUMPS -> stringResource(R.string.fonts_reason_many_jumps)
    QualityReasons.TINY_DETAILS -> stringResource(R.string.fonts_reason_tiny_details)
    QualityReasons.MISSING_HANGUL -> stringResource(R.string.fonts_reason_missing_hangul)
    QualityReasons.MISSING_LATIN -> stringResource(R.string.fonts_reason_missing_latin)
    else -> key
}

fun mm(value: Double): String = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")

/** Recommended height, reasons and the per-size sample numbers. */
@Composable
fun QualityDetails(summary: FontQualitySummary, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.fonts_min_height, mm(summary.recommendedMinHeightMm)), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        summary.reasons.forEach { Text("• " + reasonText(it), fontSize = 12.sp) }
        listOfNotNull(summary.at10?.let { 10 to it }, summary.at20?.let { 20 to it }).forEach { (h, r) -> SampleLine(h, r) }
    }
}

@Composable
private fun SampleLine(heightMm: Int, r: QualityReport) {
    Text(
        stringResource(R.string.fonts_sample_report, heightMm, mm(r.minStrokeMm), r.stitchCount, r.jumpCount, r.tightSpots),
        fontSize = 11.sp,
        color = Color(0xFF6B5C4E),
    )
}
