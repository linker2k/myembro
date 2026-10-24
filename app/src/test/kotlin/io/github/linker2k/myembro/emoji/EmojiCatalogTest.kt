// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.emoji

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.sewout.DesignOrigin
import io.github.linker2k.myembro.engine.sewout.SewoutCheck
import io.github.linker2k.myembro.engine.sewout.SewoutOptions
import io.github.linker2k.myembro.engine.sewout.Severity
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmojiCatalogTest {
    private val catalog = EmojiCatalog.parse(File("src/main/assets/${EmojiCatalog.ASSET}").readText())
    private val bundledDir = File("src/main/assets/${EmojiCatalog.BUNDLED}")
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun bundleIsThirtyVerifiedPicturesUnderOneMegabyteWithLicense() {
        val bundled = catalog.emoji.filter { it.bundled }
        assertEquals(30, bundled.size)
        bundled.forEach { assertEquals(it.sha256, sha(File(bundledDir, "${it.id}.svg")), it.id) }
        assertEquals(bundled.map { "${it.id}.svg" }.toSet() + "LICENSE", bundledDir.list()!!.toSet())
        assertTrue(File(bundledDir, "LICENSE").readText().contains("MIT License"))
        val bytes = bundledDir.listFiles()!!.sumOf { it.length() } + File("src/main/assets/${EmojiCatalog.ASSET}").length()
        assertTrue(bytes < 1_000_000, "bundle + catalog = $bytes bytes")
        assertEquals(catalog.emoji.size, catalog.emoji.map { it.id }.toSet().size)
    }

    /** Every bundled picture must sew: 50mm through the shared vector-art path, P1–P8 writer guard and SewoutCheck. */
    @Test fun everyBundledEmojiPassesTheGateAt50mm() {
        val failing = catalog.emoji.filter { it.bundled }.mapNotNull { e ->
            runCatching {
                val pes = PesWriter.write(VectorArt.render(VectorArt.parseSvg(File(bundledDir, "${e.id}.svg").readText()), 50.0).pattern)
                val report = SewoutCheck.checkPes(pes, options = SewoutOptions(hoop = HoopSpec.H100, origin = DesignOrigin.APP_GENERATED))
                report.findings.filter { it.severity == Severity.BLOCKER }.map { it.code.name }.takeIf { it.isNotEmpty() || !report.analysisComplete }
            }.getOrElse { listOf(it.message.orEmpty().take(60)) }?.let { "${e.id}: $it" }
        }
        assertEquals(emptyList(), failing)
    }

    /** Opt-in (EMOJI_NETWORK=1): real pinned downloads of non-bundled pictures, sha256-checked, then stitched. */
    @Test fun nonBundledEmojiDownloadVerifiedAndStitch() {
        if (System.getenv("EMOJI_NETWORK") != "1") return
        val dir = kotlin.io.path.createTempDirectory("emoji-dl").toFile()
        try {
            for (id in listOf("cactus", "cat", "hamburger")) {
                val e = catalog.emoji.single { it.id == id }
                assertTrue(!e.bundled)
                val out = File(dir, "$id.svg")
                io.github.linker2k.myembro.fonts.FontRepository.fetchPinned(catalog.url(e), out, e.sha256, e.id, e.size) {}
                assertEquals(e.sha256, sha(out))
                val pes = PesWriter.write(VectorArt.render(VectorArt.parseSvg(out.readText()), 50.0).pattern)
                val report = SewoutCheck.checkPes(pes, options = SewoutOptions(hoop = HoopSpec.H100, origin = DesignOrigin.APP_GENERATED))
                println("EMOJI download $id ${out.length()}B sha ok, stitches ${report.statistics.stitchCount}, blockers ${report.findings.count { it.severity == Severity.BLOCKER }}")
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun downloadUrlIsPinnedAndEncoded() {
        val heart = catalog.emoji.single { it.id == "red-heart" }
        assertEquals("https://raw.githubusercontent.com/microsoft/fluentui-emoji/${catalog.commit}/assets/Red%20heart/Flat/red_heart_flat.svg", catalog.url(heart))
    }
}
