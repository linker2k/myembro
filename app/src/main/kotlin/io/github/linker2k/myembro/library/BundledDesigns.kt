// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import android.content.Context
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

    private var titles: Pair<Boolean, Map<String, String>>? = null

    /** manifest.json display names by sample file name, in Korean or English to match the app language. */
    @Synchronized
    fun titles(context: Context): Map<String, String> {
        val korean = context.resources.configuration.locales[0].language == "ko"
        titles?.takeIf { it.first == korean }?.let { return it.second }
        val loaded = runCatching {
            val samples = JSONObject(context.assets.open("samples/manifest.json").bufferedReader().use { it.readText() })
                .getJSONArray("samples")
            (0 until samples.length()).map(samples::getJSONObject).associate {
                it.getString("file") to it.getString(if (korean) "title" else "titleEn")
            }
        }.getOrDefault(emptyMap())
        titles = korean to loaded
        return loaded
    }

    /** A bundled sample's display name; other files (and samples of an earlier pack) keep their file name. */
    fun title(context: Context, file: File): String =
        if (!DesignLibrary.isSample(file)) file.nameWithoutExtension
        else titles(context)[file.name] ?: file.nameWithoutExtension.removePrefix("sample_").replace('_', ' ')
}
