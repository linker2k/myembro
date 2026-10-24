// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InkStitchCatalogTest {
    private val fonts = InkStitchCatalog.parse(File("src/main/assets/${InkStitchCatalog.ASSET}").readText())

    @Test fun everyFileIsPinnedAndHashed() {
        // Only fonts that sew across their whole recorded height range with the shared ports are listed.
        assertTrue(fonts.size >= 40)
        val commit = Regex("^https://raw\\.githubusercontent\\.com/inkstitch/embroidery-fonts/[0-9a-f]{40}/src/")
        for (f in fonts) {
            assertTrue(listOf(f.url, f.metaUrl!!, f.licenseUrl).all { commit.containsMatchIn(it) }, f.id)
            assertTrue(listOf(f.sha256, f.metaSha256!!, f.licenseSha256!!).all { it.matches(Regex("[0-9a-f]{64}")) }, f.id)
            assertTrue(f.size in 1..(32L shl 20), f.id)
        }
        assertTrue(fonts.map { it.id }.toSet().size == fonts.size)
    }

    @Test fun onlyRedistributableLicenses() {
        val allowed = setOf("OFL-1.1", "GPL-3.0", "Public domain / CC0", "CC BY 4.0", "CC BY-SA 4.0", "CC BY-SA 2.5")
        assertTrue(fonts.all { it.license in allowed }, fonts.map { it.license }.toSet().toString())
    }

    @Test fun heightRangesAreSane() {
        for (f in fonts) {
            assertTrue(f.recommendedMinHeightMm!! > 0 && f.recommendedMinHeightMm!! <= f.recommendedMaxHeightMm!!, f.id)
            assertTrue(f.recommendedMinHeightMm!! >= 6.0 || f.qualityGrade == QualityGrade.CAUTION, f.id)
        }
    }

    @Test fun glyphSetsDecideWhichTextAFontCanSew() {
        assertTrue(fonts.all { !it.glyphs.isNullOrEmpty() })
        // No Ink/Stitch font has Hangul (docs/inkstitch-fonts.md).
        assertTrue(fonts.none { it.covers("자수") })
        assertTrue(fonts.count { it.covers("Jung Min") } >= 40)
        // Forced capitals: lowercase text is sewn in capitals, so it fits.
        val upper = fonts.first { it.letterCase == "upper" }
        assertTrue(upper.covers("abc") && upper.glyphs!!.none { it == "a" })
    }

    @Test fun everyFontHasABundledStitchPreview() {
        fonts.forEach { assertTrue(File("src/main/assets/fonts/previews/${it.id}.png").isFile, it.id) }
    }

    @Test fun bundledFontsAreOnePerThemeVerifiedAndMatchTheCatalog() {
        val bundled = fonts.filter { it.bundled }
        assertEquals(5, bundled.size)
        assertEquals(setOf("script", "serif", "sans", "display"), bundled.map { it.theme }.toSet())
        val md = java.security.MessageDigest.getInstance("SHA-256")
        fun sha(f: File) = md.digest(f.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        var total = 0L
        for (f in bundled) {
            val dir = File("src/main/assets/fonts/bundled/${f.id}")
            assertEquals(f.sha256, sha(File(dir, "ltr.svg")), f.id)
            assertEquals(f.metaSha256, sha(File(dir, "font.json")), f.id)
            assertEquals(f.licenseSha256, sha(File(dir, "LICENSE")), f.id)
            total += dir.listFiles()!!.sumOf { it.length() }
        }
        // Raw size; SVG deflates about 6x in the APK.
        assertTrue(total < 3_200_000, "$total bytes")
    }
}
