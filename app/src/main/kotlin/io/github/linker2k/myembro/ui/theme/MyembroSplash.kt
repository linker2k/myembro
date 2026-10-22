// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

import android.app.Activity
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/** Host hook: call before super.onCreate(). No artificial delay or retained splash screen. */
fun Activity.installMyembroSplashScreen(): SplashScreen = installSplashScreen().apply {
    setOnExitAnimationListener { provider ->
        provider.view.animate().alpha(0f).setDuration(180L).withEndAction { provider.remove() }.start()
    }
}
