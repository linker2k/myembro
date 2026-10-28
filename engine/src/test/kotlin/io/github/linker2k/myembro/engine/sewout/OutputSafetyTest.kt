// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class OutputSafetyTest {
    private fun p(vararg points:Pair<Int,Int>)=EmbPattern(points.map { (x,y)->Stitch(x,y,Command.STITCH) },
        listOf(PecThreads.forIndex(5)),mapOf(GeneratedPatternProcessor.FINISHED_KEY to "v1"))
    private fun raw(pattern:EmbPattern):ByteArray {
        val header=ByteArray(22);"#PES0001".toByteArray().copyInto(header);header[8]=22
        return header+PecWriter.writePayload(pattern)
    }
    @Test fun generatedUnsafeLocksLengthAndDensityCannotBeSavedByEitherWriter() {
        val cases=listOf(
            "MISSING_LOCK_STITCH" to p(100 to 0,100 to 100,0 to 100,0 to 0,100 to 0),
            "LONG_STITCH" to p(10 to 0,0 to 10,121 to 10),
            "DENSITY_HOTSPOT" to p(*Array(32) { i->i%2 to i%2 }),
        )
        for((code,pattern)in cases)for(version in PesVersion.entries) {
            val e=assertFailsWith<IllegalArgumentException> { PesWriter.write(pattern,version) }
            assertTrue(e.message!!.contains(code),"$code: ${e.message}")
        }
    }
    @Test fun zeroAndRepeatAreMeasuredOnActualWireCommands() {
        val pattern=p(0 to 0,0 to 0,0 to 0,40 to 0,0 to 0)
        val bytes=raw(pattern)
        val errors=PesOutputSafety.errors(bytes,HoopSpec.H100,pattern)
        assertTrue(errors.any { it.startsWith("PEC_ZERO_MOVE:") })
        assertTrue(errors.any { it.startsWith("REPEATED_PENETRATION:") })
        val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
        assertTrue(report.findings.any { it.code==FindingCode.ZERO_LENGTH_MOTION && it.severity==Severity.BLOCKER })
        val imported=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.IMPORTED))
        assertTrue(imported.findings.filter { it.code==FindingCode.ZERO_LENGTH_MOTION }.all { it.severity==Severity.WARNING })
    }
    @Test fun actualDefaultHalfStitchOutputSurvivesTheFinalWriterBoundary() {
        val source=GeneratedPatternProcessor.finish(EmbPattern(p(100 to 0,100 to 100,0 to 100,0 to 0,100 to 0).stitches,
            listOf(PecThreads.forIndex(5))))
        for(version in PesVersion.entries) {
            val bytes=PesWriter.write(source,version)
            assertTrue(PesOutputSafety.errors(bytes,HoopSpec.H100,source).isEmpty())
        }
    }
}
