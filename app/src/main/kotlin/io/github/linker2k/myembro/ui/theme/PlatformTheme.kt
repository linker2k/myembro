// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

internal fun systemIsDark(): Boolean =
    Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** Android 12+ persists this per-app override for native starting windows, including cold starts.
 * AUTO clears the per-app night qualifier in AOSP and thus follows the system (not a local timer):
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/UiModeManagerService.java
 */
fun Context.applyPlatformThemeMode(mode: ThemeMode) {
    if (Build.VERSION.SDK_INT >= 31) getSystemService(UiModeManager::class.java)?.setApplicationNightMode(when (mode) {
        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
    })
    // Enable the next entry before disabling the previous one. The running task is never killed.
    val launchers = mapOf(
        ThemeMode.LIGHT to LightThemeLauncherActivity::class.java,
        ThemeMode.DARK to DarkThemeLauncherActivity::class.java,
        ThemeMode.SYSTEM to SystemThemeLauncherActivity::class.java,
    )
    for ((choice, type) in launchers.entries.sortedBy { it.key != mode }) {
        val component = ComponentName(this, type)
        val desired = if (choice == mode) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (packageManager.getComponentEnabledSetting(component) != desired) {
            packageManager.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
        }
    }
}

/** Called after splash post-theme installation and by the root composition before its first draw. */
@Suppress("DEPRECATION")
internal fun Activity.applyThemeWindow(dark: Boolean, colorway: MyembroColorway = DefaultColorway) {
    val background = myembroColorScheme(dark, colorway).background.toArgb()
    window.setBackgroundDrawable(ColorDrawable(background))
    window.navigationBarColor = background
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
    if (Build.VERSION.SDK_INT >= 29) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
}
