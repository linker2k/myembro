// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.render.realistic

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.*
import kotlin.test.*

/**
 * Opt-in specification: ./gradlew :engine:realisticBrightnessAcceptance
 * Not an upstream-pixel golden. The user approved a new display requirement on 2026-11-06:
 * same-thread mean sRGB Y' must differ by <= 1 code value (8-bit rounding allowance).
 * Deliberately red on the current renderer; never disabled or inverted into an expected-failure test.
 * All geometry below is an original synthetic rectangle: no imported PES/SVG, pictures, or /tmp data.
 */
class RealisticBrightnessAcceptanceTest {
    companion object {
        // The ONLY acceptance threshold, inclusive. User-approved 8-bit rounding allowance.
        private const val MAX_MEAN_LUMA_DELTA = 1.0
        private val PIXELS_PER_MM = listOf(4, 8, 16)
        private const val RGB = 0xAF8134 // One constant thread color for every segment/layer.
        private const val LEFT = 4.0
        private const val RIGHT = 40.0
        private const val TOP = 4.0
        private const val BOTTOM = 28.0
        private const val ROW_SPACING = 0.25 // Requested synthetic top-fill spacing (mm).
        private const val UNDERLAY_SPACING = 0.75 // Requested 90-degree underlay spacing (mm).
        private const val END_INSET = 0.8 // Requested end-window center, in mm from the edge.

        private data class Window(val name: String, val x0: Double, val x1: Double,
                                  val y0: Double, val y1: Double)
        private data class Frame(val ppm: Int, val width: Int, val pixels: IntArray, val thread: BooleanArray)
        private data class Scene(val underlay: List<RenderThreadSegment>, val rows: List<List<RenderThreadSegment>>) {
            val original get() = underlay + rows.flatten()
            // Same rows/endpoints/directions; only rows 32..47 change their drawing order.
            val switched get() = underlay + (rows.take(32) + rows.subList(32, 48).reversed() + rows.drop(48)).flatten()
        }
        private fun point(x: Double, y: Double) = RenderPoint(x * ThreadShape.SVG_PIXELS_PER_MM,
                                                            y * ThreadShape.SVG_PIXELS_PER_MM)
        private fun segment(x: Double, y: Double, xx: Double, yy: Double) =
            RenderThreadSegment(point(x, y), point(xx, yy), RGB)

        private val scene by lazy {
            // Vertical 90-degree underlay, sewn FIRST, .75mm apart, 4mm stitch lengths.
            val underlay = (0..((RIGHT - LEFT) / UNDERLAY_SPACING).roundToInt()).flatMap { column ->
                val x = LEFT + column * UNDERLAY_SPACING
                val points = (0..6).map { TOP + it * 4.0 }.let { if (column % 2 == 0) it else it.reversed() }
                points.zipWithNext().map { (a, b) -> segment(x, a, x, b) }
            }
            // Lengths sum to 36mm; rotating them staggers needle holes without a short remainder.
            // Every top stitch is 3..7mm, horizontal 0 degrees, .25mm row spacing.
            val lengths = listOf(5.0, 7.0, 3.0, 6.0, 4.0, 7.0, 4.0)
            val rows = (0..((BOTTOM - TOP) / ROW_SPACING).roundToInt()).map { row ->
                val y = TOP + row * ROW_SPACING
                val shift = row % lengths.size
                val xs = mutableListOf(LEFT)
                for (length in lengths.drop(shift) + lengths.take(shift)) xs += xs.last() + length
                val directed = if (row % 2 == 0) xs else xs.reversed()
                directed.zipWithNext().map { (a, b) -> segment(a, y, b, y) }
            }
            Scene(underlay, rows)
        }
        private fun awtPath(commands: List<ThreadPathCommand>) = Path2D.Double().apply {
            for (command in commands) when (command) {
                is ThreadPathCommand.Move -> moveTo(command.p.x, command.p.y)
                is ThreadPathCommand.Line -> lineTo(command.p.x, command.p.y)
                is ThreadPathCommand.Cubic -> curveTo(command.a.x, command.a.y, command.b.x, command.b.y,
                                                     command.end.x, command.end.y)
                ThreadPathCommand.Close -> closePath()
            }
        }
        private fun BufferedImage.graphicsAA(action: java.awt.Graphics2D.() -> Unit) {
            val g = createGraphics()
            try { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.action() }
            finally { g.dispose() }
        }
        private val rasterizer = ThreadAlphaRasterizer { path, w, h ->
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            image.graphicsAA { color = Color.WHITE; fill(awtPath(path)) }
            image.getRGB(0, 0, w, h, null, 0, w).also { a -> for (i in a.indices) a[i] = a[i] ushr 24 }
        }
        private fun render(ppm: Int, segments: List<RenderThreadSegment>): Frame {
            val w = (RIGHT + LEFT).toInt() * ppm
            val h = (BOTTOM + TOP).toInt() * ppm
            val scale = ppm / ThreadShape.SVG_PIXELS_PER_MM
            // Mask is independent of measured output color/lighting and of the row permutation.
            // Fully covered pixels in unfiltered original geometry define "same-thread pixels".
            val coverage = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            // Fill the geometric union ONCE: compositing AA masks separately would leave alpha<255
            // at 4px/mm even where adjacent thread shapes physically cover the entire pixel.
            // Every capsule has the same winding, so a non-zero-winding compound path is their union.
            val union = Path2D.Double(Path2D.WIND_NON_ZERO)
            for (s in scene.original) union.append(awtPath(ThreadShape.path(s, scale)), false)
            coverage.graphicsAA { color = Color.WHITE; fill(union) }
            val mask = coverage.getRGB(0, 0, w, h, null, 0, w).map { it ushr 24 == 255 }.toBooleanArray()
            val output = RealisticThreadRenderer.render(w, h, (ppm * 25.4).roundToInt(), segments, rasterizer,
                                                        pixelsPerSvgUnit = scale)
            return Frame(ppm, w, output, mask)
        }
        private val original by lazy { PIXELS_PER_MM.associateWith { render(it, scene.original) } }
        private val switched by lazy { PIXELS_PER_MM.associateWith { render(it, scene.switched) } }
        private fun mean(frame: Frame, window: Window): Double {
            // Exact pixel/physical-window intersection weights: no rounded ROI or resize filter.
            val p = frame.ppm.toDouble()
            var total = 0.0; var weight = 0.0
            for (y in floor(window.y0 * p).toInt() until ceil(window.y1 * p).toInt()) {
                val wy = (min(y + 1.0, window.y1 * p) - max(y.toDouble(), window.y0 * p)).coerceAtLeast(0.0)
                for (x in floor(window.x0 * p).toInt() until ceil(window.x1 * p).toInt()) {
                    val i = y * frame.width + x
                    if (!frame.thread[i]) continue
                    val wx = (min(x + 1.0, window.x1 * p) - max(x.toDouble(), window.x0 * p)).coerceAtLeast(0.0)
                    val c = frame.pixels[i]
                    val luma = .2126 * (c ushr 16 and 255) + .7152 * (c ushr 8 and 255) + .0722 * (c and 255)
                    total += luma * wx * wy; weight += wx * wy
                }
            }
            check(weight > 0) { "No same-thread pixels in $window" }
            return total / weight
        }
        private fun accepted(delta: Double) = delta.isFinite() && delta in 0.0..MAX_MEAN_LUMA_DELTA
        private fun finish(name: String, deltas: List<Double>) {
            val worst = deltas.max()
            println("BRIGHTNESS_RESULT $name maxDelta=$worst limit=$MAX_MEAN_LUMA_DELTA pass=${accepted(worst)}")
            assertTrue(accepted(worst), "$name: max mean Y' difference $worst > $MAX_MEAN_LUMA_DELTA; see BRIGHTNESS_METRIC output")
        }
    }

    @Test fun inclusiveThresholdBoundariesAndSceneIntegrity() {
        // n-1 / n / n+1 in the specified 8-bit-code-value units; threshold defined once above.
        assertTrue(accepted(MAX_MEAN_LUMA_DELTA - 1))
        assertTrue(accepted(MAX_MEAN_LUMA_DELTA))
        assertFalse(accepted(MAX_MEAN_LUMA_DELTA + 1))
        assertEquals(scene.underlay, scene.switched.take(scene.underlay.size))
        assertEquals(scene.original.groupingBy { it }.eachCount(), scene.switched.groupingBy { it }.eachCount())
        for (row in scene.rows) for (s in row) {
            assertEquals(s.start.y, s.end.y)
            assertTrue((hypot(s.end.x - s.start.x, s.end.y - s.start.y) / ThreadShape.SVG_PIXELS_PER_MM).roundToInt() in 3..7)
        }
    }

    @Test fun topRowOrderHasNoBandOrWindowBrightnessChange() {
        // The reversed section runs y=12..15.75mm; both transitions and interior are sampled.
        val windows = listOf(Window("above", 12.0, 32.0, 10.0, 11.0),
            Window("first-transition", 12.0, 32.0, 11.5, 12.0),
            Window("band", 12.0, 32.0, 13.0, 14.0),
            Window("second-transition", 12.0, 32.0, 15.5, 16.0),
            Window("below", 12.0, 32.0, 17.0, 18.0))
        val deltas = mutableListOf<Double>()
        for (ppm in PIXELS_PER_MM) {
            val a = windows.map { mean(original.getValue(ppm), it) }
            val b = windows.map { mean(switched.getValue(ppm), it) }
            for (i in windows.indices) {
                println("BRIGHTNESS_METRIC order ppm=$ppm window=${windows[i].name} original=${a[i]} switched=${b[i]} delta=${abs(a[i]-b[i])}")
                deltas += abs(a[i] - b[i])
                // Each scene's band/transition windows must also agree with BOTH neighbor windows.
                for (neighbor in listOf(0, windows.lastIndex)) {
                    deltas += abs(a[i] - a[neighbor]); deltas += abs(b[i] - b[neighbor])
                }
            }
        }
        finish("order", deltas)
    }

    @Test fun physicalMeanBrightnessIsResolutionIndependent() {
        val window = Window("physical-patch", 10.0, 34.0, 7.0, 25.0)
        val means = PIXELS_PER_MM.map { ppm -> mean(original.getValue(ppm), window).also {
            println("BRIGHTNESS_METRIC resolution ppm=$ppm mean=$it")
        } }
        finish("resolution", means.flatMap { a -> means.map { b -> abs(a-b) } })
    }

    @Test fun rowEndsHaveTheSameMeanBrightnessAsTheCenter() {
        val centerX = (LEFT + RIGHT) / 2
        // Equal physical-size windows, centered .8mm inside the ends, shared y=8..24mm.
        val windows = listOf(LEFT + END_INSET, centerX, RIGHT - END_INSET).mapIndexed { i, x ->
            Window(listOf("left", "center", "right")[i], x - .35, x + .35, 8.0, 24.0)
        }
        val deltas = mutableListOf<Double>()
        for (ppm in PIXELS_PER_MM) {
            val means = windows.map { mean(original.getValue(ppm), it) }
            println("BRIGHTNESS_METRIC ends ppm=$ppm left=${means[0]} center=${means[1]} right=${means[2]}")
            deltas += abs(means[0] - means[1]); deltas += abs(means[2] - means[1])
        }
        finish("ends", deltas)
    }
}
