// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.io.PesWriter
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Representative Fluent Emoji Flat pictures (MIT, test resources) through the shared vector-art
 * path at 50mm: AGENTS.md P1–P8 and SewoutCheck blockers. EMOJI_OUT=<dir> writes PES + review PNGs.
 */
class EmojiDesignTest {
    private val hoop = HoopSpec.H100
    private val names = listOf("red_heart", "star", "grinning_face")

    @Test fun representativeEmojiPassInvariantsAndGate() {
        val failures = ArrayList<String>()
        var satinParts = 0
        for (name in names) {
            val art = VectorArt.parseSvg(File("src/test/resources/emoji/fluent/${name}_flat.svg").readText())
            val result = VectorArt.render(art, 50.0, name = name)
            val pes = PesWriter.write(result.pattern)
            val report = SewoutCheck.checkPes(pes, options = SewoutOptions(hoop = hoop, origin = DesignOrigin.APP_GENERATED))
            val blockers = report.findings.filter { it.severity == Severity.BLOCKER }.map { "${it.code}" }
            satinParts += result.parts.count { it.method == StitchTypeRule.Type.SATIN }
            println("EMOJI $name stitches ${report.statistics.stitchCount} colors ${result.pattern.threads.size} blockers $blockers " +
                result.parts.joinToString { "${it.shape}:${it.method}(max ${"%.1f".format(it.widths?.maxMm ?: 0.0)})" })
            failures += PesInvariants.violations(pes, hoop).map { "$name $it" } + blockers.map { "$name $it" }
            if (!report.analysisComplete) failures += "$name analysis incomplete"
            System.getenv("EMOJI_OUT")?.let(::File)?.let { out ->
                out.mkdirs()
                File(out, "${name.uppercase().take(8)}.PES").writeBytes(pes)
                ImageIO.write(DesignPreview.sideBySide(art, pes), "png", File(out, "$name.png"))
            }
        }
        assertEquals(emptyList(), failures)
        assertTrue(satinParts > 0, "the grinning face's eyes and mouth are narrow areas: some satin is expected")
    }
}
