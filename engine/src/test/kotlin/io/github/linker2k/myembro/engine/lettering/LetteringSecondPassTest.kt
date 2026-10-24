// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.*

class LetteringSecondPassTest {
    private val red = EmbPattern.DEFAULT_PALETTE[1]
    private val blue = EmbPattern.DEFAULT_PALETTE[0]
    private fun box(x: Double = 0.0, baseline: Double = 0.0) = listOf(
        x to baseline - 10, x + 5 to baseline - 10, x + 5 to baseline, x to baseline, x to baseline - 10,
    )

    @Test fun hersheySupportsMultilineSpacingAlignmentAndPerCharacterThreads() {
        val normal = LetteringEngine.generate("HH\nH", spacing = 0.0)
        val spaced = LetteringEngine.generate("HH\nH", spacing = 0.0, lineSpacing = 2.0)
        assertEquals(50, spaced.bounds!!.height - normal.bounds!!.height)
        fun lastLineX(alignment: TextAlignment) = LetteringEngine.generate("HH\nH", spacing = 0.0, alignment = alignment)
            .stitches.filter { it.command == Command.JUMP }.takeLast(3).first().x
        assertTrue(lastLineX(TextAlignment.LEFT) < lastLineX(TextAlignment.CENTER))
        assertTrue(lastLineX(TextAlignment.CENTER) < lastLineX(TextAlignment.RIGHT))
        val colored = TextObject("text", text = "A\nBA", thread = red, characterThreads = mapOf(2 to blue)).localPattern()
        assertEquals(listOf(red, blue, red), colored.threads)
        assertEquals(2, colored.colorChangeCount)
        assertEquals(3, colored.colorBlocks.size)
    }

    @Test fun radiusControlsAboveBelowArcsAndPreservesStitchLength() {
        val contours = listOf(listOf(-10.0 to -10.0, -10.0 to 0.0), listOf(10.0 to -10.0, 10.0 to 0.0))
        val above = LetteringEngine.fromContours(contours, 10.0, arcDeg = 1.0, arcRadiusMm = 30.0)
        val below = LetteringEngine.fromContours(contours, 10.0, arcDeg = -1.0, arcRadiusMm = 30.0)
        val shallow = LetteringEngine.fromContours(contours, 10.0, arcDeg = 1.0, arcRadiusMm = 60.0)
        assertTrue(abs(above.stitches.first().x) > abs(below.stitches.first().x))
        assertTrue(above.bounds!!.width > shallow.bounds!!.width)
        for (p in listOf(above, below, shallow)) p.stitches.zipWithNext().forEach { (a, b) ->
            if (b.command == Command.STITCH) assertTrue(hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) <= 25.0)
        }
    }

    @Test fun eachLineCurvesAroundItsOwnBaseline() {
        val p = LetteringEngine.fromContours(listOf(box(), box(baseline = 15.0)), 10.0, sourceHeight = 10.0,
            arcDeg = 1.0, arcRadiusMm = 30.0, contourBaselines = listOf(0.0, 15.0))
        val jumps = p.stitches.filter { it.command == Command.JUMP }
        assertEquals(2, jumps.size)
        assertEquals(jumps[0].x, jumps[1].x)
        assertEquals(150, jumps[1].y - jumps[0].y)
    }

    @Test fun multiColorOutlinePersistsAndExportsInSewingOrder() {
        val obj = TextObject("colors", text = "A\nBA", fontKind = FontKind.OUTLINE, fontId = "offline",
            heightMm = 10.0, thread = red, contours = listOf(box(), box(8.0, 15.0), box(16.0, 15.0)),
            contourSourceHeight = 10.0, contourCharacters = listOf(0, 2, 3), contourBaselines = listOf(0.0, 15.0, 15.0),
            lineSpacing = 1.5, alignment = TextAlignment.RIGHT, arcDeg = -1.0, arcRadiusMm = 40.0,
            characterThreads = mapOf(2 to blue))
        val project = Project(objects = listOf(obj))
        val restored = ProjectJson.decode(project.toJson())
        assertEquals(project, restored)
        val pattern = project.flatten()
        assertEquals(pattern.stitches, restored.flatten().stitches)
        assertEquals(listOf(red, blue, red), pattern.threads)
        assertEquals(2, pattern.colorChangeCount)
        assertTrue(pattern.trimCount >= 2)
        val decoded = PesReader.read(PesWriter.write(pattern))
        assertEquals(pattern.bounds, decoded.bounds)
        assertEquals(io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.forSewout(pattern).stitchCount, decoded.stitchCount)
        assertEquals(3, decoded.colorBlocks.size)
    }

    @Test fun legacyJsonDefaultsStillProduceTheSamePattern() {
        val obj = TextObject("legacy", text = "AB")
        val extra = setOf("lineSpacing", "alignment", "arcRadiusMm", "characterThreads", "contourCharacters", "contourBaselines")
        val restored = TextObjectCodec.decode(JsonObject(TextObjectCodec.encode(obj).filterKeys { it !in extra })) as TextObject
        assertEquals(obj, restored)
        assertEquals(obj.localPattern().stitches, restored.localPattern().stitches)
    }

    @Test fun rejectsInvalidRadiusAndParallelMetadata() {
        assertFailsWith<IllegalArgumentException> { LetteringEngine.fromContours(listOf(box()), 10.0, arcRadiusMm = 0.0) }
        assertFailsWith<IllegalArgumentException> { LetteringEngine.fromContours(listOf(box()), 10.0, contourBaselines = listOf(0.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { TextObject("bad", lineSpacing = 0.5) }
        assertFailsWith<IllegalArgumentException> { TextObject("bad", fontKind = FontKind.OUTLINE, contours = listOf(box()), characterThreads = mapOf(0 to red)) }
    }

    private fun key(id: String = "font") = FontQualityKey(id, "sha256:v1", "A", 10.0, true)

    @Test fun cacheHitsRevisionIsolationEvictionAndInvalidation() {
        val cache = FontQualityCache(2)
        var calls = 0
        val get = { k: FontQualityKey -> cache.getOrAnalyze(k) { calls++; listOf(listOf(box())) } }
        val a = get(key("a"))
        assertEquals(a, get(key("a")))
        assertEquals(1, calls)
        get(key("b"))
        cache.get(key("a")) // a becomes most recently used
        get(key("c"))
        assertNull(cache.get(key("b")))
        assertEquals(2, cache.size)
        assertNull(cache.get(key("a").copy(revision = "sha256:v2")))
        assertNull(cache.get(key("a").copy(heightMm = 20.0)))
        assertNull(cache.get(key("a").copy(outline = false)))
        assertNull(cache.get(key("a").copy(sampleText = "B")))
        cache.invalidate("a")
        assertNull(cache.get(key("a")))
        cache.clear()
        assertEquals(0, cache.size)
        assertFailsWith<IllegalArgumentException> { FontQualityCache(0) }
    }

    @Test fun cacheComputesOnceAcrossConcurrentReadersAndDoesNotCacheFailures() {
        val cache = FontQualityCache()
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val readers = (0..3).map { pool.submit<FontQualityReport> {
                start.await()
                cache.getOrAnalyze(key()) { calls.incrementAndGet(); listOf(listOf(box())) }
            } }
            start.countDown()
            val reports = readers.map { it.get(5, TimeUnit.SECONDS) }
            assertTrue(reports.all { it == reports.first() })
            assertEquals(1, calls.get())
            assertFailsWith<IllegalStateException> { cache.getOrAnalyze(key("bad")) { error("Missing font") } }
            assertNull(cache.get(key("bad")))
        } finally { pool.shutdownNow() }
    }
}
