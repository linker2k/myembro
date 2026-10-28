// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.inkstitch.elements.DefaultTatami
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.EmbThread
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.WKTReader
import kotlin.test.*

class PhotoIslandOrderTest {
    private val region=PolygonRegion(WKTReader().read("MULTIPOLYGON (((0 0,200 0,200 200,0 200,0 0),(60 60,60 140,140 140,140 60,60 60)),((600 0,700 0,700 100,600 100,600 0)),((300 0,400 0,400 100,300 100,300 0)))"))

    @Test fun usesExistingImprovementOnlyRouteWithFixedFirstIslandAndAllHoles() {
        val original=(0 until region.geometry.numGeometries).map { region.geometry.getGeometryN(it) as Polygon }
        val anchors=original.map { p -> p.exteriorRing.coordinates.map { Point(it.x,it.y) } }
        val route=PhotoOutline.route(anchors)
        val actual=PhotoIslandOrder.polygons(region)
        assertTrue(route.after<route.before)
        assertSame(original.first(),actual.first())
        assertEquals(original.toSet(),actual.toSet())
        assertEquals(route.paths,actual.map { p -> p.exteriorRing.coordinates.map { Point(it.x,it.y) } })
        assertEquals(1,actual.sumOf { it.numInteriorRing })
        assertEquals(actual,PhotoIslandOrder.polygons(region))
        val alreadyOrdered=PolygonRegion(region.geometry.factory.createMultiPolygon(actual.toTypedArray()))
        assertEquals(actual,PhotoIslandOrder.polygons(alreadyOrdered))
    }

    @Test fun islandFillIsUnchangedAndImmediatelyFollowedByItsOutlineThenTrim() {
        val thread=EmbThread(0xFFB5492A.toInt())
        val ordered=PhotoIslandOrder.polygons(region)
        val selection=RedworkPipeline.silhouetteGroups(region,thread,HoopSpec.H100)
        assertTrue(selection.skipped.isEmpty())
        var offset=0
        ordered.forEachIndexed { index,polygon ->
            val fill=DefaultTatami.stitchGroups(PolygonRegion(polygon),thread)
            val outline=ColorFillOutline.stitchGroups(polygon,thread)
            val count=fill.size+outline.size
            val actual=selection.groups.subList(offset,offset+count)
            assertEquals((fill+outline).map { it.stitches },actual.map { it.stitches })
            assertTrue(actual.dropLast(1).none { it.trimAfter })
            assertEquals(index<ordered.lastIndex,actual.last().trimAfter)
            offset+=count
        }
        assertEquals(selection.groups.size,offset)
        assertTrue(selection.groups.all { it.color==thread })
        assertTrue(ColorFillOutline.safe(selection.groups,HoopSpec.H100))
    }
}
