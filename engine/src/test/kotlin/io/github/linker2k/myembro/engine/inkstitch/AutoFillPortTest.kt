// SPDX-License-Identifier: GPL-3.0-or-later
// Transferred unchanged from myembro codex/letter b7106ec361eb5a75eaac54b0cba450cf1041a554; source introduced at 1cbdd569747032a48075ca16e732629b15555d6c.
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
        // Source travel() clamps its path before even_running_stitch(..., tolerance=0.2mm).
        // Needle points remain in the polygon; straight stitch chords can cut a corner by <=0.2mm.
        val needleArea=region.offset(1e-5)
        val chordArea=region.offset(2.0+1e-5) // native units: 0.2mm, not a fitted oracle tolerance.
        paths.forEach { path ->
            path.forEach { assertTrue(needleArea.covers(it),"needle $it outside polygon") }
            path.zipWithNext().forEach { (a,b) ->
                assertTrue(if(a==b) chordArea.covers(a) else chordArea.coversSegment(a,b),"$a -> $b")
            }
        }
    }
}
