// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.digitizing

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.*
import kotlin.test.*

class DigitizingTest {
    private val square = "M-200-200H200V200H-200Z"
    private val hole = "M-60-60H60V60H-60Z"
    private val thread = PecThreads.forIndex(5)

    @Test fun runningPreservesCornersEndpointsClosureAndMaximumLength() {
        val path = Polyline(listOf(Point(0.0, 0.0), Point(60.0, 0.0), Point(60.0, 40.0)), true)
        val sampled = RunningStitch.sample(path)
        assertTrue(Point(60.0, 0.0) in sampled.points)
        assertTrue(Point(60.0, 40.0) in sampled.points)
        assertEquals(sampled.points.first(), sampled.points.last())
        assertTrue(sampled.points.zipWithNext().all { (a, b) -> a.distance(b) <= 25.000001 })
        val denseStraight = Polyline((0..100).map { Point(it.toDouble(), 0.0) })
        RunningStitch.sample(denseStraight).points.map { it.x }.zip(listOf(0.0,25.0,50.0,75.0,100.0))
            .forEach { (a,b) -> assertEquals(b,a,1e-5) }
        val pattern = RunningStitch.generate(listOf(path, Polyline(listOf(Point(150.0, 150.0), Point(200.0, 150.0)))))
        assertEquals(2, pattern.jumpCount)
        assertEquals(1, pattern.trimCount)
        assertEquals(Command.END, pattern.stitches.last().command)
        assertEquals(3, path.points.size)
    }

    @Test fun tatamiDefaultsHaveFourUnitSpacingFortyUnitStitchesAndStaggeredRows() {
        val pattern = TatamiFill.generate(SvgPath.polygons("M0 0H200V20H0Z"), TatamiSettings(underlay = false))
        val sewn = io.github.linker2k.myembro.engine.lettering.bodyStitches(pattern)
        assertEquals(setOf(2,6,10,14,18), sewn.map { it.y }.toSet()) // Existing generic Tatami API preserves its half-row origin.
        for (y in listOf(2,6,10,14,18)) {
            val interior = sewn.filter { it.y == y && it.x > 0 && it.x < 200 }.map { it.x }
            assertTrue(interior.isNotEmpty())
            assertTrue(interior.all { Math.floorMod(it,40) == (y/4%4)*10 })
        }
        assertEquals(1, pattern.jumpCount)
        for ((a, b) in pattern.stitches.zipWithNext()) if (a.command == Command.STITCH && b.command == Command.STITCH) {
            assertTrue(hypot((b.x-a.x).toDouble(), (b.y-a.y).toDouble()) <= 40.8)
        }
    }

    private fun assertInside(pattern: EmbPattern, region: PolygonRegion) {
        // Integer quantization can move boundary endpoints by sqrt(0.5^2+0.5^2) units.
        val allowed = region.offset(0.72)
        for (s in pattern.stitches.filter { it.command == Command.STITCH }) assertTrue(allowed.covers(Point(s.x.toDouble(), s.y.toDouble())), "$s")
        for ((a, b) in pattern.stitches.zipWithNext()) if (a.command == Command.STITCH && b.command == Command.STITCH) {
            assertTrue(allowed.coversSegment(Point(a.x.toDouble(), a.y.toDouble()), Point(b.x.toDouble(), b.y.toDouble())), "${a} -> $b")
        }
    }

    @Test fun filteredFillBodyNeverSewsAcrossHolesConcavityOrDisconnectedComponents() {
        val cases = listOf("$square $hole", "M0 0H200V40H40V200H0Z", "M0 0H80V80H0Z M120 0H200V80H120Z")
        for (path in cases) for (angle in listOf(0.0, 37.0, 90.0, -25.0)) {
            val region = SvgPath.polygons(path, FillRule.EVEN_ODD)
            val pattern = TatamiFill.generate(region, TatamiSettings(angleDegrees = angle))
            assertTrue(pattern.stitchCount > 0)
            // Check the ACTUAL filtered body, not stripGeneratedLocks(), which restores
            // the pre-filter baseline. Ink/Stitch's half-stitch fallback can extend a lock
            // outside the region; that source behavior has a separate explicit regression.
            val lockIndices = pattern.metadata[io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.LOCK_INDICES_KEY]
                .orEmpty().split(',').mapNotNull(String::toIntOrNull).toSet()
            assertInside(EmbPattern(pattern.stitches.filterIndexed { index, _ -> index !in lockIndices }, pattern.threads), region)
            assertTrue(pattern.trimCount > 0)
        }
        val donut = TatamiFill.generate(SvgPath.polygons("$square $hole", FillRule.EVEN_ODD), TatamiSettings(underlay = false))
        assertTrue(donut.jumpCount > 1) // Generic scanlines jump across counters.
        assertTrue(donut.trimCount > 1)
    }

    @Test fun sourceHalfStitchFallbackIsNotARegionContainmentGuarantee() {
        val region = SvgPath.polygons(square)
        val pattern = io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.finish(
            EmbPattern(listOf(Stitch(200, 198, Command.JUMP), Stitch(200, 198, Command.STITCH),
                Stitch(198, 198, Command.STITCH), Stitch(198, 198, Command.END)), listOf(thread)))
        // Source lock_stitch.py uses +x 0.5 SVG px when no candidate is >0.5mm.
        // Preserve that behavior openly: it can quantize to a needle 0.1mm outside.
        assertTrue(pattern.stitches.any { it.command == Command.STITCH && it.x == 201 && it.y == 198 })
        assertFailsWith<AssertionError> { assertInside(pattern, region) }
    }

    @Test fun underlayRunsInsideEdgeBeforeFillAndHandlesCollapsedInsets() {
        val region = SvgPath.polygons(square)
        val with = TatamiFill.generate(region)
        val without = TatamiFill.generate(region, TatamiSettings(underlay = false))
        assertTrue(with.stitchCount > without.stitchCount)
        val first = with.stitches.first { it.command == Command.JUMP }
        assertTrue(abs(first.x) == 190 && abs(first.y) == 190)
        assertInside(with, region)
        val narrow = SvgPath.polygons("M0 0H100V1H0Z")
        assertTrue(TatamiFill.generate(narrow).stitchCount > 0)
        assertEquals(0, TatamiFill.generate(PolygonRegion.empty()).stitchCount)
        assertEquals(0, RunningStitch.generate(emptyList()).stitchCount)
        assertEquals(1, RunningStitch.generate(listOf(Polyline(listOf(Point(1.0, 1.0))))).stitchCount) // source retains an isolated anchor
    }

    @Test fun invalidSettingsAndExcessiveWorkAreRejected() {
        assertFailsWith<IllegalArgumentException> { RunningSettings(stitchLength = 0.0) }
        assertFailsWith<IllegalArgumentException> { TatamiSettings(rowSpacing = 0.0) }
        assertFailsWith<IllegalArgumentException> { TatamiSettings(angleDegrees = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { TatamiSettings(rowOffsets = listOf(1.0)) }
        assertFailsWith<IllegalArgumentException> { TatamiFill.generate(SvgPath.polygons(square), TatamiSettings(rowSpacing = 1e-20)) }
        assertFailsWith<IllegalArgumentException> { RunningStitch.sample(Polyline(listOf(Point(0.0, 0.0), Point(1e8, 0.0))), RunningSettings(1e-20)) }
    }

    @Test fun generatedPathsRoundTripAsPesAndExportIndependentGeometryOracleInputs() {
        val output = File("build/digitizing-check").apply { mkdirs() }
        val paths = linkedMapOf(
            "square" to square,
            "donut" to "$square $hole",
            "concave" to "M-200-200H200V-100H-100V200H-200Z",
            "islands" to "M-200-100H-40V100H-200Z M40-100H200V100H40Z",
            "curves" to "M-150 0C-150-200 150-200 150 0Q0 200-150 0Z",
            "arcs" to "M-180 0A180 120 20 1 0 180 0A180 120 20 1 0-180 0Z",
        )
        for ((name, data) in paths) {
            val flattened = SvgPath.flatten(data)
            val region = PolygonRegion.fromPaths(flattened, FillRule.EVEN_ODD)
            val patterns = listOf(
                "running" to RunningStitch.generate(flattened, thread = thread),
                "fill0" to TatamiFill.generate(region, TatamiSettings(underlay = false), thread),
                "fill37" to TatamiFill.generate(region, TatamiSettings(angleDegrees = 37.0), thread),
            )
            for ((kind, source) in patterns) for (version in PesVersion.entries) {
                val expected = if (version == PesVersion.V6) PecWriter.normalizePreservingThreads(source) else PecWriter.normalize(source)
                val bytes = PesWriter.write(source, version)
                val back = PesReader.read(bytes)
                assertTrue(back.warnings.isEmpty())
                assertEquals(expected.stitches, back.stitches)
                assertEquals(expected.threads, back.threads)
                val file = File(output, "$name-$kind-$version.pes")
                file.writeBytes(bytes)
                fun stitches(p: EmbPattern) = JsonArray(p.stitches.map { buildJsonArray { add(it.x); add(it.y); add(it.command.name) } })
                val manifest = buildJsonObject {
                    put("source", buildJsonObject {
                        put("stitches", stitches(source)); put("threads", JsonArray(source.threads.map { JsonPrimitive(it.color and 0xFFFFFF) }))
                        put("threadDetails", JsonArray(source.threads.map { t -> buildJsonObject {
                            put("color", t.color and 0xFFFFFF)
                            for ((key, value) in mapOf("description" to t.description, "catalogNumber" to t.catalogNumber, "brand" to t.brand, "chart" to t.chart)) put(key, value?.let(::JsonPrimitive) ?: JsonNull)
                        } }))
                        put("metadata", JsonObject(source.metadata.mapValues { JsonPrimitive(it.value) }))
                    })
                    put("expected", buildJsonObject {
                        put("stitches", stitches(expected)); put("threads", JsonArray(expected.threads.map { JsonPrimitive(it.color and 0xFFFFFF) }))
                    })
                    put("geometry", buildJsonObject {
                        put("kind", kind)
                        put("rings", JsonArray(region.boundaries().map { line -> JsonArray(line.points.map { buildJsonArray { add(it.x); add(it.y) } }) }))
                    })
                }
                File(file.path + ".json").writeText(manifest.toString())
            }
        }
    }
}
