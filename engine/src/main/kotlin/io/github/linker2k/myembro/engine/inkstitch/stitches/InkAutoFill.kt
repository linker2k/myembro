// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/tatami_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Native-unit API originated in codex/letter 1cbdd569747032a48075ca16e732629b15555d6c;
// model now delegates to the complete TatamiFill/FillRouting port instead of its reduced graph.
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import org.locationtech.jts.geom.util.AffineTransformation

/** Native 0.1mm compatibility adapter; source routing runs in SVG pixels. */
object InkAutoFill {
    data class Settings(val angleDegrees: Double = 0.0, val rowSpacing: Double = 4.0,
        val stitchLength: Double = 35.0, val travelLength: Double = 25.0,
        val staggers: Int = 4, val underpath: Boolean = true) {
        init { require(rowSpacing.isFinite() && rowSpacing > 0 && stitchLength.isFinite() && stitchLength > 0)
            require(angleDegrees.isFinite() && travelLength.isFinite() && travelLength > 0 && staggers > 0) }
    }

    fun paths(region: PolygonRegion, settings: Settings = Settings(),
        startingPoint: Point? = null, endingPoint: Point? = null, expandMm: Double = 0.0): List<List<Point>> {
        require(expandMm.isFinite())
        if (region.isEmpty) return emptyList()
        val scale=PIXELS_PER_MM/10
        val shape=AffineTransformation.scaleInstance(scale,scale).transform(region.geometry)
        fun px(point:Point?)=point?.let { FillPoint(it.x*scale,it.y*scale) }
        // fill_stitch.py expand_mm is in mm; routing geometry is in SVG pixels.
        // Preserve the exact unexpanded path (including geometry type) at the source default.
        val expanded=if(expandMm==0.0) shape else FillGeometry.shrinkOrGrow(shape,expandMm*PIXELS_PER_MM)
        val points=TatamiFill.fill(expanded,Math.toRadians(settings.angleDegrees),settings.rowSpacing*scale,null,
            settings.stitchLength*scale,settings.travelLength*scale,
            // fill_stitch.py running_stitch_tolerance getter defaults to 0.2mm.
            0.2*PIXELS_PER_MM,settings.staggers.toDouble(),false,px(startingPoint),px(endingPoint),settings.underpath)
        val paths=mutableListOf<List<Point>>()
        var current=mutableListOf<Point>()
        for(point in points) {
            if("jump" in point.tags && current.isNotEmpty()) { paths+=current;current=mutableListOf() }
            current+=Point(point.x/scale,point.y/scale)
        }
        if(current.isNotEmpty()) paths+=current
        return paths
    }
}
