// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

import android.app.Activity
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.settings.SettingsPreferences

/** Host hook: call before super.onCreate(). No artificial delay or retained splash screen. */
fun Activity.installMyembroSplashScreen(): SplashScreen {
    val mode = SettingsPreferences(this).read().themeMode
    applyPlatformThemeMode(mode)
    val dark = mode.isDark(systemIsDark())
    setTheme(if (dark) R.style.Theme_Myembro_Starting_Dark else R.style.Theme_Myembro_Starting_Light)
    // installSplashScreen applies the NoActionBar post-theme before decorView is created.
    val splash = installSplashScreen()
    applyThemeWindow(dark)
    splash.setOnExitAnimationListener { provider ->
        provider.view.animate().alpha(0f).setDuration(180L).withEndAction { provider.remove() }.start()
    }
    return splash
}
