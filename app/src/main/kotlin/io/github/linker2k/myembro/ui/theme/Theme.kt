// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private data class ThemeConfig(val dark: Boolean, val colorway: MyembroColorway)
private val LocalThemeConfig = staticCompositionLocalOf<ThemeConfig?> { null }

/** Fixed brand palettes: wallpaper/dynamic colors are deliberately never requested. */
@Composable
fun MyembroTheme(
    darkTheme: Boolean = LocalThemeConfig.current?.dark ?: isSystemInDarkTheme(),
    colorway: MyembroColorway = LocalThemeConfig.current?.colorway ?: DefaultColorway,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalThemeConfig provides ThemeConfig(darkTheme, colorway)) {
        MaterialTheme(
            colorScheme = myembroColorScheme(darkTheme, colorway),
            typography = MyembroTypography, shapes = MyembroShapeScheme, content = content,
        )
    }
}
