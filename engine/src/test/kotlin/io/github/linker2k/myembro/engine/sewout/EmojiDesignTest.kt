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

    /**
     * Overlap removal (JTS difference of everything drawn later): stitches, peak 0.5mm-cell density
     * and penetrations of earlier threads inside later shapes, with and without it.
     */
    @Test fun overlapRemovalTable() {
        for (name in names) {
            val art = VectorArt.parseSvg(File("src/test/resources/emoji/fluent/${name}_flat.svg").readText())
            val rows = listOf(false, true).map { remove ->
                val result = VectorArt.render(art, 50.0, name = name, removeOverlaps = remove)
                val pes = PesWriter.write(result.pattern)
                val stats = SewoutCheck.checkPes(pes, options = SewoutOptions(hoop = hoop, origin = DesignOrigin.APP_GENERATED)).statistics
                Triple(remove, stats, buried(result.pattern))
            }
            rows.forEach { (remove, s, b) -> println("OVERLAP | $name | ${if (remove) "제거 후" else "제거 전"} | ${s.stitchCount} | ${s.peakCellPenetrations} | ${s.densityHotspotCount} | $b |") }
            val (before, after) = rows
            assertTrue(after.second.stitchCount <= before.second.stitchCount && after.third <= before.third)
        }
    }

    /** Needle penetrations of each color block that land on a cell later re-sewn by a different color. */
    private fun buried(pattern: io.github.linker2k.myembro.engine.model.EmbPattern): Int {
        val cells = HashMap<Pair<Int, Int>, MutableSet<Int>>(); var block = 0
        val owners = ArrayList<Pair<Pair<Int, Int>, Int>>()
        for (s in pattern.stitches) when (s.command) {
            io.github.linker2k.myembro.engine.model.Command.COLOR_CHANGE -> block++
            io.github.linker2k.myembro.engine.model.Command.STITCH -> { val c = Math.floorDiv(s.x, 5) to Math.floorDiv(s.y, 5)
                cells.getOrPut(c) { mutableSetOf() } += block; owners += c to block }
            else -> Unit
        }
        return owners.count { (c, b) -> cells.getValue(c).any { it > b } }
    }

    /** Opt-in: EMOJI_CANDIDATES=<dir of .svg>. Gate verdict per candidate at 50mm, for picking the bundle. */
    @Test fun candidateSurvey() {
        val dir = System.getenv("EMOJI_CANDIDATES")?.let(::File) ?: return
        for (f in dir.listFiles { x -> x.extension == "svg" }.orEmpty().sortedBy { it.name }) {
            val verdict = runCatching {
                val result = VectorArt.render(VectorArt.parseSvg(f.readText()), 50.0)
                val pes = PesWriter.write(result.pattern)
                val report = SewoutCheck.checkPes(pes, options = SewoutOptions(hoop = hoop, origin = DesignOrigin.APP_GENERATED))
                val bad = PesInvariants.violations(pes, hoop) + report.findings.filter { it.severity == Severity.BLOCKER }.map { "${it.code}" }
                System.getenv("EMOJI_OUT")?.let(::File)?.let { out ->
                    out.mkdirs(); File(out, "${f.nameWithoutExtension.uppercase().replace("_", "").take(8)}.PES").writeBytes(pes)
                    ImageIO.write(DesignPreview.sideBySide(VectorArt.parseSvg(f.readText()), pes), "png", File(out, "${f.nameWithoutExtension}.png"))
                }
                "${if (bad.isEmpty() && report.analysisComplete) "PASS" else "FAIL $bad"} stitches ${report.statistics.stitchCount} colors ${result.pattern.threads.size}"
            }.getOrElse { "ERROR ${it.javaClass.simpleName}: ${it.message?.take(80)}" }
            println("CANDIDATE ${f.nameWithoutExtension} $verdict")
        }
    }

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
