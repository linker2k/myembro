// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitches

import kotlinx.serialization.json.*
import org.locationtech.jts.io.WKTReader
import kotlin.math.abs
import kotlin.test.*

/** expected.json is unchanged Ink/Stitch v3.3.0 output from tools/cross-stitch/oracle.py (ordered mode). */
class CrossStitchOracleTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResource("/cross-stitch/$name")).readText()

    @Test fun everyCaseMatchesUpstreamStitchForStitch() {
        val cases = Json.parseToJsonElement(resource("cases.json")).jsonArray
        val expected = Json.parseToJsonElement(resource("expected.json")).jsonObject.getValue("results").jsonObject
        assertEquals(40, cases.size)
        var stitches = 0
        var worst = 0.0
        for (raw in cases) {
            val case = raw.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            fun pair(key: String) = case.getValue(key).jsonArray.let { Pair(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
            fun point(key: String) = case[key]?.takeIf { it !is JsonNull }?.jsonArray?.let { FillPoint(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
            val settings = CrossStitch.Settings(
                method = case.getValue("method").jsonPrimitive.content, patternSizePx = pair("pattern"),
                coverage = case.getValue("coverage").jsonPrimitive.int, offsetPx = pair("offset"),
                canvasGridOrigin = case.getValue("canvas_grid_origin").jsonPrimitive.boolean,
                threadCount = case.getValue("thread_count").jsonPrimitive.int,
                maxStitchLengthPx = case.getValue("max_length").jsonPrimitive.double)
            val shape = WKTReader().read(case.getValue("wkt").jsonPrimitive.content)
            val want = expected.getValue(id).jsonObject.getValue("cycles").jsonArray
            val got = CrossStitch.generate(shape, settings, point("start"), point("end"))
            assertEquals(want.size, got.size, "$id cycles")
            for ((w, g) in want.zip(got)) {
                assertEquals(w.jsonArray.size, g.size, "$id stitches")
                for ((ws, gs) in w.jsonArray.zip(g)) {
                    val d = maxOf(abs(ws.jsonArray[0].jsonPrimitive.double - gs.x), abs(ws.jsonArray[1].jsonPrimitive.double - gs.y))
                    worst = maxOf(worst, d)
                    assertTrue(d <= 1e-6, "$id stitch $stitches off by $d px")
                    stitches++
                }
            }
        }
        assertEquals(15506, stitches)
        println("cross stitch oracle: ${cases.size} cases, $stitches stitches, max deviation $worst px")
    }

    @Test fun fontAttributesFollowUpstreamParameterDefaults() {
        val s = CrossStitch.Settings.fromAttributes(mapOf("fill_method" to "cross_stitch", "pattern_size_mm" to "2",
            "cross_offset_mm" to "", "canvas_grid_origin" to "False", "fill_coverage" to "50", "cross_thread_count" to "2.0"))
        assertEquals("simple cross", s.method)
        assertEquals(2.0 * 96 / 25.4, s.patternSizePx.first, 1e-12)
        assertEquals(s.patternSizePx.first, s.patternSizePx.second)
        assertEquals(Pair(0.0, 0.0), s.offsetPx)
        assertFalse(s.canvasGridOrigin)
        assertEquals(4, s.threadCount) // int("2.0") fails upstream -> default
        assertEquals(11.0 * 96 / 25.4, s.maxStitchLengthPx, 1e-12)
        assertTrue(s.supported)
        assertFalse(CrossStitch.Settings.fromAttributes(mapOf("cross_stitch_method" to "half_cross")).supported)
        assertFalse(CrossStitch.Settings.fromAttributes(mapOf("cross_rotation" to "15")).supported)
    }
}
