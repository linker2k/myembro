// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.inkstitch.elements.EmbroideryElement
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import kotlin.test.*

class ImageElementAdapterTest {
    private fun trace(w: Int, h: Int, mm: Double = w.toDouble()) = ImagePipeline.trace(
        RasterImage(w, h, IntArray(w * h) { 0xFFAA1122.toInt() }),
        ImageTraceOptions(mm, minimumRegionAreaMm2 = 0.0, simplifyToleranceMm = 0.0))

    @Test fun actualContractReceivesSvgPixelsAndPreservesParametersAndThread() {
        for (draft in listOf(trace(20, 20), trace(30, 3), trace(100, 2, 20.0))) {
            val original = draft.elements.single()
            val request = original.toSvgElement()
            assertEquals(original.type, request.type)
            assertEquals(original.parameters, request.parameters)
            assertEquals(original.region.color.thread, request.color)
            assertEquals(if (original.type == ImageStitchType.SATIN) 2 else 1, request.paths.size)
            assertTrue(request.paths.all { path -> path.points.all { p -> p.x <= Units.mmToPixels(draft.vector.widthMm) + 1e-9 } })
            val nativePaths = when (original.type) {
                ImageStitchType.FILL -> original.region.geometry.boundaries()
                ImageStitchType.SATIN -> listOf(original.rails!!.first, original.rails.second)
                ImageStitchType.RUNNING -> listOf(original.centerLine!!)
            }
            for ((source, converted) in nativePaths.zip(request.paths)) {
                assertEquals(source.closed, converted.closed)
                for ((a, b) in source.points.zip(converted.points)) {
                    assertEquals(a.x / 10, Units.pixelsToMm(b.x), 1e-10)
                    assertEquals(a.y / 10, Units.pixelsToMm(b.y), 1e-10)
                }
            }
        }
    }

    @Test fun handoffIsPassiveAndPreviousStitchReachesFactoryElementUnchanged() {
        val draft = trace(100, 2, 20.0)
        var generated = false
        val previous = Stitch(-4.5, 7.25, tags = setOf("source"))
        val elements: List<EmbroideryElement> = draft.toEmbroideryElements { request ->
            EmbroideryElement { received ->
                assertSame(previous, received)
                generated = true
                listOf(StitchGroup(color = request.color, stitches = request.paths.single().points.map { Stitch(it) }))
            }
        }
        assertFalse(generated)
        val groups = elements.single().toStitchGroups(previous)
        assertTrue(generated)
        assertEquals(draft.elements.single().region.color.thread, groups.single().color)
        val plan = StitchPlan(listOf(ColorBlock(groups.single().color, groups.single().stitches)))
        val native = EmbPatternAdapter.toEmbPattern(plan)
        assertEquals(200, native.bounds!!.width)
        assertEquals(1, native.stitchCount - 1)
    }

    @Test fun holesAndNonIntegerMillimetersSurviveUnitBoundary() {
        val pixels = IntArray(49) { i -> if (i % 7 in 2..4 && i / 7 in 2..4) 0 else 0xFF112233.toInt() }
        val draft = ImagePipeline.trace(RasterImage(7, 7, pixels), ImageTraceOptions(13.37,
            minimumRegionAreaMm2 = 0.0, simplifyToleranceMm = 0.0))
        val request = draft.elements.single().toSvgElement()
        assertEquals(2, request.paths.size)
        assertEquals(io.github.linker2k.myembro.engine.geometry.FillRule.EVEN_ODD, request.fillRule)
        assertTrue(request.paths.all { it.closed })
        assertEquals(13.37, request.paths.flatMap { it.points }.maxOf { Units.pixelsToMm(it.x) }, 1e-10)
    }
}
