// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.DefaultTatami
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*
import kotlinx.serialization.json.*

class TatamiLetteringIntegrationTest {
    private fun box(x:Double,y:Double,w:Double,h:Double)=listOf(x to y,x+w to y,x+w to y+h,x to y+h,x to y)
    @Test fun adoptedWidthBoundariesAreInclusiveForSatin() {
        assertEquals(StitchStyle.RUNNING,TatamiLettering.methodForWidth(0.8999))
        assertEquals(StitchStyle.SATIN,TatamiLettering.methodForWidth(0.9))
        assertEquals(StitchStyle.SATIN,TatamiLettering.methodForWidth(6.0))
        assertEquals(StitchStyle.FILL,TatamiLettering.methodForWidth(6.0001))
    }
    @Test fun ttfOutlineAutoUsesFillAndSweepUpperBoundIs90() {
        val policy=AutomaticLetteringPolicy(emptyList())
        assertEquals(StitchStyle.FILL,policy.method(emptyList(),90.0,LetteringGeometry.OUTLINE))
        assertEquals(StitchStyle.RUNNING,policy.method(emptyList(),90.01,LetteringGeometry.OUTLINE))
        val rules=listOf(java.io.File("../docs/tatami-sweep-results/fill-allowed-regions.json"),
            java.io.File("docs/tatami-sweep-results/fill-allowed-regions.json")).first { it.isFile }
        val grid=Json.parseToJsonElement(rules.readText()).jsonObject.getValue("size_grid_mm").jsonArray.map { it.jsonPrimitive.double }
        assertEquals(grid.max(),TatamiLettering.MAX_HEIGHT_MM)
        assertEquals(grid.min(),TatamiLettering.MIN_HEIGHT_MM)
    }
    @Test fun filledContoursUseTheExactDefaultTatamiGroupsWithoutLegacyExtras() {
        val paths=listOf(box(0.0,0.0,20.0,20.0),box(6.0,6.0,8.0,8.0))
        val thread=EmbThread(0xff245b85.toInt(),"Blue")
        val actual=TatamiLettering.generateGlyphs(listOf(OutlineGlyph(paths,thread)))
        val region=PolygonRegion.fromPaths(paths.map { Polyline(it.map { p -> Point(p.first*10,p.second*10) },true) },FillRule.EVEN_ODD)
        val expected=GeneratedPatternProcessor.fromGroups(DefaultTatami.stitchGroups(region,thread)).centeredGenerated()
        assertEquals(expected.stitches,actual.stitches)
        assertEquals("inkstitch-default-tatami-underlay",actual.metadata["myembro.fill.backend"])
        assertEquals(PesWriter.prepareForOutput(actual).stitchCount,PesReader.read(PesWriter.writeV6(actual)).stitchCount)
    }
    @Test fun koreanOutlineCodepointsKeepOwnersThreadsAndDefaultFill() {
        // Original square/ring glyph contours model separated Hangul components; no personal font assets.
        val contours=listOf(box(0.0,0.0,20.0,20.0),box(5.0,5.0,10.0,10.0),box(25.0,0.0,16.0,20.0))
        val p=LetteringEngine.fromContours(contours,heightMm=20.0,sourceHeight=20.0,
            stitchStyle=StitchStyle.FILL,outline=true,contourCharacters=listOf('자'.code,'자'.code,'수'.code))
        assertEquals("inkstitch-default-tatami-underlay",p.metadata["myembro.fill.backend"])
        assertTrue(p.stitchCount>0)
        assertTrue(p.stitches.any { it.command==Command.TRIM })
    }
    @Test fun bundledKoreanTtfRoutesToDefaultFillAndReadablePes() {
        val file=listOf(java.io.File("../app/src/main/res/font/jua_regular.ttf"),java.io.File("app/src/main/res/font/jua_regular.ttf")).first { it.isFile }
        val font=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,file).deriveFont(1000f)
        val text="자수";assertTrue(text.all { font.canDisplay(it) })
        val vector=font.createGlyphVector(java.awt.font.FontRenderContext(null,true,true),text)
        val contours=mutableListOf<List<Pair<Double,Double>>>()
        val owners=mutableListOf<Int>()
        for(index in 0 until vector.numGlyphs) {
            val iterator=java.awt.geom.FlatteningPathIterator(vector.getGlyphOutline(index).getPathIterator(null),0.2)
            var path=mutableListOf<Pair<Double,Double>>();val coordinates=DoubleArray(6)
            while(!iterator.isDone) {
                when(iterator.currentSegment(coordinates)) {
                    java.awt.geom.PathIterator.SEG_MOVETO -> path=mutableListOf(coordinates[0] to coordinates[1])
                    java.awt.geom.PathIterator.SEG_LINETO -> path+=coordinates[0] to coordinates[1]
                    java.awt.geom.PathIterator.SEG_CLOSE -> { if(path.size>=3) { path+=path.first();contours+=path;owners+=index } }
                }
                iterator.next()
            }
        }
        val all=contours.flatten();val reference=all.maxOf { it.second }-all.minOf { it.second }
        val p=LetteringEngine.fromContours(contours,heightMm=20.0,sourceHeight=reference,
            stitchStyle=StitchStyle.FILL,outline=true,contourCharacters=owners)
        assertEquals("inkstitch-default-tatami-underlay",p.metadata["myembro.fill.backend"])
        val bytes=PesWriter.writeV6(p)
        assertEquals(PesWriter.prepareForOutput(p).stitchCount,PesReader.read(bytes).stitchCount)
        java.io.File("build/s3-fill").apply { mkdirs() }.resolve("jua-korean.pes").writeBytes(bytes)
        println("JUA 자수: ${p.stitchCount} stitches; ${p.bounds!!.width/10.0} x ${p.bounds!!.height/10.0} mm")
    }
    @Test fun railWidthAbove6RoutesToDefaultFill() {
        val p=SatinColumn.generate(listOf(0.0 to 0.0,0.0 to 20.0),listOf(6.01 to 0.0,6.01 to 20.0))
        assertEquals("inkstitch-default-tatami-underlay",p.metadata["myembro.fill.backend"])
        assertTrue(p.stitchCount>0)
    }
}
