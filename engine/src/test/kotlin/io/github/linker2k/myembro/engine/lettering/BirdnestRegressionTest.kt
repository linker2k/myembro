// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.PatternStartAnalysis
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.math.floor
import kotlin.test.*

class BirdnestRegressionTest {
    @Test fun publicScriptAt34MmUsesAutoFillInsteadOfUnvalidatedSkeletonSatin() {
        val text=LetteringAuditGlyphs.script()
        val p=text.localPattern()
        assertSewingLengths(p)
        val cells=bodyStitches(p).groupingBy { floor(it.x/5.0).toInt() to floor(it.y/5.0).toInt() }.eachCount()
        assertTrue(cells.values.max()<=8)
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
            val decoded=PesReader.read(PesWriter.write(p,version))
            assertEquals(p.stitchCount,decoded.stitchCount);assertEquals(p.bounds,decoded.bounds)
            assertSewingLengths(decoded)
        }
    }
}
