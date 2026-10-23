// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import android.content.Context
import io.github.linker2k.myembro.engine.lettering.InkStitchFont
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One entry of `assets/fonts/inkstitch-catalog.json`: a pre-digitized Ink/Stitch font (3 pinned files). */
data class InkStitchCatalogFont(
    val id: String,
    val name: String,
    val license: String,
    val designer: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val metaUrl: String,
    val metaSha256: String,
    val licenseUrl: String,
    val licenseSha256: String,
    /** The digitizer's tested range ('H' ink height at font.json min_scale/max_scale). */
    val recommendedMinHeightMm: Double,
    val recommendedMaxHeightMm: Double,
    val qualityGrade: QualityGrade,
    /** font.json asks for 60 wt thread and a 60 needle. */
    val thinThreadRequired: Boolean,
)

/**
 * Downloadable Ink/Stitch embroidery fonts, kept apart from [FontRepository]'s TTF/SVG catalog:
 * they carry their own satin/fill parameters and are sewn by the engine's InkStitchLettering.
 * Files: `filesDir/fonts/inkstitch/<id>.svg`, `<id>.font.json` and `<id>.LICENSE.txt`.
 */
class InkStitchFonts(context: Context, private val repository: FontRepository) {
    private val appContext = context.applicationContext
    val dir: File = File(repository.dir, "inkstitch")

    val catalog: List<InkStitchCatalogFont> by lazy {
        appContext.assets.open(CATALOG_ASSET).bufferedReader().use { parseCatalog(it.readText()) }
    }

    fun find(id: String): InkStitchCatalogFont? = catalog.firstOrNull { it.id == id }
    fun svgFile(font: InkStitchCatalogFont) = File(dir, "${font.id}.svg")
    fun metaFile(font: InkStitchCatalogFont) = File(dir, "${font.id}.font.json")
    fun licenseFile(font: InkStitchCatalogFont) = File(dir, "${font.id}.LICENSE.txt")
    fun isInstalled(font: InkStitchCatalogFont) = svgFile(font).isFile && metaFile(font).isFile && licenseFile(font).isFile

    /** Downloads and verifies all three files; on any failure nothing partial is left behind. */
    suspend fun download(font: InkStitchCatalogFont, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val parts = listOf(File(dir, "${font.id}.svg.part"), File(dir, "${font.id}.json.part"), File(dir, "${font.id}.license.part"))
        try {
            repository.fetch(font.url, parts[0], font.sha256, font.id, font.size, onProgress)
            repository.fetch(font.metaUrl, parts[1], font.metaSha256, font.id, null) {}
            repository.fetch(font.licenseUrl, parts[2], font.licenseSha256, font.id, null) {}
            ensureActive()
            licenseFile(font).writeText(
                "${font.name}\nLicense: ${font.license}\nDesigner: ${font.designer}\nSource: ${font.url}\n" +
                    "License source: ${font.licenseUrl}\n\n" + parts[2].readText(),
            )
            moveOver(parts[1], metaFile(font))
            moveOver(parts[0], svgFile(font))
            onProgress(1f)
            svgFile(font)
        } catch (e: Throwable) {
            delete(font)
            throw e
        } finally {
            parts.forEach { it.delete() }
        }
    }

    fun delete(font: InkStitchCatalogFont) {
        svgFile(font).delete(); metaFile(font).delete(); licenseFile(font).delete()
    }

    /** Parsed font of an installed entry, or null. Parsing a large font takes up to a second: call off the main thread. */
    fun load(font: InkStitchCatalogFont): InkStitchFont? {
        if (!isInstalled(font)) return null
        return runCatching { svgFile(font).inputStream().buffered().use { InkStitchFont.parse(it, metaFile(font).readText()) } }.getOrNull()
    }

    private fun moveOver(from: File, to: File) {
        if (!from.renameTo(to)) { from.copyTo(to, overwrite = true) }
        if (!to.isFile) throw IOException("Could not store ${to.name}")
    }

    companion object {
        const val CATALOG_ASSET = "fonts/inkstitch-catalog.json"

        fun parseCatalog(json: String): List<InkStitchCatalogFont> =
            Json.parseToJsonElement(json).jsonObject.getValue("fonts").jsonArray.map { element ->
                val o = element.jsonObject
                fun s(key: String) = o.getValue(key).jsonPrimitive.content
                fun d(key: String) = o.getValue(key).jsonPrimitive.doubleOrNull ?: error("$key is not a number")
                val url = s("url"); val metaUrl = s("metaUrl"); val licenseUrl = s("licenseUrl")
                require(listOf(url, metaUrl, licenseUrl).all { it.startsWith("https://") }) { "Only https downloads: ${s("id")}" }
                InkStitchCatalogFont(
                    id = s("id"), name = s("name"), license = s("license"), designer = (o["designer"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
                    url = url, sha256 = s("sha256"), size = o.getValue("size").jsonPrimitive.longOrNull ?: 0L,
                    metaUrl = metaUrl, metaSha256 = s("metaSha256"), licenseUrl = licenseUrl, licenseSha256 = s("licenseSha256"),
                    recommendedMinHeightMm = d("recommendedMinHeightMm"), recommendedMaxHeightMm = d("recommendedMaxHeightMm"),
                    qualityGrade = QualityGrade.valueOf(s("qualityGrade")),
                    thinThreadRequired = (o["thinThreadRequired"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false,
                )
            }
    }
}
