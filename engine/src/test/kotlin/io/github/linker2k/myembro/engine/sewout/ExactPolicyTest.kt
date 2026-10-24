// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import java.io.File
import kotlin.test.*

class ExactPolicyTest {
    private fun bytes(maxX:Int,maxY:Int,minX:Int=0,minY:Int=0):ByteArray {
        // Test-only raw container: deliberately includes invalid coordinates/header variants.
        val pattern=EmbPattern(listOf(Stitch(maxX,minY,Command.STITCH),Stitch(minX,maxY,Command.STITCH),
            Stitch(maxX,minY,Command.STITCH)),listOf(PecThreads.forIndex(5)))
        val header=ByteArray(22);"#PES0001".toByteArray().copyInto(header);header[8]=22
        return header+PecWriter.writePayload(pattern)
    }
    @Test fun everyHoopUsesTheExactExclusiveIntegerSizeBoundary() {
        val hoops=HoopSpec.ALL+HoopSpec("SE400",1000,1000)+HoopSpec("custom",731,1247)
        for(hoop in hoops)for(axis in 0..1)for(delta in -1..1) {
            val max=if(axis==0)hoop.width-1+delta else hoop.height-1+delta
            val data=if(axis==0)bytes(max,20) else bytes(20,max)
            assertEquals(delta>0,PecViewportCheck.check(data,hoop).errors.any { it.startsWith("PEC_HOOP:") },"$hoop axis=$axis delta=$delta")
            val report=SewoutCheck.checkPes(data,options=SewoutOptions(hoop=hoop))
            assertEquals(delta>0,report.findings.any { it.code==FindingCode.HOOP_EXCEEDED && it.message.startsWith("PEC_HOOP:") })
        }
    }
    @Test fun exactOriginAndHeaderHaveNoOneUnitTolerance() {
        for(axis in 0..1)for(min in -1..1) {
            val data=if(axis==0)bytes(20,20,minX=min) else bytes(20,20,minY=min)
            assertEquals(min==0,PecViewportCheck.check(data,HoopSpec.H100).valid)
        }
        for(axis in 0..1)for(delta in -1..1) {
            val data=bytes(20,20);val index=22+520+axis*2
            data[index]=(20+delta).toByte()
            assertEquals(delta==0,PecViewportCheck.check(data,HoopSpec.H100).valid)
        }
    }
    @Test fun writerRejectsOutOfHoopAndOriginViolationsBeforeReturningBytes() {
        for(hoop in HoopSpec.ALL+HoopSpec("custom",731,1247))for(delta in -1..1) {
            val max=SewoutLimits.maxDesignWidthUnits0p1mm(hoop)+delta
            val p=EmbPattern(listOf(Stitch(max,0,Command.STITCH),Stitch(0,20,Command.STITCH),
                Stitch(max,0,Command.STITCH)),listOf(PecThreads.forIndex(5)))
            for(v in PesVersion.entries) {
                if(delta>0)assertFailsWith<IllegalArgumentException> { PesWriter.write(p,PesWriteSettings(v,hoop=hoop)) }
                else assertTrue(PecViewportCheck.check(PesWriter.write(p,PesWriteSettings(v,hoop=hoop)),hoop).valid)
            }
        }
        assertFailsWith<IllegalArgumentException> { PesWriter.write(EmbPattern(emptyList(),emptyList())) }
        val open=EmbPattern(listOf(Stitch(0,0,Command.STITCH),Stitch(10,10,Command.STITCH)),listOf(PecThreads.forIndex(5)))
        for(v in PesVersion.entries)assertFailsWith<IllegalArgumentException> { PesWriter.write(open,v) }
    }
    @Test fun allSafetyCountLimitsUseNMinusOneNAndNPlusOne() {
        fun report(count:Int,generated:Boolean,spread:Boolean)=SewoutCheck.check(EmbPattern(List(count) { i->
            Stitch(if(spread)i%2 else 0,if(spread)i%2 else 0,Command.STITCH) },listOf(PecThreads.forIndex(5))),
            options=SewoutOptions(origin=if(generated)DesignOrigin.APP_GENERATED else DesignOrigin.IMPORTED))
        for((generated,n) in listOf(true to SewoutLimits.MAX_GENERATED_PENETRATIONS_PER_CELL,false to SewoutLimits.MAX_IMPORTED_PENETRATIONS_PER_CELL))
            for(count in n-1..n+1)assertEquals(count>n,report(count,generated,true).findings.any {
                it.code==FindingCode.DENSITY_HOTSPOT && it.severity==Severity.BLOCKER })
        val n=SewoutLimits.MAX_CONSECUTIVE_POSITIONS
        for(count in n-1..n+1)assertEquals(count>n,report(count,true,false).findings.any { it.code==FindingCode.REPEATED_PENETRATION })
        val length=SewoutLimits.MAX_STITCH_LENGTH_UNITS_0P1MM
        for(dx in length-1..length+1)assertEquals(dx>length,SewoutLimits.exceedsStitchLength(dx.toLong(),0))
        assertFalse(SewoutLimits.exceedsStitchLength(72,96));assertTrue(SewoutLimits.exceedsStitchLength(72,97))
        assertTrue(SewoutLimits.exceedsStitchLength(Int.MAX_VALUE.toLong()-Int.MIN_VALUE,0))
        for(generated in listOf(false,true)) {
            val r=SewoutCheck.check(EmbPattern(listOf(Stitch(0,0,Command.STITCH),Stitch(121,0,Command.STITCH)),listOf(PecThreads.forIndex(5))),
                options=SewoutOptions(origin=if(generated)DesignOrigin.APP_GENERATED else DesignOrigin.IMPORTED))
            assertEquals(if(generated)Severity.BLOCKER else Severity.WARNING,r.findings.single { it.code==FindingCode.LONG_STITCH }.severity)
        }
    }
    @Test fun safetyMarginSizeAndPhysicalPlacementBoundaries() {
        for(hoop in HoopSpec.ALL+HoopSpec("custom",731,1247))for(axis in 0..1)for(delta in -1..1) {
            val max=(if(axis==0)SewoutLimits.maxDesignWidthUnits0p1mm(hoop) else SewoutLimits.maxDesignHeightUnits0p1mm(hoop))+delta
            val data=if(axis==0)bytes(max,20) else bytes(20,max)
            assertEquals(delta<=0,PecViewportCheck.check(data,hoop).valid)
        }
        val area=io.github.linker2k.myembro.engine.edit.SewingArea(-500.0,-500.0,499.0,499.0)
        for(max in 498..500)assertEquals(max<=499,area.contains(io.github.linker2k.myembro.engine.model.Bounds(-10,-10,max,10)))
        assertEquals(SewoutLimits.MAX_CONSECUTIVE_POSITIONS,SewoutOptions().repeatedPointThreshold)
    }
    @Test fun agentsDocumentAndExecutableLimitsMustAgree() {
        val doc=File("../AGENTS.md").readText()
        fun row(n:Int)=doc.lineSequence().single { it.startsWith("| P$n |") }
        fun number(n:Int,regex:String)=Regex(regex).find(row(n))!!.groupValues[1].toDouble()
        assertEquals(SewoutLimits.PEC_MIN_COORD_UNITS_0P1MM.toDouble(),number(1,"minimum x = (\\d+)"))
        assertEquals(SewoutLimits.PEC_MIN_COORD_UNITS_0P1MM.toDouble(),number(1,"minimum y = (\\d+)"))
        assertEquals(SewoutLimits.hoopMaxXUnits0p1mm(HoopSpec.H100).toDouble(),number(2,"0[–-](\\d+)"))
        assertEquals(SewoutLimits.MAX_CONSECUTIVE_POSITIONS.toDouble(),number(5,"\\*\\*at most (\\d+)\\*\\*"))
        assertEquals(SewoutLimits.MAX_GENERATED_PENETRATIONS_PER_CELL.toDouble(),number(7,"more than (\\d+) needle penetrations"))
        assertEquals(SewoutLimits.MAX_IMPORTED_PENETRATIONS_PER_CELL.toDouble(),number(7,"blocked only above (\\d+)"))
        assertEquals(SewoutLimits.DENSITY_CELL_UNITS_0P1MM/10.0,number(7,"([0-9.]+)mm cell"))
        assertEquals(SewoutLimits.MAX_STITCH_LENGTH_UNITS_0P1MM/10.0,number(8,"≤ ([0-9.]+)mm"))
        val defaults=io.github.linker2k.myembro.engine.inkstitch.stitch_plan.LockStitchParameters()
        val documentedLock=Regex("`([^`]+)`").find(row(6))!!.groupValues[1]
        assertEquals(documentedLock,defaults.lock_start)
        assertEquals(documentedLock,defaults.lock_end)
        assertEquals(documentedLock,io.github.linker2k.myembro.engine.inkstitch.stitch_plan.InkLockStitch().lock_id)
        assertTrue(row(6).contains("ties=3"))
        assertTrue(row(3).contains("Not even a 1-unit (0.1mm) difference is allowed"))
        assertEquals(0,SewoutLimits.PEC_DIMENSION_TOLERANCE_UNITS_0P1MM)
        assertTrue(row(4).contains("**0-length JUMPs"))
        assertEquals(0,SewoutLimits.MAX_ZERO_LENGTH_MOTION_COMMANDS)
        val margin=doc.lineSequence().single { it.startsWith("| P2a |") }
        val units=Regex("\\(([0-9]+) unit\\)").find(margin)!!.groupValues[1].toInt()
        assertEquals(SewoutLimits.HOOP_SAFETY_MARGIN_UNITS_0P1MM,units)
    }
}
