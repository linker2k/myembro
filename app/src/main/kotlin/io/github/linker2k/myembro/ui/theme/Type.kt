// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.linker2k.myembro.R

// Unmodified OFL fonts from google/fonts; full Hangul, Jamo and Hanja coverage is retained.
@OptIn(ExperimentalTextApi::class)
val NotoSansKr = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.noto_sans_kr, weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)
val GowunBatang = FontFamily(Font(R.font.gowun_batang, FontWeight.Normal))

private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, display: Boolean = false) = TextStyle(
    fontFamily = if (display) GowunBatang else NotoSansKr,
    fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

val MyembroTypography = Typography(
    displayLarge = text(48, 62, display = true), displayMedium = text(40, 54, display = true),
    displaySmall = text(34, 46, display = true), headlineLarge = text(30, 42, display = true),
    headlineMedium = text(26, 38, display = true), headlineSmall = text(24, 34, display = true),
    titleLarge = text(22, 32, FontWeight.SemiBold), titleMedium = text(18, 28, FontWeight.SemiBold),
    titleSmall = text(16, 24, FontWeight.Medium), bodyLarge = text(16, 26),
    bodyMedium = text(14, 22), bodySmall = text(12, 18),
    labelLarge = text(14, 22, FontWeight.SemiBold), labelMedium = text(12, 18, FontWeight.Medium),
    labelSmall = text(11, 16, FontWeight.Medium),
)
