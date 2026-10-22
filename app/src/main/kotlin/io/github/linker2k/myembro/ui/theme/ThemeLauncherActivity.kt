// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.linker2k.myembro.MainActivity

/** The OS paints a starting window before app code runs. Exactly one launcher is enabled,
 * giving that window a fixed light/dark theme (or the system-qualified theme) from the outset.
 * The real activity keeps navigation/state ownership; this no-history entry only forwards intents.
 */
abstract class ThemeLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installMyembroSplashScreen()
        super.onCreate(savedInstanceState)
        startActivity(Intent(intent).setClass(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

class LightThemeLauncherActivity : ThemeLauncherActivity()
class DarkThemeLauncherActivity : ThemeLauncherActivity()
class SystemThemeLauncherActivity : ThemeLauncherActivity()
