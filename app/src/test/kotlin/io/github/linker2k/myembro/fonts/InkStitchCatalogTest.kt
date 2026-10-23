// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.fonts

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class InkStitchCatalogTest {
    private val fonts = InkStitchCatalog.parse(File("src/main/assets/${InkStitchCatalog.ASSET}").readText())

    @Test fun everyFileIsPinnedAndHashed() {
        assertTrue(fonts.size >= 50)
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
        assertTrue(fonts.count { it.covers("Jung Min") } > 40)
        // Forced capitals: lowercase text is sewn in capitals, so it fits.
        val upper = fonts.first { it.letterCase == "upper" }
        assertTrue(upper.covers("abc") && upper.glyphs!!.none { it == "a" })
    }

    @Test fun everyFontHasABundledStitchPreview() {
        fonts.forEach { assertTrue(File("src/main/assets/fonts/previews/${it.id}.png").isFile, it.id) }
    }
}
