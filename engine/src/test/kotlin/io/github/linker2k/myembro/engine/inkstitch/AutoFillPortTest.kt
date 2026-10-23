// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.inkstitch.stitches.InkAutoFill
import kotlin.test.*

class AutoFillPortTest {
    @Test fun autoFillGraphPreservesHolesAndCoversDisconnectedComponents() {
        fun box(x:Double,y:Double,w:Double,h:Double)=Polyline(listOf(Point(x,y),Point(x+w,y),Point(x+w,y+h),Point(x,y+h)),true)
        val region=PolygonRegion.fromPaths(listOf(box(0.0,0.0,120.0,100.0),box(30.0,30.0,60.0,40.0),box(160.0,0.0,40.0,100.0)),FillRule.EVEN_ODD)
        val paths=InkAutoFill.paths(region)
        assertTrue(paths.size>=2)
        assertTrue(paths.flatten().any { it.x>150 })
        paths.forEach { path -> path.zipWithNext().forEach { (a,b) -> assertTrue(region.offset(1e-5).coversSegment(a,b),"$a -> $b") } }
    }
}
