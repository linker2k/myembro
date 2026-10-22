// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class MyembroColorway { CRAFT, BOTANICAL, INK }

// Change this one selection for A / B / C throughout Compose and the generated splash colors.
val DefaultColorway = MyembroColorway.CRAFT

// These named ARGB tokens also generate Android launch resources in app/build.gradle.kts.
const val LightBackgroundArgb: Long = 0xFFF6F0E6
const val DarkBackgroundArgb: Long = 0xFF1C1815
const val LightPrimaryArgb: Long = 0xFFC4552D
const val DarkPrimaryArgb: Long = 0xFFEF9A78
const val LightSecondaryArgb: Long = 0xFF7F9A76
const val DarkSecondaryArgb: Long = 0xFFA4BC99
const val LightTertiaryArgb: Long = 0xFFD9A441
const val DarkTertiaryArgb: Long = 0xFFE8BE68
const val LightInkArgb: Long = 0xFF2B2420
const val DarkInkArgb: Long = 0xFFF4EBDF
const val LightHoopArgb: Long = 0xFF916448
const val DarkHoopArgb: Long = 0xFFD7B58F

private val CraftLight = lightColorScheme(
    primary = Color(LightPrimaryArgb), onPrimary = Color.White,
    primaryContainer = Color(0xFFF5DED1), onPrimaryContainer = Color(0xFF492516),
    secondary = Color(LightSecondaryArgb), onSecondary = Color(LightInkArgb),
    secondaryContainer = Color(0xFFE6ECDF), onSecondaryContainer = Color(0xFF273522),
    tertiary = Color(LightTertiaryArgb), onTertiary = Color(LightInkArgb),
    tertiaryContainer = Color(0xFFF7E8C6), onTertiaryContainer = Color(0xFF493506),
    background = Color(LightBackgroundArgb), onBackground = Color(LightInkArgb),
    surface = Color(0xFFFFFDF8), onSurface = Color(LightInkArgb),
    surfaceVariant = Color(0xFFEEE5D8), onSurfaceVariant = Color(0xFF72645A),
    outline = Color(0xFFD8CBB8), outlineVariant = Color(0xFFE8DED0),
    error = Color(0xFFAD352C), onError = Color.White,
    errorContainer = Color(0xFFF9DEDB), onErrorContainer = Color(0xFF4B1715),
    inverseSurface = Color(LightInkArgb), inverseOnSurface = Color(LightBackgroundArgb),
    inversePrimary = Color(DarkPrimaryArgb), surfaceTint = Color(LightPrimaryArgb),
    surfaceBright = Color(0xFFFFFDF8), surfaceDim = Color(0xFFE9DFD0),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBF6ED),
    surfaceContainer = Color(0xFFF2E9DC), surfaceContainerHigh = Color(0xFFECE1D1),
    surfaceContainerHighest = Color(0xFFE5D7C4),
)

private val CraftDark = darkColorScheme(
    primary = Color(DarkPrimaryArgb), onPrimary = Color(0xFF472314),
    primaryContainer = Color(0xFF693923), onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Color(DarkSecondaryArgb), onSecondary = Color(0xFF1F331E),
    secondaryContainer = Color(0xFF354A30), onSecondaryContainer = Color(0xFFE0EAD8),
    tertiary = Color(DarkTertiaryArgb), onTertiary = Color(0xFF402E05),
    tertiaryContainer = Color(0xFF5C4313), onTertiaryContainer = Color(0xFFFFE2A5),
    background = Color(DarkBackgroundArgb), onBackground = Color(DarkInkArgb),
    surface = Color(0xFF26211D), onSurface = Color(DarkInkArgb),
    surfaceVariant = Color(0xFF443A32), onSurfaceVariant = Color(0xFFD2C3B4),
    outline = Color(0xFF998A7A), outlineVariant = Color(0xFF50463C),
    error = Color(0xFFFFB4AA), onError = Color(0xFF690005),
    errorContainer = Color(0xFF89251E), onErrorContainer = Color(0xFFFFDAD4),
    inverseSurface = Color(DarkInkArgb), inverseOnSurface = Color(DarkBackgroundArgb),
    inversePrimary = Color(LightPrimaryArgb), surfaceTint = Color(DarkPrimaryArgb),
    surfaceBright = Color(0xFF403730), surfaceDim = Color(DarkBackgroundArgb),
    surfaceContainerLowest = Color(0xFF15120F), surfaceContainerLow = Color(0xFF211C18),
    surfaceContainer = Color(0xFF2B2520), surfaceContainerHigh = Color(0xFF362E28),
    surfaceContainerHighest = Color(0xFF413831),
)

fun myembroColorScheme(dark: Boolean, colorway: MyembroColorway = DefaultColorway): ColorScheme {
    val base = if (dark) CraftDark else CraftLight
    return when (colorway) {
        MyembroColorway.CRAFT -> base
        MyembroColorway.BOTANICAL -> base.copy(
            primary = base.secondary, onPrimary = base.onSecondary,
            primaryContainer = base.secondaryContainer, onPrimaryContainer = base.onSecondaryContainer,
            secondary = base.primary, onSecondary = base.onPrimary,
            secondaryContainer = base.primaryContainer, onSecondaryContainer = base.onPrimaryContainer,
            surfaceTint = base.secondary,
        )
        MyembroColorway.INK -> base.copy(
            primary = if (dark) base.tertiary else base.onBackground,
            onPrimary = if (dark) base.onTertiary else base.surface,
            primaryContainer = if (dark) base.tertiaryContainer else base.surfaceContainerHighest,
            onPrimaryContainer = if (dark) base.onTertiaryContainer else base.onSurface,
            surfaceTint = if (dark) base.tertiary else base.onBackground,
        )
    }
}
