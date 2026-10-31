// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import android.content.Context
import io.github.linker2k.myembro.engine.edit.HoopSpec
import org.json.JSONObject
import java.io.File

/** Read-only bundled gallery, separate from the user's installed/deleted library copies. Call on IO. */
internal object BundledDesigns {
    /** Bump with each replaced pack so upgraded installs do not keep showing the previous pack's copies. */
    private const val DIRECTORY = "bundled_designs_v2"
    private val OLD_DIRECTORIES = listOf("bundled_designs_v1")

    fun files(context: Context): List<File> {
        OLD_DIRECTORIES.forEach { File(context.cacheDir, it).deleteRecursively() }
        val directory = File(context.cacheDir, DIRECTORY)
        SampleInstaller(directory).install(context.assets.list("samples").orEmpty().toList()) { name ->
            context.assets.open("samples/$name")
        }
        return directory.listFiles().orEmpty()
            .filter { it.isFile && DesignLibrary.isSample(it) && it.extension in DesignLibrary.SUPPORTED }
            .sortedBy { it.name }
    }

    /** The home screen's fixed sample row: manifest entries with an `order`, 1 first. */
    fun featured(context: Context): List<File> {
        val order = manifest(context).filter { it.has("order") }.associate { it.getString("file") to it.getInt("order") }
        return files(context).filter { it.name in order }.sortedBy { order.getValue(it.name) }
    }

    private var entries: List<JSONObject>? = null
    private var titles: Pair<Boolean, Map<String, String>>? = null

    @Synchronized
    private fun manifest(context: Context): List<JSONObject> = entries ?: runCatching {
        val samples = JSONObject(context.assets.open("samples/manifest.json").bufferedReader().use { it.readText() })
            .getJSONArray("samples")
        (0 until samples.length()).map(samples::getJSONObject)
    }.getOrDefault(emptyList()).also { entries = it }

    /** manifest.json display names by sample file name, in Korean or English to match the app language. */
    @Synchronized
    fun titles(context: Context): Map<String, String> {
        val korean = context.resources.configuration.locales[0].language == "ko"
        titles?.takeIf { it.first == korean }?.let { return it.second }
        val loaded = manifest(context).associate { it.getString("file") to it.getString(if (korean) "title" else "titleEn") }
        titles = korean to loaded
        return loaded
    }

    /** The hoop a bundled sample was made for (manifest hoopMm): 100×100 unless it says 130×180. */
    fun hoop(context: Context, file: File): HoopSpec {
        val mm = manifest(context).firstOrNull { it.getString("file") == file.name }?.optJSONArray("hoopMm")
        return if (mm != null && mm.optInt(0) == 130 && mm.optInt(1) == 180) HoopSpec.H130x180 else HoopSpec.H100
    }

    /** A bundled sample's display name; other files (and samples of an earlier pack) keep their file name. */
    fun title(context: Context, file: File): String =
        if (!DesignLibrary.isSample(file)) file.nameWithoutExtension
        else titles(context)[file.name] ?: file.nameWithoutExtension.removePrefix("sample_").replace('_', ' ')
}
