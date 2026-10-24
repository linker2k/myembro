// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoRasterFilters
import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoStyle
import io.github.linker2k.myembro.engine.inkstitch.svg.image.RedworkPipeline
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.DesignOrigin
import io.github.linker2k.myembro.engine.sewout.SewoutCheck
import io.github.linker2k.myembro.engine.sewout.SewoutOptions
import io.github.linker2k.myembro.engine.sewout.Severity
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Photo size range evidence: original synthetic drawings (no photos or personal designs) traced
 * and stitched the way [PhotoViewModel] does at each width, written as the app's default PES and
 * judged by [SewoutCheck] as app output in the 100mm hoop. A case passes with zero blockers.
 */
internal object PhotoSizeCases {
    val hoop = HoopSpec.H100
    private val thread = EmbThread(0xFFB5492A.toInt())

    /** Eight drawings that scale with the picture: solids, rings, strokes and isolated dots. */
    val fixtures: Map<String, (Double, Double) -> Boolean> = mapOf(
        "disk" to { x, y -> hypot(x - .5, y - .5) < .35 },
        "square" to { x, y -> abs(x - .5) < .3 && abs(y - .5) < .3 },
        "ring" to { x, y -> hypot(x - .5, y - .5).let { it < .38 && it > .3 } },
        "triangle" to { x, y -> y in .2..0.8 && abs(x - .5) < (y - .2) * .5 },
        "cross" to { x, y -> (abs(x - .5) < .06 || abs(y - .5) < .06) && abs(x - .5) < .4 && abs(y - .5) < .4 },
        "letter-h" to { x, y -> y in .2..0.8 && (x in .25..0.37 || x in .63..0.75 || (x in .25..0.75 && abs(y - .5) < .05)) },
        "diagonal" to { x, y -> abs(x - y) < .02 && x in .1..0.9 },
        "dots" to { x, y -> (1..4).any { i -> hypot(x - i * .2, y - (if (i % 2 == 0) .3 else .7)) < .03 } },
    )

    /** One case, as PhotoViewModel.prepare + generateResult (square 100mm frame, median3 denoise). */
    fun passes(fixture: String, style: PhotoStyle, detail: Int, widthMm: Double): Boolean = verdict(fixture, style, detail, widthMm) == null

    /** null = pass; otherwise the blocker codes or the generation failure. */
    fun verdict(fixture: String, style: PhotoStyle, detail: Int, widthMm: Double): String? = runCatching {
        val edge = photoRasterSize(detail, widthMm, widthMm)
        val draw = fixtures.getValue(fixture)
        val rgb = IntArray(edge * edge) { i -> if (draw((i % edge + .5) / edge, (i / edge + .5) / edge)) 0 else 0xFFFFFF }
        val traced = RedworkPipeline.trace(edge, edge, PhotoRasterFilters.median3(edge, edge, rgb), widthMm, 0, thread,
            minAreaMm2 = photoMinimumArea(detail), maxContours = 128)
        val pes = PesWriter.write(RedworkPipeline.generate(traced, thread, photoMode = true, style = style).pattern)
        val report = SewoutCheck.checkPes(pes, options = SewoutOptions(hoop = hoop, origin = DesignOrigin.APP_GENERATED))
        report.findings.filter { it.severity == Severity.BLOCKER }.map { it.code.name }.toSet()
            .let { if (!report.analysisComplete) it + "INCOMPLETE" else it }.takeIf { it.isNotEmpty() }?.joinToString(",")
    }.getOrElse { "ERROR:${it.javaClass.simpleName}:${it.message.orEmpty().take(60)}" }
}

class PhotoSizeSweep {
    /**
     * Opt-in: PHOTO_SIZE_SWEEP=<out dir>. Every fixture × style × detail at 5mm steps up to the hoop
     * maximum; the minimum is the smallest width from which every measured width passes.
     */
    @Test fun sweep() {
        val out = System.getenv("PHOTO_SIZE_SWEEP")?.let(::File) ?: return
        val max = photoMaxWidthMm(PhotoSizeCases.hoop)
        val widths = (generateSequence(5.0) { it + 5 }.takeWhile { it < max }.toList() + max)
        val cases = PhotoSizeCases.fixtures.keys.flatMap { f -> PhotoStyle.entries.flatMap { s -> (0..2).map { d -> Triple(f, s, d) } } }
        val pool = Executors.newFixedThreadPool(8)
        val verdicts = cases.associateWith { (f, s, d) -> widths.map { w -> pool.submit<String?> { PhotoSizeCases.verdict(f, s, d, w) } } }
            .mapValues { (_, futures) -> futures.map { it.get() } }
        val results = verdicts.mapValues { (_, v) -> v.map { it == null } }
        pool.shutdown()
        val failing = widths.indices.filter { i -> results.values.any { !it[i] } }
        val minimum = widths[(failing.maxOrNull() ?: -1) + 1]
        val rows = results.map { (c, pass) -> "${c.first}\t${c.second}\t${c.third}\t" + pass.joinToString("") { if (it) "o" else "x" } }
        out.mkdirs()
        File(out, "photo-size-sweep.tsv").writeText((listOf("widthsMm\t" + widths.joinToString(",")) + rows).joinToString("\n") + "\n")
        println("photo size sweep: ${cases.size * widths.size} cases, failing widths ${failing.map { widths[it] }}, minimum $minimum mm")
        rows.forEach(::println)
        verdicts.forEach { (c, v) -> v.forEachIndexed { i, r -> if (r != null) println("fail ${c.first} ${c.second} ${c.third} ${widths[i]}mm $r") } }
    }

    /** The slider ends: every fixture × style × detail passes at both; 5mm below the minimum does not. */
    @Test fun sliderEndsPassAndTheMinimumIsTight() {
        val pool = Executors.newFixedThreadPool(8)
        fun failing(widthMm: Double) = PhotoSizeCases.fixtures.keys.flatMap { f -> PhotoStyle.entries.flatMap { s -> (0..2).map { d ->
            pool.submit<String?> { PhotoSizeCases.verdict(f, s, d, widthMm)?.let { "$f $s $d: $it" } } } } }.mapNotNull { it.get() }
        try {
            assertEquals(emptyList(), failing(PHOTO_MIN_WIDTH_MM))
            assertEquals(emptyList(), failing(photoMaxWidthMm(PhotoSizeCases.hoop)))
            assertTrue(failing(PHOTO_MIN_WIDTH_MM - 5).isNotEmpty())
        } finally { pool.shutdown() }
    }

    @Test fun maximumIsTheHoopInteger999Range() {
        assertEquals(99.9, photoMaxWidthMm(HoopSpec.H100))
        // Non-square frames: the height (width / aspect) must also stay within 0..(units-1).
        val wide = HoopSpec("wide", 1800, 1300)
        assertEquals(true, photoMaxWidthMm(wide) * 1300 / 1800 <= 129.9 + 1e-9)
    }
}
