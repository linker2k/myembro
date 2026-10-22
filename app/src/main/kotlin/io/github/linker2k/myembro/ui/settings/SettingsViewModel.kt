// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.Hoop

enum class ThreadBrand(val labelRes: Int) {
    BROTHER(R.string.settings_brand_brother),
    MADEIRA(R.string.settings_brand_madeira),
    ISACORD(R.string.settings_brand_isacord),
}

data class EmbroiderySettings(
    val machineModel: String = "",
    val defaultHoop: Hoop = Hoop.H100,
    val threadBrand: ThreadBrand = ThreadBrand.BROTHER,
)

/** Shared preference access for integration code; opening this screen is not required to read defaults. */
class SettingsPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("myembro_settings", Context.MODE_PRIVATE)

    fun read(): EmbroiderySettings = EmbroiderySettings(
        machineModel = preferences.getString("machine_model", "").orEmpty(),
        defaultHoop = Hoop.entries.firstOrNull { it.name == preferences.getString("default_hoop", null) } ?: Hoop.H100,
        threadBrand = ThreadBrand.entries.firstOrNull { it.name == preferences.getString("thread_brand", null) }
            ?: ThreadBrand.BROTHER,
    )

    fun setMachineModel(model: String) {
        preferences.edit().putString("machine_model", model.trim().take(120)).apply()
    }

    fun setDefaultHoop(hoop: Hoop) {
        preferences.edit().putString("default_hoop", hoop.name).apply()
    }

    fun setThreadBrand(brand: ThreadBrand) {
        preferences.edit().putString("thread_brand", brand.name).apply()
    }
}

/** Persistent user defaults. Applying them to the viewer/editor is left to the navigation owner. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val preferences = SettingsPreferences(app)
    var settings by mutableStateOf(preferences.read())
        private set

    fun setMachineModel(model: String) {
        val name = model.trim().take(120)
        preferences.setMachineModel(name)
        settings = settings.copy(machineModel = name)
    }

    fun setDefaultHoop(hoop: Hoop) {
        preferences.setDefaultHoop(hoop)
        settings = settings.copy(defaultHoop = hoop)
    }

    fun setThreadBrand(brand: ThreadBrand) {
        preferences.setThreadBrand(brand)
        settings = settings.copy(threadBrand = brand)
    }
}
