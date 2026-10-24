// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import kotlinx.serialization.json.*
import kotlin.test.*

class ThreadWeightTest {
    private fun bar(width: Double, height: Double = 10.0) = listOf(
        -width / 2 to 0.0, width / 2 to 0.0, width / 2 to height, -width / 2 to height, -width / 2 to 0.0)
    private fun outline(height: Double = 10.0, strategy: FillStrategy = FillStrategy.AUTO) = TextObject(
        "glyph", text = "I", fontKind = FontKind.OUTLINE, contours = listOf(bar(2.0)),
        contourSourceHeight = 10.0, heightMm = height, stitchStyle = StitchStyle.FILL,
        fillOptions = FillOptions(pullCompensationMm = 0.0, underlay = false, border = FillBorder.NONE, strategy = strategy))

    @Test fun presetsUseSameRailSpacingAndRespectSmallTextBoundary() {
        assertEquals(listOf(0.4, 0.35, 0.3), ThreadWeight.entries.map { it.spacingForHeight(7.0) })
        assertEquals(listOf(0.6, 0.5, 0.3), ThreadWeight.entries.map { it.spacingForHeight(6.999) })
        for (weight in ThreadWeight.entries) for (height in listOf(6.0, 10.0)) {
            assertEquals(weight.spacingForHeight(height), FillOptions().densityForHeight(height, weight))
            assertEquals(weight.spacingForHeight(height), SatinOptions().densityForHeight(height, weight))
        }
    }
    @Test fun finerThreadIncreasesActualSatinAndTatamiStitchCountsWithoutChangingBounds() {
        for (strategy in FillStrategy.entries) {
            val obj = outline(strategy = strategy).let { it.copy(fillOptions = it.fillOptions.copy(border = FillBorder.RUNNING)) }
            val patterns = ThreadWeight.entries.map { obj.withThreadWeight(it).localPattern() }
            assertTrue(patterns[0].stitchCount < patterns[1].stitchCount)
            assertTrue(patterns[1].stitchCount < patterns[2].stitchCount)
            assertTrue(patterns.all { it.bounds == patterns.first().bounds })
            val back = PesReader.read(PesWriter.write(patterns.last()))
            assertEquals(io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.forSewout(patterns.last()).stitchCount, back.stitchCount)
            assertEquals(patterns.last().bounds, back.bounds)
        }
        // Imported single-line geometry uses the same satin preset as Hershey.
        val line = TextObject("line", fontKind = FontKind.OUTLINE, singleLine = true,
            contours = listOf(listOf(0.0 to 0.0, 0.0 to 10.0)), contourSourceHeight = 10.0,
            stitchStyle = StitchStyle.SATIN)
        assertTrue(line.withThreadWeight(ThreadWeight.WT60).localPattern().stitchCount > line.localPattern().stitchCount)
        val hershey = TextObject("hershey", text = "I", stitchStyle = StitchStyle.SATIN)
        assertTrue(hershey.withThreadWeight(ThreadWeight.WT60).localPattern().stitchCount > hershey.localPattern().stitchCount)
        assertEquals(hershey.copy(stitchStyle = StitchStyle.BEAN).localPattern().stitches,
            hershey.copy(stitchStyle = StitchStyle.BEAN, threadWeight = ThreadWeight.WT60).localPattern().stitches)
    }
    @Test fun qualityUsesWeightThresholdsButNeverChangesMeasuredGeometry() {
        // Limits scale with the preset (FontQuality local percentiles); the measured geometry does not.
        val bar = listOf(listOf(bar(0.8, 20.0)))
        val reports = ThreadWeight.entries.map { FontQuality.analyze(bar, 20.0, true, threadWeight = it) }
        reports.forEach { assertEquals(reports[0].inkP10Mm!!, it.inkP10Mm!!, 1e-9) }
        assertTrue(reports.zipWithNext().all { (a, b) -> b.recommendedMinHeightMm < a.recommendedMinHeightMm })
        assertTrue(reports.zipWithNext().all { (a, b) -> b.grade.ordinal <= a.grade.ordinal })
        assertTrue(reports.all { it.limitedBy == QualityLimit.INK })
        val strokes = listOf(listOf(listOf(0.0 to 0.0, 0.0 to 10.0), listOf(0.9 to 0.0, 0.9 to 10.0)))
        assertTrue(FontQuality.analyze(strokes, 10.0, false).tightSpots > 0)
        assertEquals(0, FontQuality.analyze(strokes, 10.0, false, threadWeight = ThreadWeight.WT60).tightSpots)
    }
    @Test fun cacheSeparatesWeightsAndProjectRoundTripRetainsSettings() {
        val cache = FontQualityCache()
        val key = FontQualityKey("font", "v1", "I", 10.0, true)
        val a = cache.getOrAnalyze(key) { listOf(listOf(bar(1.0))) }
        val b = cache.getOrAnalyze(key.copy(threadWeight = ThreadWeight.WT60)) { listOf(listOf(bar(1.0))) }
        assertEquals(2, cache.size)
        assertTrue(b.recommendedMinHeightMm < a.recommendedMinHeightMm)
        for (weight in ThreadWeight.entries) {
            val project = Project(objects = listOf(outline().withThreadWeight(weight)))
            val back = ProjectJson.decode(project.toJson())
            assertEquals(project, back)
            assertEquals(project.flatten().stitches, back.flatten().stitches)
        }
    }
    @Test fun manualDensitySurvivesUntilChangingWeightAndLegacyProjectsStayFortyWeight() {
        val manual = outline(6.0).copy(fillOptions = FillOptions(densityMm = 0.55, autoDensity = false),
            satinOptions = SatinOptions(densityMm = 0.55, autoDensity = false))
        for (weight in ThreadWeight.entries) {
            assertEquals(0.55, manual.fillOptions.densityForHeight(6.0, weight))
            assertEquals(0.55, manual.satinOptions.densityForHeight(6.0, weight))
        }
        val fine = manual.withThreadWeight(ThreadWeight.WT60)
        assertTrue(fine.fillOptions.autoDensity && fine.satinOptions.autoDensity)
        assertEquals(0.3, fine.satinOptions.densityForHeight(6.0, fine.threadWeight))
        assertEquals(0.6, fine.withThreadWeight(ThreadWeight.WT40).satinOptions.densityForHeight(6.0))
        val json = TextObjectCodec.encode(manual)
        val old = JsonObject(json.filterKeys { it != "threadWeight" } + ("satinOptions" to
            JsonObject(json.getValue("satinOptions").jsonObject.filterKeys { it != "autoDensity" })))
        val decoded = TextObjectCodec.decode(old) as TextObject
        assertEquals(ThreadWeight.WT40, decoded.threadWeight)
        assertFalse(decoded.satinOptions.autoDensity)
        assertEquals(manual.localPattern().stitches, decoded.localPattern().stitches)
    }
}
