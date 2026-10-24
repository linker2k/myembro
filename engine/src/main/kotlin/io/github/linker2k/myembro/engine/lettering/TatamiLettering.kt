// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.DefaultTatami
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.model.*

/** Coordinator's auto-rules policy; sizes are the nominal 5..90mm sweep domain, not a font certification. */
object TatamiLettering {
    const val MIN_HEIGHT_MM=5.0
    const val MAX_HEIGHT_MM=90.0
    const val MIN_SATIN_WIDTH_MM=0.9
    const val MAX_SATIN_WIDTH_MM=6.0
    fun methodForWidth(widthMm:Double):StitchStyle {
        require(widthMm.isFinite() && widthMm>=0)
        return when { widthMm<MIN_SATIN_WIDTH_MM -> StitchStyle.RUNNING
            widthMm<=MAX_SATIN_WIDTH_MM -> StitchStyle.SATIN
            else -> StitchStyle.FILL }
    }
    fun groups(contoursMm:List<List<Pair<Double,Double>>>,thread:EmbThread):List<StitchGroup> {
        val region=PolygonRegion.fromPaths(contoursMm.map { path ->
            Polyline(path.map { Point(it.first*10,it.second*10) },true)
        },FillRule.EVEN_ODD)
        return DefaultTatami.stitchGroups(region,thread)
    }
    internal fun generateGlyphs(glyphs:List<OutlineGlyph>):EmbPattern {
        val groups=mutableListOf<StitchGroup>()
        for(glyph in glyphs) {
            val next=groups(glyph.contours,glyph.thread)
            if(next.isEmpty())continue
            if(groups.isNotEmpty()) groups[groups.lastIndex]=groups.last().copy(trimAfter=true)
            groups+=next
        }
        val pattern=GeneratedPatternProcessor.fromGroups(groups).centeredGenerated()
        return EmbPattern(pattern.stitches,pattern.threads,pattern.metadata+mapOf("myembro.fill.backend" to "inkstitch-default-tatami-underlay"),pattern.warnings)
    }
}
