// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.SvgPath
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** SVG 1.1 structure the emoji route needs: transforms, groups, basic shapes, fill-rule, opacity. */
class VectorArtSvgTest {
    private fun svg(body: String) = VectorArt.parseSvg("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\">$body</svg>")
    private fun points(shape: VectorArt.Shape) = SvgPath.flatten(shape.d, shape.transform, tolerance = 0.01).flatMap { it.points }
    private fun near(a: Point, b: Point) = assertTrue(abs(a.x - b.x) < 1e-6 && abs(a.y - b.y) < 1e-6, "$a != $b")

    @Test fun transformsNestAndRotateAboutTheGivenCenter() {
        // Fluent style: rotate(angle cx cy) on the element, inside a translated, scaled group.
        val art = svg("<g transform=\"translate(10 0) scale(2)\"><path d=\"M1,1 L3,1\" transform=\"rotate(90 1 1)\" fill=\"#FF0000\"/></g>")
        val p = points(art.shapes.single())
        near(Point(12.0, 2.0), p.first()) // (1,1) is the rotation center → scale 2 → +10
        near(Point(12.0, 6.0), p.last())  // (3,1) rotates to (1,3) → (2,6) → (12,6)
        val matrix = svg("<path d=\"M0,0 L1,0\" transform=\"matrix(0 1 -1 0 5 5)\" fill=\"#000\"/>")
        near(Point(5.0, 6.0), points(matrix.shapes.single()).last())
    }

    @Test fun strokeWidthFollowsTheTransformScale() {
        val art = svg("<g transform=\"scale(3)\"><path d=\"M0,0 L4,0\" fill=\"none\" stroke=\"#000\" stroke-width=\"0.5\"/></g>")
        assertEquals(1.5, art.shapes.single().strokeWidth, 1e-9)
    }

    @Test fun basicShapesInheritedStyleFillRuleAndOpacity() {
        val art = svg("<g fill=\"#00FF00\" fill-rule=\"evenodd\"><rect x=\"1\" y=\"2\" width=\"4\" height=\"3\"/>" +
            "<circle cx=\"10\" cy=\"10\" r=\"2\" fill-opacity=\"0.5\" fill=\"#000000\"/><ellipse cx=\"20\" cy=\"20\" rx=\"3\" ry=\"1\" style=\"fill:#0000FF\"/></g>")
        assertEquals(listOf(0x00FF00, 0x808080, 0x0000FF), art.shapes.map { it.fill }) // 50% black on white paper
        assertTrue(art.shapes.all { it.fillRule == FillRule.EVEN_ODD })
        val rect = points(art.shapes[0])
        assertEquals(1.0, rect.minOf { it.x }, 1e-9); assertEquals(5.0, rect.maxOf { it.x }, 1e-9); assertEquals(5.0, rect.maxOf { it.y }, 1e-9)
        val circle = points(art.shapes[1])
        assertEquals(8.0, circle.minOf { it.x }, 0.02); assertEquals(12.0, circle.maxOf { it.y }, 0.02) // flattened arc, tolerance 0.01
    }

    @Test fun defaultFillIsBlackAndNonRenderedContentIsSkipped() {
        val art = svg("<defs><path d=\"M0,0 L1,1\"/></defs><clipPath id=\"c\"><rect width=\"32\" height=\"32\" fill=\"white\"/></clipPath><path d=\"M0,0 L4,0 L4,4 Z\"/>")
        assertEquals(listOf<Int?>(0x000000), art.shapes.map { it.fill })
    }

    @Test fun picturesItCannotDrawAreRejectedNotApproximated() {
        assertFailsWith<IllegalArgumentException> { svg("<linearGradient id=\"g\"/><path d=\"M0,0 L1,1\" fill=\"url(#g)\"/>") }
        assertFailsWith<IllegalArgumentException> { svg("<g clip-path=\"url(#c)\"><path d=\"M0,0 L1,1\"/></g>") }
        assertFailsWith<IllegalArgumentException> { svg("<path d=\"M0,0 L1,1\" filter=\"url(#f)\"/>") }
    }

    /** Opt-in survey: FLUENT_FLAT_DIR=<all Fluent Flat SVGs>. Prints parse success and rejection reasons. */
    @Test fun fluentFlatSurvey() {
        val dir = System.getenv("FLUENT_FLAT_DIR")?.let(::File) ?: return
        val files = dir.listFiles { f -> f.extension == "svg" }.orEmpty().sortedBy { it.name }
        val failures = files.mapNotNull { f -> runCatching { VectorArt.parseSvg(f.readText()) }.exceptionOrNull()?.let { f.name to it.message.orEmpty() } }
        val transformed = files.count { "transform=" in it.readText() }
        println("FLUENT parsed ${files.size - failures.size}/${files.size}, with transform $transformed, rejected ${failures.size}")
        failures.groupingBy { it.second.substringBefore(" on <").substringBefore(" url(") }.eachCount().forEach { (k, v) -> println("FLUENT reject $v × $k") }
    }
}
