// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import io.github.linker2k.myembro.engine.lettering.FontQualityCache
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchFont
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchFontRegistry
import io.github.linker2k.myembro.engine.lettering.SvgFont
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
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
    /** Optional precomputed quality; otherwise the app analyzes the font after download. */
    val recommendedMinHeightMm: Double? = null,
    val qualityGrade: QualityGrade? = null,
    /** Style tag, e.g. "script" for joined handwriting fonts. */
    val style: String? = null,
    /** Hairline outline font: reads best sewn as a running stitch, not a satin fill. */
    val thinStroke: Boolean = false,
    /** Ink/Stitch fonts: pinned `font.json` (layout metadata) downloaded beside the SVG. */
    val metaUrl: String? = null,
    val metaSha256: String? = null,
    /** Ink/Stitch fonts: the digitizer's largest tested height. */
    val recommendedMaxHeightMm: Double? = null,
    /** Ink/Stitch fonts whose font.json asks for 60 wt thread and a 60 needle. */
    val thinThreadRequired: Boolean = false,
    /** Ink/Stitch fonts: glyph names (NFC) of the font; null when unknown. */
    val glyphs: Set<String>? = null,
    /** Ink/Stitch fonts: font.json's forced case ("upper" sews every letter as a capital). */
    val letterCase: String = "",
    /** Ink/Stitch fonts: theme from font.json keywords (script, serif, sans, display). */
    val theme: String? = null,
    /** Shipped in the APK (assets/fonts/bundled/<id>/): installed offline, never deleted. */
    val bundled: Boolean = false,
) {
    /** Theme used to pick a similar font: the embroidery font's own, else "script" for script TTFs. */
    val themeKey: String? get() = theme?.takeIf { it.isNotEmpty() } ?: if (style == "script") "script" else null

    /**
     * Whether every non-space character of [text] has a glyph, after the font's forced case. Glyph
     * names may be ligatures; a single character is enough for each one here. Unknown sets cover all.
     */
    fun covers(text: String): Boolean {
        val names = glyphs ?: return true
        val cased = when (letterCase) { "upper" -> text.uppercase(); "lower" -> text.lowercase(); else -> text }
        val normalized = java.text.Normalizer.normalize(cased, java.text.Normalizer.Form.NFC)
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            val c = String(Character.toChars(cp))
            if (!Character.isWhitespace(cp) && c !in names) return false
            i += Character.charCount(cp)
        }
        return true
    }

    enum class Kind(val json: String, val extension: String) {
        SINGLE_LINE_SVG("single-line-svg", "svg"),
        OUTLINE_TTF("outline-ttf", "ttf"),
        /** Pre-digitized Ink/Stitch font (docs/inkstitch-fonts.md): satin/fill/running parameters included. */
        INKSTITCH_SVG("inkstitch-svg", "svg"),
    }

    val predigitized: Boolean get() = kind == Kind.INKSTITCH_SVG

    val korean: Boolean get() = "ko" in scripts
    val latin: Boolean get() = "latin" in scripts
    val script: Boolean get() = style == "script"
}

class ChecksumMismatchException(val fontId: String) : IOException("Checksum mismatch for $fontId")

/** Why a user-picked font file was refused. */
class UserFontException(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
    enum class Reason { TOO_LARGE, UNSUPPORTED_TYPE, UNREADABLE, NO_GLYPHS }
}

/**
 * Downloadable font library. Fonts come only from the bundled, curated catalog: each URL is a
 * pinned commit and every byte is checked against the catalog sha256 before it is kept. Files
 * live in `filesDir/fonts/<id>.<ext>` with the license text beside them in `<id>.LICENSE.txt`.
 */
class FontRepository(context: Context) {
    private val appContext = context.applicationContext
    val dir: File = File(appContext.filesDir, "fonts")

    /** Pre-digitized Ink/Stitch fonts first: they are the primary lettering source for Latin text. */
    val catalog: List<CatalogFont> by lazy {
        appContext.assets.open(InkStitchCatalog.ASSET).bufferedReader().use { InkStitchCatalog.parse(it.readText()) } +
            appContext.assets.open(CATALOG_ASSET).bufferedReader().use { parseCatalog(it.readText()) }
    }

    init {
        installInkStitchLoader(appContext)
        installBundled()
    }

    /**
     * Copies the APK's bundled embroidery fonts into the font folder once, verified against the
     * catalog's pinned sha256 like a download, so they work offline from the first launch.
     */
    private fun installBundled() {
        for (font in catalog.filter { it.bundled && !isInstalled(it) }) runCatching {
            dir.mkdirs()
            val base = "$BUNDLED_ASSETS/${font.id}"
            val files = listOf("ltr.svg" to (fontFile(font) to font.sha256), "font.json" to (metaFile(font)!! to font.metaSha256),
                "LICENSE" to (licenseFile(font) to font.licenseSha256))
            for ((name, target) in files) {
                val (file, sha) = target
                val bytes = appContext.assets.open("$base/$name").use { it.readBytes() }
                check(sha == null || sha256(bytes) == sha) { "Bundled ${font.id}/$name does not match the catalog" }
                File(dir, file.name + ".tmp").apply { writeBytes(bytes); check(renameTo(file)) }
            }
        }
    }

    private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun find(id: String): CatalogFont? = catalog.firstOrNull { it.id == id }

    fun fontFile(font: CatalogFont): File = File(dir, "${font.id}.${font.kind.extension}")

    fun licenseFile(font: CatalogFont): File = File(dir, "${font.id}.LICENSE.txt")

    /** Ink/Stitch layout metadata beside the SVG; null for other kinds. */
    fun metaFile(font: CatalogFont): File? = font.metaUrl?.let { File(dir, "${font.id}.font.json") }

    fun isInstalled(font: CatalogFont): Boolean =
        fontFile(font).isFile && licenseFile(font).isFile && metaFile(font)?.isFile != false

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
        val metaTmp = File(dir, "${font.id}.meta.part")
        try {
            fetch(font.url, tmp, font.sha256, font.id, font.size, onProgress)
            font.metaUrl?.let { fetch(it, metaTmp, font.metaSha256, font.id, null) {} }
            val licenseText = if (font.licenseInFont) {
                extractMetadata(tmp.readText()) ?: throw IOException("No license metadata in ${font.id}")
            } else {
                val licTmp = File(dir, "${font.id}.license.part")
                try {
                    fetch(font.licenseUrl, licTmp, font.licenseSha256, font.id, null) {}
                    // EMS fonts ship a generic OFL text; their credits live in the font metadata.
                    val credits = if (font.kind == CatalogFont.Kind.SINGLE_LINE_SVG) extractMetadata(tmp.readText()) else null
                    licTmp.readText() + (credits?.let { "\n\n--- Font metadata ---\n$it\n" } ?: "")
                } finally {
                    licTmp.delete()
                }
            }
            ensureActive()
            license.writeText(licenseHeader(font) + licenseText)
            metaFile(font)?.let { meta -> if (!metaTmp.renameTo(meta)) metaTmp.copyTo(meta, overwrite = true) }
            InkStitchFontRegistry.invalidate(font.id)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
            FontQualityCache.Shared.invalidate(font.id)
            onProgress(1f)
            target
        } catch (e: Throwable) {
            target.delete()
            license.delete()
            metaFile(font)?.delete()
            throw e
        } finally {
            tmp.delete()
            metaTmp.delete()
        }
    }

    fun delete(font: CatalogFont) {
        if (font.bundled) return
        fontFile(font).delete()
        licenseFile(font).delete()
        metaFile(font)?.delete()
        InkStitchFontRegistry.invalidate(font.id)
        FontQualitySummary.qualityFile(fontFile(font)).delete()
        FontQualityCache.Shared.invalidate(font.id)
    }

    // ---- User fonts and lettering lookups ----

    val userDir: File = File(dir, "user")

    fun letterFont(font: CatalogFont): LetterFont {
        val file = fontFile(font)
        return LetterFont(
            id = font.id, name = font.nameKo ?: font.name,
            kind = when (font.kind) {
                CatalogFont.Kind.OUTLINE_TTF -> LetterFont.Kind.OUTLINE
                CatalogFont.Kind.SINGLE_LINE_SVG -> LetterFont.Kind.SINGLE_LINE_SVG
                CatalogFont.Kind.INKSTITCH_SVG -> LetterFont.Kind.INKSTITCH
            },
            file = file, quality = FontQualitySummary.read(file) ?: precomputedQuality(font),
        )
    }

    fun userFonts(): List<LetterFont> = userDir.listFiles { f -> f.name.endsWith(META_SUFFIX) }.orEmpty()
        .mapNotNull { meta ->
            runCatching {
                val o = JSONObject(meta.readText())
                val file = File(userDir, o.getString("file"))
                if (!file.isFile) return@runCatching null
                LetterFont(
                    id = USER_PREFIX + o.getString("id"), name = o.getString("name"),
                    kind = LetterFont.Kind.valueOf(o.getString("kind")), file = file,
                    quality = FontQualitySummary.read(file) ?: runCatching {
                        LetterFontGeometry(appContext).analyze(LetterFont(USER_PREFIX + o.getString("id"), o.getString("name"),
                            LetterFont.Kind.valueOf(o.getString("kind")), file, null, userAdded = true))
                            .also { FontQualitySummary.write(file, it) }
                    }.getOrNull(),
                    userAdded = true,
                ) to o.optLong("addedAt")
            }.getOrNull()
        }
        .sortedBy { it.second }.map { it.first }

    /**
     * Copies a user-picked TTF/OTF/SVG into `filesDir/fonts/user/`, checks it can be parsed and
     * stores its 10/20 mm quality analysis. Throws [UserFontException] with the reason on refusal.
     */
    suspend fun importUserFont(uri: Uri, geometry: LetterFontGeometry): LetterFont = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "font"
        userDir.mkdirs()
        val uuid = UUID.randomUUID().toString()
        val tmp = File(userDir, "$uuid.part")
        var target: File? = null
        try {
            val stream = resolver.openInputStream(uri) ?: throw UserFontException(UserFontException.Reason.UNREADABLE)
            stream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_USER_BYTES) throw UserFontException(UserFontException.Reason.TOO_LARGE)
                        out.write(buf, 0, n)
                    }
                }
            }
            val kind = sniffKind(tmp, displayName) ?: throw UserFontException(UserFontException.Reason.UNSUPPORTED_TYPE)
            val ext = if (kind == LetterFont.Kind.SINGLE_LINE_SVG) "svg" else displayName.substringAfterLast('.', "ttf").lowercase()
                .takeIf { it in setOf("ttf", "otf", "ttc") } ?: "ttf"
            val file = File(userDir, "$uuid.$ext").also { target = it }
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
            val name = displayName.substringBeforeLast('.').ifBlank { displayName }
            val font = LetterFont(USER_PREFIX + uuid, name, kind, file, null, userAdded = true)
            try {
                when (kind) {
                    LetterFont.Kind.SINGLE_LINE_SVG ->
                        if (geometry.svgFont(font).glyphs.values.none { it.strokes.isNotEmpty() }) throw UserFontException(UserFontException.Reason.NO_GLYPHS)
                    else -> geometry.typeface(font)
                }
            } catch (e: UserFontException) { throw e
            } catch (e: Exception) { throw UserFontException(UserFontException.Reason.UNREADABLE, e) }
            val sampleChars = FontQualityGateway.sample(hangul = true) + FontQualityGateway.sample(hangul = false)
            if (geometry.missing(font, sampleChars).size == sampleChars.toSet().size) {
                throw UserFontException(UserFontException.Reason.NO_GLYPHS)
            }
            val quality = geometry.analyze(font)
            FontQualitySummary.write(file, quality)
            File(userDir, uuid + META_SUFFIX).writeText(
                JSONObject().put("id", uuid).put("name", name).put("originalName", displayName)
                    .put("kind", kind.name).put("file", file.name).put("addedAt", System.currentTimeMillis()).toString(2),
            )
            font.copy(quality = quality)
        } catch (e: Throwable) {
            target?.let { FontQualitySummary.qualityFile(it).delete(); it.delete() }
            File(userDir, uuid + META_SUFFIX).delete()
            throw e
        } finally {
            tmp.delete()
        }
    }

    fun deleteUserFont(font: LetterFont) {
        val file = font.file ?: return
        if (!font.userAdded || file.parentFile != userDir) return
        FontQualitySummary.qualityFile(file).delete()
        File(userDir, font.id.removePrefix(USER_PREFIX) + META_SUFFIX).delete()
        file.delete()
        FontQualityCache.Shared.invalidate(font.id)
    }

    /** The catalog's precomputed quality, shown before download; null when the catalog has none. */
    fun precomputedQuality(font: CatalogFont): FontQualitySummary? = font.recommendedMinHeightMm?.let { h ->
        FontQualitySummary(font.qualityGrade ?: QualityGrade.GOOD, h, null, null, emptyList())
    }

    /**
     * Runs and stores the quality analysis of an installed catalog font. Ink/Stitch fonts keep the
     * digitizer's tested range instead: contour analysis does not describe their stitches.
     */
    fun analyzeInstalled(font: CatalogFont, geometry: LetterFontGeometry): FontQualitySummary {
        if (font.predigitized) return checkNotNull(precomputedQuality(font))
        val summary = geometry.analyze(letterFont(font))
        FontQualitySummary.write(fontFile(font), summary)
        return summary
    }

    /** A user font by its `user:` id, without analysis. Null when the id or its files are gone. */
    fun userFont(id: String): LetterFont? {
        if (!id.startsWith(USER_PREFIX)) return null
        val meta = File(userDir, id.removePrefix(USER_PREFIX) + META_SUFFIX)
        if (meta.parentFile != userDir || !meta.isFile) return null
        return runCatching {
            val o = JSONObject(meta.readText())
            val file = File(userDir, o.getString("file")).takeIf { it.isFile && it.parentFile == userDir } ?: return null
            LetterFont(id, o.getString("name"), LetterFont.Kind.valueOf(o.getString("kind")), file, FontQualitySummary.read(file), userAdded = true)
        }.getOrNull()
    }

    fun userTypeface(font: LetterFont): Typeface? = font.file?.let { runCatching { Typeface.Builder(it).build() }.getOrNull() }

    fun userSvgFont(font: LetterFont): SvgFont? = font.file?.let { runCatching { SvgFont.parse(it.readText()) }.getOrNull() }

    /** Cache key part for lettering quality: changes whenever the user replaces the file. */
    fun userRevision(font: LetterFont): String = font.file?.let { "user-${it.length()}-${it.lastModified()}" } ?: "user"

    private fun sniffKind(file: File, name: String): LetterFont.Kind? {
        val head = ByteArray(512)
        val n = file.inputStream().use { it.read(head) }.coerceAtLeast(0)
        val magic = String(head, 0, minOf(4, n), Charsets.ISO_8859_1)
        if (magic == "\u0000\u0001\u0000\u0000" || magic == "OTTO" || magic == "true" || magic == "ttcf") return LetterFont.Kind.OUTLINE
        val text = String(head, 0, n, Charsets.UTF_8)
        if (name.endsWith(".svg", ignoreCase = true) || text.contains("<svg") || text.trimStart().startsWith("<?xml")) {
            return LetterFont.Kind.SINGLE_LINE_SVG
        }
        return null
    }

    fun licenseText(font: CatalogFont): String? = licenseFile(font).takeIf { it.isFile }?.readText()

    /** Typeface of an installed outline font, or null. Feed it to AndroidGlyphOutlines. */
    fun typeface(font: CatalogFont): Typeface? {
        if (font.kind != CatalogFont.Kind.OUTLINE_TTF || !isInstalled(font)) return null
        return runCatching { Typeface.createFromFile(fontFile(font)) }.getOrNull()
    }

    /** Parsed Ink/Stitch font of an installed entry (cached by id), or null. Up to a second for large fonts. */
    fun inkStitchFont(font: CatalogFont): InkStitchFont? {
        if (font.kind != CatalogFont.Kind.INKSTITCH_SVG || !isInstalled(font)) return null
        return InkStitchFontRegistry[font.id]
    }

    /** Parsed single-line font of an installed SVG font, or null. Glyph strokes feed LetteringEngine. */
    fun svgFont(font: CatalogFont): SvgFont? {
        if (font.kind != CatalogFont.Kind.SINGLE_LINE_SVG || !isInstalled(font)) return null
        return runCatching { SvgFont.parse(fontFile(font).readText()) }.getOrNull()
    }

    /** Pinned https download checked against [sha256]; also used by [InkStitchFonts]. */
    internal fun fetch(
        url: String, out: File, sha256: String?, id: String, expectedSize: Long?, onProgress: (Float) -> Unit,
    ) = fetchPinned(url, out, sha256, id, expectedSize, onProgress)

    private fun licenseHeader(font: CatalogFont) = buildString {
        append(font.name).append('\n')
        append("License: ").append(font.license).append('\n')
        append("Designer: ").append(font.designer).append('\n')
        append("Source: ").append(font.url).append('\n')
        append("License source: ").append(font.licenseUrl).append("\n\n")
    }

    companion object {
        /** Pinned https download checked against [sha256] (fonts, Ink/Stitch fonts, emoji). */
        internal fun fetchPinned(
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

        const val CATALOG_ASSET = "fonts/catalog.json"
        const val BUNDLED_ASSETS = "fonts/bundled"

        /** Lets saved Ink/Stitch text objects find installed fonts by id. Idempotent. */
        fun installInkStitchLoader(context: Context) {
            if (InkStitchFontRegistry.loader != null) return
            val app = context.applicationContext
            InkStitchFontRegistry.loader = { id ->
                val repo = FontRepository(app)
                repo.find(id)?.takeIf { it.kind == CatalogFont.Kind.INKSTITCH_SVG && repo.isInstalled(it) }?.let { font ->
                    runCatching {
                        repo.fontFile(font).inputStream().buffered().use { InkStitchFont.parse(it, repo.metaFile(font)!!.readText()) }
                    }.getOrNull()
                }
            }
        }
        const val USER_PREFIX = "user:"
        private const val META_SUFFIX = ".meta.json"
        private const val MAX_BYTES = 32L * 1024 * 1024
        private const val MAX_USER_BYTES = 32L * 1024 * 1024

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
                    recommendedMinHeightMm = o.optDouble("recommendedMinHeightMm").takeUnless { it.isNaN() },
                    qualityGrade = o.optString("qualityGrade").ifEmpty { null }?.let { QualityGrade.valueOf(it) },
                    style = o.optString("style").ifEmpty { null },
                    thinStroke = o.optBoolean("thinStroke"),
                )
            }
        }

        internal fun extractMetadata(svg: String): String? =
            Regex("<metadata>(.*?)</metadata>", RegexOption.DOT_MATCHES_ALL).find(svg)?.groupValues?.get(1)?.trim()

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
