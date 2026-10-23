// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Goldens are produced by unmodified upstream C, not by this Kotlin implementation. */
class CurveFitterOracleTest {
    @Test fun rectangleMatchesOriginalStage4() = compare("rectangle")
    @Test fun staircaseMatchesOriginalStage4() = compare("staircase")
    @Test fun ringIncludingReversedHoleMatchesOriginalStage4() = compare("ring")

    @Test fun rectangleMatchesOriginalStage5() = compare("rectangle", optimized = true)
    @Test fun staircaseMatchesOriginalStage5() = compare("staircase", optimized = true)
    @Test fun ringMatchesOriginalStage5() = compare("ring", optimized = true)
    @Test fun roundedShapeMatchesOriginalStage4() = compare("rounded")
    @Test fun roundedShapeMergesSevenSegmentsIntoThreeLikeOriginalStage5() = compare("rounded", optimized = true)

    private fun compare(name: String, optimized: Boolean = false) {
        val tokens = resource("$name.pbm").trim().split(Regex("\\s+"))
        assertEquals("P1", tokens[0])
        val width = tokens[1].toInt()
        val height = tokens[2].toInt()
        val topDown = tokens.drop(3).map { it.toInt() != 0 }
        val pixels = BooleanArray(width * height) { index ->
            topDown[(height - 1 - index / width) * width + index % width]
        }
        val expected = Json.parseToJsonElement(resource("$name.stage${if (optimized) 5 else 4}.json")).jsonObject
        assertEquals("886392d351417d6753aff61de08e294d1506e502", expected.getValue("upstream").jsonPrimitive.content)
        assertEquals(optimized, expected.getValue("opticurve").jsonPrimitive.boolean)
        val paths = BinaryContourTracer.trace(width, height, pixels)
        val expectedPaths = expected.getValue("paths").jsonArray
        assertEquals(expectedPaths.size, paths.size, "$name paths")
        paths.forEachIndexed { index, path ->
            val golden = expectedPaths[index].jsonObject
            assertEquals(golden.getValue("positive").jsonPrimitive.boolean, path.positive)
            assertEquals(golden.getValue("area").jsonPrimitive.long, path.area)
            val boundary = golden.getValue("boundary").jsonArray.map { p ->
                PixelPoint(p.jsonArray[0].jsonPrimitive.int, p.jsonArray[1].jsonPrimitive.int)
            }
            assertEquals(boundary, path.points, "$name boundary $index")
            assertEquals(golden.getValue("polygon").jsonArray.map { it.jsonPrimitive.int }, OptimalPolygon.indices(path))
            val stage4 = CurveFitter.fit(path)
            val unchanged = stage4.copy(vertices = stage4.vertices.toList(), segments = stage4.segments.toList())
            val fitted = if (optimized) CurveOptimizer.optimize(stage4) else stage4
            assertEquals(unchanged, stage4, "Optimizer must preserve its source curve")
            if (name == "rounded" && optimized) {
                assertEquals(7, stage4.segments.size)
                assertEquals(3, fitted.segments.size)
            }
            val vertices = golden.getValue("vertices").jsonArray
            assertEquals(vertices.size, fitted.vertices.size)
            vertices.forEachIndexed { i, v -> point(v, fitted.vertices[i]) }
            val segments = golden.getValue("segments").jsonArray
            assertEquals(segments.size, fitted.segments.size)
            fitted.segments.forEachIndexed { i, actual ->
                val segment = segments[i].jsonObject
                assertEquals(segment.getValue("tag").jsonPrimitive.int, actual.tag)
                val controls = segment.getValue("controls").jsonArray
                controls.forEachIndexed { c, p -> point(p, actual.controls[c]) }
                number(segment.getValue("alpha"), actual.alpha)
                number(segment.getValue("alpha0"), actual.alpha0)
                number(segment.getValue("beta"), actual.beta)
            }
        }
        // Ensure the three goldens exercise both cubic curves and sharp corners overall.
        val tags = paths.flatMap {
            val curve = CurveFitter.fit(it)
            (if (optimized) CurveOptimizer.optimize(curve) else curve).segments
        }.map { it.tag }
        assertTrue(tags.isNotEmpty())
        if (name == "rectangle") assertTrue(tags.all { it == 1 })
        if (name == "staircase") {
            assertTrue(1 in tags)
            assertTrue(2 in tags)
        }
    }
    private fun point(expected: JsonElement, actual: CurvePoint) {
        number(expected.jsonArray[0], actual.x)
        number(expected.jsonArray[1], actual.y)
    }
    private fun number(expected: JsonElement, actual: Double) {
        assertTrue(actual.isFinite())
        assertEquals(expected.jsonPrimitive.double, actual, 1e-10)
    }
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/potrace/$name")).readText()
}
