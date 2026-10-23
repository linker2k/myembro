// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.test.Test

/**
 * Regenerates the precomputed quality in the app's `assets/fonts/catalog.json` (docs/lettering.md).
 * Runs only when MYEMBRO_FONT_CACHE names a directory: font files are cached there by sha256,
 * downloaded from their pinned URLs when missing and checked against the catalog's sha256.
 *
 *     MYEMBRO_FONT_CACHE=/tmp/fonts ./gradlew :engine:test --tests '*CatalogQualityTool*'
 *
 * Rewrites recommendedMinHeightMm and qualityGrade of every font in place and writes the
 * measurements to `quality.tsv` in the cache directory.
 */
class CatalogQualityTool {
    @Test
    fun regenerate() {
        val cache = System.getenv("MYEMBRO_FONT_CACHE")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val catalogFile = File("../app/src/main/assets/fonts/catalog.json")
        val rows = mutableListOf("id\tname\tkind\tscript\tink10\tgap10\tloop10\trecommended\tlimit\tgrade")
        // Entries are flat objects; only their two quality fields are replaced, the rest stays byte for byte.
        val updated = ENTRY.replace(catalogFile.readText()) { match ->
            val entry = match.value
            fun field(key: String) = Regex("\"$key\": \"([^\"]*)\"").find(entry)!!.groupValues[1]
            val file = fetch(cache, field("url"), field("sha256"))
            val a = when (field("kind")) {
                "outline-ttf" -> AwtGlyphs.analyze(file)
                "single-line-svg" -> AwtGlyphs.analyzeSvg(SvgFont.parse(file.readText()))
                else -> error("unknown kind ${field("kind")}")
            }
            check(a.recommendedMinHeightMm.isFinite() && a.recommendedMinHeightMm > 0) { "${field("id")}: ${a.recommendedMinHeightMm}" }
            rows += listOf(
                field("id"), field("name"), field("kind"), if (a.hangul) "ko" else "latin", mm(a.at10.inkP10Mm), mm(a.at10.gapP10Mm),
                mm(a.at10.loopP10Mm), mm(a.recommendedMinHeightMm), a.at10.limitedBy.name, a.grade.name,
            ).joinToString("\t")
            QUALITY.replace(entry, "").replace(Regex("\n    }$"),
                ",\n      \"recommendedMinHeightMm\": ${a.recommendedMinHeightMm},\n      \"qualityGrade\": \"${a.grade.name}\"\n    }")
        }
        catalogFile.writeText(updated)
        File(cache, "quality.tsv").writeText(rows.joinToString("\n") + "\n")
    }

    private fun mm(v: Double?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"

    private fun fetch(cache: File, url: String, sha256: String): File {
        val file = File(cache, sha256)
        if (!file.isFile) {
            val part = File(cache, "$sha256.part")
            URL(url).openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            check(part.renameTo(file))
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == sha256) { "$url: sha256 $actual" }
        return file
    }

    private companion object {
        val ENTRY = Regex("\n    \\{\n[^{}]*\n    }")
        val QUALITY = Regex(",\n      \"(recommendedMinHeightMm|qualityGrade)\": [^,\n]*")
    }
}
