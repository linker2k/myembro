// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.inkstitch.stitches.FillGeometry
import kotlin.test.*

class FillMatrixInputTest {
    private fun svg(path:String="<path d=\"M0 0 H100 V100 H0 Z M30 30 V70 H70 V30 Z\" fill=\"#b74e35\" fill-rule=\"evenodd\" inkstitch:row_spacing_mm=\"0.4\"/>",metadata:String="") =
        """<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkstitch="http://inkstitch.org/namespace" width="48mm" height="24mm" viewBox="-10 -10 240 120">$path<metadata>$metadata</metadata></svg>"""
    @Test fun upstreamViewboxKeepsTranslationInPixelsAndRoundsScaleToSixPlaces() {
        val region=FillMatrixInput.read(svg().toByteArray()).regions.single()
        val bounds=region.geometry.envelopeInternal
        assertEquals(10.0,bounds.minX,1e-9)
        assertEquals(10.0,bounds.minY,1e-9)
        assertEquals(75.5906,bounds.width,1e-8)
        assertEquals(1,FillGeometry.polygons(region.geometry).single().numInteriorRing)
        assertEquals(0.4,region.parameters.row_spacing_mm)
        assertTrue(region.parameters.fill) // SVG fill color is not inkstitch:fill's Boolean.
        assertEquals(0xffb74e35.toInt(),region.thread.color)
    }
    @Test fun zeroSpacingRectKeepsUpstreamClampInsteadOfInventingARejection() {
        val region=FillMatrixInput.read(svg("<rect width=\"100\" height=\"100\" inkstitch:row_spacing_mm=\"0\"/>").toByteArray()).regions.single()
        assertEquals(0.0,region.parameters.row_spacing_mm)
        assertEquals(0.1,region.parameters.rowSpacingPx/(96.0/25.4))
        assertEquals(0xff000000.toInt(),region.thread.color)
    }
    @Test fun metadataAndGroupTransformReachTheElement() {
        val input=svg("<g transform=\"translate(2,3)\" inkstitch:fill_underlay=\"false\"><path d=\"M0 0 H10 V10 H0 Z\"/></g>",
            "<inkstitch:inkstitch_svg_version>4</inkstitch:inkstitch_svg_version><inkstitch:min_stitch_len_mm>0.2</inkstitch:min_stitch_len_mm><inkstitch:collapse_len_mm>2</inkstitch:collapse_len_mm>")
        val doc=FillMatrixInput.read(input.toByteArray())
        assertEquals(0.2,doc.minStitchMm);assertEquals(2.0,doc.collapseMm)
        assertFalse(doc.regions.single().parameters.fill_underlay)
        assertEquals(10+2*0.755906,doc.regions.single().geometry.envelopeInternal.minX,1e-8)
    }
    @Test fun sourceBowtieKeepsBothFacesAndCrossingShapeIsAnArea() {
        val bowtie=FillMatrixInput.read(svg("<path d=\"M0 0 L100 100 L0 100 L100 0 Z\" fill-rule=\"evenodd\"/>").toByteArray()).regions.single().geometry
        assertEquals(2,FillGeometry.polygons(bowtie).size)
        assertEquals(5000*0.755906*0.755906,bowtie.area,1e-7)
    }
    @Test fun unsupportedCssAndExternalEntityAreExplicitErrors() {
        assertFails { FillMatrixInput.read(svg("<path d=\"M0 0 H10 V10Z\" style=\"fill:red\"/>").toByteArray()) }
        assertFails { FillMatrixInput.read(("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///does-not-exist'>]>"+svg()).toByteArray()) }
    }
    @Test fun tinySourceFaceIsFilteredBeforeUnderlayWhileTheLargeFaceRemains() {
        val document=FillMatrixInput.read(svg("<path d=\"M0 0 L100 100 L0 100 L2 0 Z\" fill-rule=\"evenodd\"/>").toByteArray())
        val parts=FillGeometry.polygons(document.regions.single().geometry)
        assertEquals(1,parts.size)
        assertTrue(parts.single().area>3)
    }
}
