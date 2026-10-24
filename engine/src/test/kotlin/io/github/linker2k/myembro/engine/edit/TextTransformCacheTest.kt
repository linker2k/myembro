// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import kotlin.math.roundToInt
import kotlin.test.*

class TextTransformCacheTest {
    private fun text()=TextObject("glyph",text="synthetic",fontKind=FontKind.OUTLINE,heightMm=12.0,
        contours=listOf(listOf(0.0 to 0.0,20.0 to 0.0,20.0 to 30.0,0.0 to 30.0,0.0 to 0.0)),
        contourSourceHeight=30.0,singleLine=false,stitchStyle=StitchStyle.FILL)

    @Test fun rigidEditsRetainExactLocalStitchesAndOnlyMapFinishedCoordinates() {
        val original=text();val source=original.localPattern()
        for(t in listOf(Transform(x=2.37,y=-5.19),Transform(rotationDeg=23.4),Transform(x=-150.0,y=62.5,rotationDeg=-83.0))) {
            val moved=original.withTransform(t)
            assertSame(source,moved.localPattern())
            val expected=GeneratedPatternProcessor.mapCoordinates(source) { x,y ->
                val (px,py)=t.apply(x.toDouble(),y.toDouble());px.roundToInt() to py.roundToInt()
            }
            assertEquals(expected.stitches,moved.placedPattern().stitches)
            assertEquals(source.stitchCount,moved.placedPattern().stitchCount)
        }
        assertSame(source,original.withId("copy").withVisible(false).localPattern())
    }
    @Test fun scalingMatchesOriginalGenerationExactlyAndReusesResultAcrossRigidEdits() {
        val original=text()
        val t=Transform(scaleX=1.2,scaleY=0.9)
        val expected=placeDesignPattern(original.copy(transform=t))
        val scaled=original.withTransform(t)
        assertEquals(expected.stitches,scaled.placedPattern().stitches)
        assertEquals(expected.metadata,scaled.placedPattern().metadata)
        assertSame(scaled.placedPattern(),scaled.withTransform(t).placedPattern())
        assertSame(original.localPattern(),scaled.localPattern())
        assertNotSame(original.localPattern(),original.copy(heightMm=14.0).localPattern())
    }
    @Test fun reportsBaselineRebuildVersusCachedAffinePreviewCost() {
        val original=text();original.localPattern()
        val count=6
        val before=(0 until count).map { i ->
            val start=System.nanoTime()
            placeDesignPattern(original.copy(transform=Transform(x=i*.37,rotationDeg=i*.7,scaleX=1+i*.01,scaleY=1+i*.01)))
            (System.nanoTime()-start)/1e6
        }
        val after=(0 until count).map { i ->
            val start=System.nanoTime()
            val obj=original.withTransform(Transform(x=i*.37,rotationDeg=i*.7,scaleX=1+i*.01,scaleY=1+i*.01))
            val b=obj.localPattern().bounds!!
            listOf(b.minX to b.minY,b.maxX to b.minY,b.maxX to b.maxY,b.minX to b.maxY)
                .forEach { (x,y) -> obj.transform.apply(x.toDouble(),y.toDouble()) }
            (System.nanoTime()-start)/1e6
        }
        println("TEXT_TRANSFORM_FRAME_JVM before_mean_ms=${before.average()} before_max_ms=${before.max()} after_mean_ms=${after.average()} after_max_ms=${after.max()} frames=$count")
    }
}
