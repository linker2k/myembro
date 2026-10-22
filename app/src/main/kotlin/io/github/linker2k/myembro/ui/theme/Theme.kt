// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import io.github.linker2k.myembro.ui.settings.SettingsPreferences

private data class ThemeConfig(val dark: Boolean, val colorway: MyembroColorway)
private val LocalThemeConfig = staticCompositionLocalOf<ThemeConfig?> { null }

/** Fixed brand palettes: wallpaper/dynamic colors are deliberately never requested. */
@Composable
fun MyembroTheme(
    darkTheme: Boolean? = null,
    colorway: MyembroColorway = LocalThemeConfig.current?.colorway ?: DefaultColorway,
    content: @Composable () -> Unit,
) {
    val inherited = LocalThemeConfig.current
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) { SettingsPreferences(context.applicationContext) }
    var mode by remember(preferences) { mutableStateOf(preferences.read().themeMode) }
    DisposableEffect(preferences, inherited) {
        val dispose = if (inherited == null) preferences.observe { mode = preferences.read().themeMode } else ({})
        onDispose { dispose() }
    }
    val dark = darkTheme ?: inherited?.dark ?: mode.isDark(isSystemInDarkTheme())
    val view = LocalView.current
    if (inherited == null && !view.isInEditMode) SideEffect {
        context.activity()?.applyThemeWindow(dark, colorway)
    }
    CompositionLocalProvider(LocalThemeConfig provides ThemeConfig(dark, colorway)) {
        MaterialTheme(
            colorScheme = myembroColorScheme(dark, colorway),
            typography = MyembroTypography, shapes = MyembroShapeScheme, content = content,
        )
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
