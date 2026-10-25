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
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.ui.machines.CustomFrame
import io.github.linker2k.myembro.ui.machines.CustomFrameCodec
import io.github.linker2k.myembro.ui.machines.FrameCatalog
import io.github.linker2k.myembro.ui.machines.FrameOption
import io.github.linker2k.myembro.ui.machines.MachineProfile
import io.github.linker2k.myembro.ui.machines.MachineProfiles
import io.github.linker2k.myembro.engine.io.PesVersion
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.ui.theme.ThemeMode
import io.github.linker2k.myembro.ui.theme.applyPlatformThemeMode

enum class ThreadBrand(val labelRes: Int) {
    BROTHER(R.string.settings_brand_brother),
    MADEIRA(R.string.settings_brand_madeira),
    ISACORD(R.string.settings_brand_isacord),
}

data class EmbroiderySettings(
    val machineModel: String = MachineProfiles.default.displayName,
    val defaultHoop: Hoop = Hoop.H100,
    val threadBrand: ThreadBrand = ThreadBrand.BROTHER,
    /** v1 stays the default until a v6 file is confirmed on a real SE400 (docs/machines.md). */
    val pesVersion: PesVersion = PesVersion.V1,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val customFrames: List<CustomFrame> = emptyList(),
    val defaultFrameId: String? = null,
    val myThreads: io.github.linker2k.myembro.engine.io.MyThreads = io.github.linker2k.myembro.engine.io.MyThreads(),
    val favoriteFontIds: Set<String> = emptySet(),
) {
    /** Fixed Ink/Stitch defaults; intentionally not a user preference. */
    val sewing: SewingOptions get() = SewingOptions()
    val writeSettings: PesWriteSettings get() = PesWriteSettings(pesVersion, hoop = defaultFrame.spec)
    val machineProfile: MachineProfile? = MachineProfiles.find(machineModel)
    val frames: List<FrameOption> get() = FrameCatalog.options(customFrames)
    val defaultFrame: FrameOption get() = frames.firstOrNull { it.id == defaultFrameId }
        ?: frames.first { it.id == defaultHoop.name }
    val availableViewerHoops: List<Hoop> get() =
        Hoop.entries
    // Selection is unrestricted; machine compatibility remains a separate warning.
    val availableEditorHoops: List<HoopSpec> get() = frames.map { it.spec }
    fun constrainHoop(hoop: Hoop): Hoop = hoop.takeIf { it in availableViewerHoops } ?: Hoop.H100
    fun validated(): EmbroiderySettings = copy(defaultHoop = constrainHoop(defaultHoop))
}

/** Shared preference access for integration code; opening this screen is not required to read defaults. */
class SettingsPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.applicationContext.getSharedPreferences("myembro_settings", Context.MODE_PRIVATE)

    fun read(): EmbroiderySettings = EmbroiderySettings(
        machineModel = preferences.getString("machine_model", null).orEmpty()
            .ifBlank { MachineProfiles.default.displayName },
        defaultHoop = Hoop.entries.firstOrNull { it.name == preferences.getString("default_hoop", null) } ?: Hoop.H100,
        threadBrand = ThreadBrand.entries.firstOrNull { it.name == preferences.getString("thread_brand", null) }
            ?: ThreadBrand.BROTHER,
        pesVersion = PesVersion.entries.firstOrNull { it.name == preferences.getString("pes_version", null) }
            ?: PesVersion.V1,
        themeMode = ThemeMode.fromStored(preferences.getString("theme_mode", null)),
        customFrames = CustomFrameCodec.decode(preferences.getString("custom_frames", null)),
        defaultFrameId = preferences.getString("default_frame_id", null),
        myThreads = io.github.linker2k.myembro.engine.io.MyThreads(
            preferences.getBoolean("my_threads_enabled", false),
            preferences.getStringSet("my_thread_indices", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()),
        favoriteFontIds = preferences.getStringSet("favorite_font_ids", emptySet()).orEmpty().toSet(),
    ).validated()

    fun setMyThreads(value: io.github.linker2k.myembro.engine.io.MyThreads) {
        preferences.edit().putBoolean("my_threads_enabled", value.enabled)
            .putStringSet("my_thread_indices", value.indices.filter { it in 1 until io.github.linker2k.myembro.engine.io.PecThreads.SET.size }.map { it.toString() }.toSet()).apply()
    }

    fun toggleFavoriteFont(id: String) {
        val current = read().favoriteFontIds
        preferences.edit().putStringSet("favorite_font_ids", if (id in current) current - id else current + id).apply()
    }

    fun setMachineModel(model: String) {
        val name = model.trim().take(120).ifBlank { MachineProfiles.default.displayName }
        val edit = preferences.edit().putString("machine_model", name).remove("default_frame_id")
        if (MachineProfiles.find(name) != null) edit.putString("default_hoop", Hoop.H100.name)
        edit.apply()
    }

    fun setDefaultHoop(hoop: Hoop) {
        preferences.edit().putString("default_hoop", read().constrainHoop(hoop).name).putString("default_frame_id", hoop.name).apply()
    }

    fun setThreadBrand(brand: ThreadBrand) {
        preferences.edit().putString("thread_brand", brand.name).apply()
    }

    fun setPesVersion(version: PesVersion) {
        preferences.edit().putString("pes_version", version.name).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString("theme_mode", mode.name).apply()
        appContext.applyPlatformThemeMode(mode)
    }

    /** Profile query API for the integration owner; sizes are engine-native 0.1 mm. */
    fun machineProfile(): MachineProfile? = read().machineProfile
    fun editorHoops(): List<HoopSpec> = read().availableEditorHoops
    fun defaultEditorHoop(): HoopSpec = read().defaultFrame.spec

    fun setDefaultFrame(id: String) {
        val frame = read().frames.firstOrNull { it.id == id } ?: return
        val edit = preferences.edit().putString("default_frame_id", id)
        frame.preset?.let { edit.putString("default_hoop", it.name) }
        edit.apply()
    }

    fun saveCustomFrame(frame: CustomFrame) {
        val frames = read().customFrames.filterNot { it.id == frame.id } + frame
        require(frames.size <= 100)
        preferences.edit().putString("custom_frames", CustomFrameCodec.encode(frames)).apply()
    }

    fun deleteCustomFrame(id: String) {
        val current = read()
        val edit = preferences.edit().putString("custom_frames", CustomFrameCodec.encode(current.customFrames.filterNot { it.id == id }))
        if (current.defaultFrameId == id) edit.remove("default_frame_id").putString("default_hoop", Hoop.H100.name)
        edit.apply()
    }

    /** Strongly held listener until the returned disposer is called by the owning ViewModel. */
    fun observe(onChange: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

/** Persistent user defaults. Applying them to the viewer/editor is left to the navigation owner. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val preferences = SettingsPreferences(app)
    var settings by mutableStateOf(preferences.read())
        private set
    private val stopObserving = preferences.observe { settings = preferences.read() }

    override fun onCleared() { stopObserving() }

    fun setMyThreads(value: io.github.linker2k.myembro.engine.io.MyThreads) {
        preferences.setMyThreads(value); settings = preferences.read()
    }

    fun setMachineModel(model: String) {
        val name = model.trim().take(120)
        preferences.setMachineModel(name)
        settings = preferences.read()
    }

    fun setDefaultHoop(hoop: Hoop) {
        preferences.setDefaultHoop(hoop)
        settings = preferences.read()
    }

    fun setDefaultFrame(id: String) { preferences.setDefaultFrame(id); settings = preferences.read() }
    fun saveCustomFrame(frame: CustomFrame) { preferences.saveCustomFrame(frame); settings = preferences.read() }
    fun deleteCustomFrame(id: String) { preferences.deleteCustomFrame(id); settings = preferences.read() }

    fun setThreadBrand(brand: ThreadBrand) {
        preferences.setThreadBrand(brand)
        settings = settings.copy(threadBrand = brand)
    }

    fun setPesVersion(version: PesVersion) {
        preferences.setPesVersion(version)
        settings = settings.copy(pesVersion = version)
    }

    fun setThemeMode(mode: ThemeMode) {
        preferences.setThemeMode(mode)
        settings = preferences.read()
    }
}
