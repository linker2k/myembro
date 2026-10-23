// Ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.WKTReader
import kotlin.test.*

class FillUnderlayTest {
    private val shape = WKTReader().read("POLYGON ((0 0,80 0,80 60,0 60,0 0),(25 20,25 40,45 40,45 20,25 20))")
    private val p = FillParameters(row_spacing_mm = 1.0, max_stitch_length_mm = 2.0)

    @Test fun defaultPerpendicularLayerMatchesOriginalTatamiCall() {
        val layers = FillUnderlay.generate(shape, p)
        assertEquals(1, layers.size)
        assertEquals(90.0, layers.single().angle)
        val expected = TatamiFill.fill(shape, Math.PI / 2, p.underlayRowSpacingPx, p.underlayRowSpacingPx,
            p.underlayMaxStitchLengthPx, p.runningLengthPx, p.runningTolerancePx, p.staggers, false, null,
            underpath = true).map { it.tagged("tatami_fill", "fill_underlay") }
        assertEquals(expected, layers.single().stitches)
    }

    @Test fun angleLayersChainPreviousLayerEndAndPreserveUserOrder() {
        val parameters = p.copy(fill_underlay_angle = "0, 90")
        val start = FillPoint(80.0, 0.0)
        val layers = FillUnderlay.generate(shape, parameters, start)
        assertEquals(listOf(0.0, 90.0), layers.map { it.angle })
        val expectedSecond = TatamiFill.fill(shape, Math.PI / 2, p.underlayRowSpacingPx,
            p.underlayRowSpacingPx, p.underlayMaxStitchLengthPx, p.runningLengthPx,
            p.runningTolerancePx, p.staggers, false, layers.first().stitches.last(), underpath = true)
            .map { it.tagged("tatami_fill", "fill_underlay") }
        assertEquals(expectedSecond, layers[1].stitches)
        assertTrue(layers.flatMap { it.stitches }.all { "fill_underlay" in it.tags })
    }

    @Test fun insetShrinksUnderlayAndTopFillExpandDoesNotChangeItsShape() {
        val layers = FillUnderlay.generate(shape, p.copy(fill_underlay_inset_mm = 1.0, expand_mm = 5.0))
        assertTrue(layers.isNotEmpty())
        assertTrue(layers.all { it.shape.area < shape.area })
        assertTrue(layers.flatMap { it.stitches }.all {
            shape.buffer(0.01).covers(shape.factory.createPoint(Coordinate(it.x, it.y)))
        })
    }

    @Test fun collapsedInsetFallsBackToOriginalShape() {
        val layers = FillUnderlay.generate(shape, p.copy(fill_underlay_inset_mm = 1000.0))
        assertEquals(1, layers.size)
        assertEquals(shape.area, layers.single().shape.area, 1e-9)
        assertEquals(FillUnderlay.generate(shape, p).single().stitches, layers.single().stitches)
    }

    @Test fun multipartIsSortedByStartDistanceAndLayersStayInsideTheirOwnIsland() {
        val multi = WKTReader().read("MULTIPOLYGON (((0 0,20 0,20 20,0 20,0 0)),((100 0,120 0,120 20,100 20,100 0)))")
        val layers = FillUnderlay.generate(multi, p.copy(fill_underlay_angle = "0 90"), FillPoint(120.0, 0.0))
        assertEquals(listOf(0.0, 90.0, 0.0, 90.0), layers.map { it.angle })
        assertEquals(listOf(100.0, 100.0, 0.0, 0.0), layers.map { it.shape.envelopeInternal.minX })
        assertTrue(layers.all { layer -> layer.stitches.all {
            layer.shape.buffer(0.01).covers(layer.shape.factory.createPoint(Coordinate(it.x, it.y)))
        } })
        val noStart = FillUnderlay.generate(multi, p)
        assertEquals(listOf(0.0, 100.0), noStart.map { it.shape.envelopeInternal.minX })
    }

    @Test fun disabledFillUnderlayLegacyCrossAndEmptyShapesProduceNoLayers() {
        assertTrue(FillUnderlay.generate(shape, p.copy(fill = false)).isEmpty())
        assertTrue(FillUnderlay.generate(shape, p.copy(fill_underlay = false)).isEmpty())
        assertTrue(FillUnderlay.generate(shape, p.copy(fill_method = "legacy_fill")).isEmpty())
        assertTrue(FillUnderlay.generate(shape, p.copy(fill_method = "cross_stitch")).isEmpty())
        assertTrue(FillUnderlay.generate(GeometryFactory().createPolygon(), p).isEmpty())
    }
}
