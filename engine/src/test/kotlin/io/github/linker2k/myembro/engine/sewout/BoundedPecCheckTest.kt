// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class BoundedPecCheckTest {
    private fun bytes(vararg instructions: Int): ByteArray {
        val p=12
        val data=ByteArray(p+528+instructions.size)
        "#PES0001".toByteArray().copyInto(data)
        data[8]=p.toByte()
        "LA:".toByteArray().copyInto(data,p)
        val length=16+instructions.size
        for(i in 0..2) data[p+514+i]=(length shr (8*i)).toByte()
        instructions.forEachIndexed { i,value -> data[p+528+i]=value.toByte() }
        return data
    }
    @Test fun combinedTrimJumpPreservesThreadBoundaryBeforeMovement() {
        val commands=boundedPecStitches(bytes(0xb0,10,0,0xff,0))
        assertEquals(listOf(Stitch(0,0,Command.TRIM),Stitch(10,0,Command.JUMP),
            Stitch(10,0,Command.END)),commands)
    }
    @Test fun endMustBeInsideDeclaredBlockAndLongInstructionMustBeComplete() {
        assertFailsWith<IllegalArgumentException> { boundedPecStitches(bytes(0x80,0xff)) }
        assertFailsWith<IllegalStateException> { boundedPecStitches(bytes(0,0)) }
        val oversized=bytes(0xff,0).also { it[12+514]=127 }
        assertFailsWith<IllegalArgumentException> { boundedPecStitches(oversized) }
        assertEquals(Command.END,boundedPecStitches(bytes(0xff)).single().command)
    }
    @Test fun zeroOnlyControlPlaceholdersDoNotCreateMissingLockBoundaries() {
        val pattern=EmbPattern(listOf(Stitch(0,0,Command.STITCH),Stitch(0,0,Command.TRIM),
            Stitch(0,0,Command.STITCH),Stitch(0,0,Command.STITCH),Stitch(40,0,Command.STITCH),
            Stitch(40,0,Command.STITCH),Stitch(40,0,Command.END)),listOf(EmbPattern.DEFAULT_PALETTE[0]))
        val report=SewoutCheck.check(pattern,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
        assertTrue(report.findings.none { it.code==FindingCode.MISSING_LOCK_STITCH })
        assertEquals(0,report.statistics.missingStartLocks)
        assertEquals(0,report.statistics.missingEndLocks)
    }
}
