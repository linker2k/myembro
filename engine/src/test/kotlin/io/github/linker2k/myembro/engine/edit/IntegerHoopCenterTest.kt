// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.SewoutLimits
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.Polyline
import kotlin.test.*

class IntegerHoopCenterTest {
    private fun pattern(min:Int,span:Int)=EmbPattern(listOf(
        Stitch(min,min,Command.JUMP),Stitch(min,min,Command.STITCH),
        Stitch(min+span,min+span,Command.STITCH),Stitch(min+span,min+span,Command.END)),
        listOf(EmbThread(0xff336699.toInt())))

    @Test fun requestedTieCasesAreIdempotentAndUseNegativeHalfUnitBias() {
        for((lo,hi,targetLo,targetHi) in listOf(listOf(-499,500,-500,499),
            listOf(-500,499,-500,499),listOf(-425,425,-425,425))) {
            val centered=StitchObject.recenter(pattern(lo,hi-lo))
            assertEquals(Bounds(targetLo,targetLo,targetHi,targetHi),centered.bounds)
            assertEquals(centered.stitches,StitchObject.recenter(centered).stitches)
        }
    }
    @Test fun offsetsOnBothSidesAndParitiesFitExactlyUntil999() {
        var cases=0
        for(span in listOf(998,999,1000)) for(min in listOf(-1002,-1001,-501,-500,-499,-1,0,1,410,411)) {
            val original=pattern(min,span)
            val centered=StitchObject.recenter(original)
            val lo=-Math.floorDiv(span+1,2);val hi=span+lo
            assertEquals(Bounds(lo,lo,hi,hi),centered.bounds)
            assertEquals(span<=999,SewoutLimits.placedFits(HoopSpec.H100,centered.bounds!!),"span=$span min=$min")
            assertEquals(original.stitchCount,centered.stitchCount)
            assertEquals(original.stitches.map { it.command },centered.stitches.map { it.command })
            assertEquals(original.stitches.zipWithNext().map { (a,b)-> b.x-a.x to b.y-a.y },
                centered.stitches.zipWithNext().map { (a,b)-> b.x-a.x to b.y-a.y })
            cases++
        }
        println("INTEGER_CENTER_BOUNDARIES $cases/30 PASS: 998/999 fit; 1000 blocked")
    }
    @Test fun evenAndOddHoopsUseTheirExistingIndexLimitsWithoutExtraMargin() {
        for(width in listOf(999,1000,1001)) {
            val hoop=HoopSpec("index test",width,width)
            for(span in listOf(width-1,width)) {
                val bounds=StitchObject.recenter(pattern(37,span)).bounds!!
                assertEquals(span<width,SewoutLimits.placedFits(hoop,bounds))
            }
        }
    }
    @Test fun importedSourceBytesAndNormalizedOutputRemainUnchanged() {
        val bytes=checkNotNull(javaClass.getResourceAsStream("/agent/builtins/sample_heart_running.pes")).readBytes()
        val obj=StitchObject.fromBytes("import","sample",bytes,"sample.pes")
        val original=obj.source
        val centered=obj.localPattern()
        assertContentEquals(bytes,obj.sourceBytes)
        assertEquals(original.stitchCount,centered.stitchCount)
        assertContentEquals(PesWriter.write(original),PesWriter.write(centered))
        assertEquals(centered.stitches,obj.withTransform(Transform(10.0,-7.0)).withTransform(Transform()).placedPattern().stitches)
        // Existing exact integer centers have no placement change.
        assertEquals(pattern(-425,850).stitches,StitchObject.recenter(pattern(-425,850)).stitches)
    }
    @Test fun photoFactoryOnlyChangesIntegerPoseAndSurvivesRenameAndCodec() {
        val source=pattern(-499,999)
        val region=ColorDrawingPipeline.Region(source.threads.single(),StitchTypeRule.Widths(99.9,99.9),
            StitchTypeRule.Type.TATAMI,1,paths=listOf(Polyline(listOf(Point(-49.9,-49.9),Point(50.0,50.0)),true)))
        val result=ColorDrawingPipeline.Result(source,listOf(region),mapOf("region-0" to source))
        val obj=assertNotNull(ShapeObjectFactory.fromPhoto("photo","photo",result,99.9))
        assertEquals(source.stitches,obj.localPattern().stitches)
        assertEquals(Transform(-1.0,-1.0),obj.transform)
        assertEquals(Bounds(-500,-500,499,499),obj.placedBounds())
        assertEquals(source.stitchCount,obj.placedPattern().stitchCount)
        assertEquals(source.stitches,obj.regionPattern("region-0").stitches)
        val twice=ShapeObjectFactory.centeredForImport(obj)
        assertSame(obj,twice)
        val renamed=ShapeObjectFactory.renamed(obj,"renamed","renamed")
        assertEquals(obj.transform,renamed.transform)
        val decoded=ProjectJson.decode(ProjectJson.encode(Project(objects=listOf(renamed)))).objects.single()
        assertEquals(obj.transform,decoded.transform)
        assertEquals(obj.placedPattern().stitches,decoded.placedPattern().stitches)
    }
}
