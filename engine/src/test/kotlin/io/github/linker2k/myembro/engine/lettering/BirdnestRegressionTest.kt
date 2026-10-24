// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.PatternStartAnalysis
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class BirdnestRegressionTest {
    @Test fun publicScriptAt34MmUsesRunningInsteadOfUnvalidatedSatinOrFill() {
        val text=LetteringAuditGlyphs.script()
        val p=text.localPattern()
        assertSewingLengths(p)
        val satin=text.copy(stitchStyle=StitchStyle.SATIN).localPattern()
        assertTrue(satin.warnings.isNotEmpty())
        assertEquals(p.stitches,satin.stitches)
        val lockIndices=p.metadata.getValue(GeneratedPatternProcessor.LOCK_INDICES_KEY).split(',').map(String::toInt).toSet()
        // Locks may revisit cells and retain source short fractions. Do not require PE-Design bytes.
        assertTrue(PatternStartAnalysis.starts(p).all { it.penetrations > 0 })
        for(i in p.stitches.indices.filter { p.stitches[it].command in setOf(Command.TRIM,Command.END) }) {
            val tail=(i-1 downTo 0).takeWhile { p.stitches[it].command==Command.STITCH }
            assertTrue(tail.count { it in lockIndices }>=2,"Missing end lock before $i")
        }
        for(version in listOf(PesVersion.V1,PesVersion.V6)) {
            val decoded=PesReader.read(PesWriter.write(p,PesWriteSettings(version,hoop=io.github.linker2k.myembro.engine.edit.HoopSpec.H160x260)))
            assertEquals(PesWriter.prepareForOutput(p).stitchCount,decoded.stitchCount);assertEquals(PesWriter.prepareForOutput(p).bounds,decoded.bounds)
            assertSewingLengths(decoded)
        }
    }
}
