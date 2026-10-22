// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import android.content.Context
import android.graphics.Typeface
import io.github.linker2k.myembro.engine.lettering.SvgFont
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One entry of `assets/fonts/catalog.json`. */
data class CatalogFont(
    val id: String,
    val name: String,
    val nameKo: String?,
    val kind: Kind,
    val scripts: List<String>,
    val license: String,
    val designer: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val licenseUrl: String,
    val licenseSha256: String?,
    /** The license is the font file's own `<metadata>` (Hershey fonts). */
    val licenseInFont: Boolean,
) {
    enum class Kind(val json: String, val extension: String) {
        SINGLE_LINE_SVG("single-line-svg", "svg"),
        OUTLINE_TTF("outline-ttf", "ttf"),
    }

    val korean: Boolean get() = "ko" in scripts
    val latin: Boolean get() = "latin" in scripts
}

class ChecksumMismatchException(val fontId: String) : IOException("Checksum mismatch for $fontId")

/**
 * Downloadable font library. Fonts come only from the bundled, curated catalog: each URL is a
 * pinned commit and every byte is checked against the catalog sha256 before it is kept. Files
 * live in `filesDir/fonts/<id>.<ext>` with the license text beside them in `<id>.LICENSE.txt`.
 */
class FontRepository(context: Context) {
    private val appContext = context.applicationContext
    val dir: File = File(appContext.filesDir, "fonts")

    val catalog: List<CatalogFont> by lazy {
        appContext.assets.open(CATALOG_ASSET).bufferedReader().use { parseCatalog(it.readText()) }
    }

    fun find(id: String): CatalogFont? = catalog.firstOrNull { it.id == id }

    fun fontFile(font: CatalogFont): File = File(dir, "${font.id}.${font.kind.extension}")

    fun licenseFile(font: CatalogFont): File = File(dir, "${font.id}.LICENSE.txt")

    fun isInstalled(font: CatalogFont): Boolean = fontFile(font).isFile && licenseFile(font).isFile

    fun installedIds(): Set<String> = catalog.filter(::isInstalled).map { it.id }.toSet()

    /**
     * Downloads and verifies [font] and its license. [onProgress] gets 0..1 for the font file.
     * Returns the installed font file; on any failure nothing partial is left behind.
     */
    suspend fun download(font: CatalogFont, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = fontFile(font)
        val license = licenseFile(font)
        val tmp = File(dir, "${font.id}.part")
        try {
            fetch(font.url, tmp, font.sha256, font.id, font.size, onProgress)
            val licenseText = if (font.licenseInFont) {
                extractMetadata(tmp.readText()) ?: throw IOException("No license metadata in ${font.id}")
            } else {
                val licTmp = File(dir, "${font.id}.license.part")
                try {
                    fetch(font.licenseUrl, licTmp, font.licenseSha256, font.id, null) {}
                    licTmp.readText()
                } finally {
                    licTmp.delete()
                }
            }
            ensureActive()
            license.writeText(licenseHeader(font) + licenseText)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
            onProgress(1f)
            target
        } catch (e: Throwable) {
            target.delete()
            license.delete()
            throw e
        } finally {
            tmp.delete()
        }
    }

    fun delete(font: CatalogFont) {
        fontFile(font).delete()
        licenseFile(font).delete()
    }

    fun licenseText(font: CatalogFont): String? = licenseFile(font).takeIf { it.isFile }?.readText()

    /** Typeface of an installed outline font, or null. Feed it to AndroidGlyphOutlines. */
    fun typeface(font: CatalogFont): Typeface? {
        if (font.kind != CatalogFont.Kind.OUTLINE_TTF || !isInstalled(font)) return null
        return runCatching { Typeface.createFromFile(fontFile(font)) }.getOrNull()
    }

    /** Parsed single-line font of an installed SVG font, or null. Glyph strokes feed LetteringEngine. */
    fun svgFont(font: CatalogFont): SvgFont? {
        if (font.kind != CatalogFont.Kind.SINGLE_LINE_SVG || !isInstalled(font)) return null
        return runCatching { SvgFont.parse(fontFile(font).readText()) }.getOrNull()
    }

    private fun fetch(
        url: String, out: File, sha256: String?, id: String, expectedSize: Long?, onProgress: (Float) -> Unit,
    ) {
        require(url.startsWith("https://")) { "Only https downloads are allowed: $url" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "myembro")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode} for $url")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize ?: -1L
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > MAX_BYTES) throw IOException("Download too large: $url")
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                        if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(0.99f))
                    }
                }
            }
            if (sha256 != null && hex(digest.digest()) != sha256.lowercase()) {
                throw ChecksumMismatchException(id)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun licenseHeader(font: CatalogFont) = buildString {
        append(font.name).append('\n')
        append("License: ").append(font.license).append('\n')
        append("Designer: ").append(font.designer).append('\n')
        append("Source: ").append(font.url).append('\n')
        append("License source: ").append(font.licenseUrl).append("\n\n")
    }

    companion object {
        const val CATALOG_ASSET = "fonts/catalog.json"
        private const val MAX_BYTES = 32L * 1024 * 1024

        fun parseCatalog(json: String): List<CatalogFont> {
            val fonts = JSONObject(json).getJSONArray("fonts")
            return (0 until fonts.length()).map { i ->
                val o = fonts.getJSONObject(i)
                val kindName = o.getString("kind")
                val scripts = o.getJSONArray("scripts")
                CatalogFont(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    nameKo = o.optString("nameKo").ifEmpty { null },
                    kind = CatalogFont.Kind.entries.firstOrNull { it.json == kindName }
                        ?: throw IllegalArgumentException("Unknown font kind $kindName"),
                    scripts = (0 until scripts.length()).map { scripts.getString(it) },
                    license = o.getString("license"),
                    designer = o.optString("designer"),
                    url = o.getString("url"),
                    sha256 = o.getString("sha256"),
                    size = o.optLong("size"),
                    licenseUrl = o.getString("licenseUrl"),
                    licenseSha256 = o.optString("licenseSha256").ifEmpty { null },
                    licenseInFont = o.optBoolean("licenseInFont"),
                )
            }
        }

        internal fun extractMetadata(svg: String): String? =
            Regex("<metadata>(.*?)</metadata>", RegexOption.DOT_MATCHES_ALL).find(svg)?.groupValues?.get(1)?.trim()

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
