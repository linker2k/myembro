// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.export

import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class PreflightTest {
    private fun pattern(width: Int = 1000, height: Int = 1000, count: Int = 2): EmbPattern = EmbPattern(
        List(count) { Stitch(if (it % 2 == 0) 0 else width, if (it % 2 == 0) 0 else height, Command.STITCH) }, emptyList())

    @Test fun exactAreaStitchAndTransferCountBoundariesPass() {
        assertTrue(Preflight.check(pattern(count = 30000), "design_A-1.pes", patternCount = 12).isEmpty())
        assertTrue(Preflight.check(pattern(), "a".repeat(100) + ".pes").isEmpty()) // no guessed PES 8-char limit
        assertTrue(Preflight.check(pattern(), "design.dst").isEmpty())
    }

    @Test fun confirmedLimitsProduceKoreanWarnings() {
        val warnings = Preflight.check(pattern(1001, 1001, 30001), "한글 이름.pes", patternCount = 13)
        assertEquals(4, warnings.size)
        assertTrue(warnings.any { "100×100mm" in it })
        assertTrue(warnings.any { "30000땀" in it && "30001땀" in it })
        assertTrue(warnings.any { "file names" in it })
        assertTrue(warnings.any { "12개" in it })
    }

    @Test fun internalMemoryLimitIsNotAppliedToPcTransferBytes() {
        assertTrue(Preflight.check(pattern(), "a.pes", totalBytes = 600000).isEmpty())
        assertTrue(Preflight.check(pattern(), "a.pes", destination = ExportDestination.INTERNAL_MEMORY,
            totalBytes = 512L * 1024).isEmpty())
        assertEquals(1, Preflight.check(pattern(), "a.pes", destination = ExportDestination.INTERNAL_MEMORY,
            totalBytes = 512L * 1024 + 1).size)
    }

    @Test fun unconfirmedColorNameAndVersionLimitsAreNotInvented() {
        assertNull(MachineProfile.SE400.maxColors)
        assertNull(MachineProfile.SE400.maxFileNameLength)
        val stitches = (0..70).flatMap { listOf(Stitch(it, 0, Command.STITCH), Stitch(it, 0, Command.COLOR_CHANGE)) }
        assertTrue(Preflight.check(EmbPattern(stitches, emptyList()), "long_but_ascii_name_123456789.pes").isEmpty())
    }

    @Test fun serializedInitialSplitCanExtendBoundsButOriginIsNotAlwaysIncluded() {
        val offset = EmbPattern(listOf(Stitch(5000, 0, Command.STITCH), Stitch(5100, 0, Command.STITCH)), emptyList())
        assertTrue(Preflight.check(offset, "a.pes").any { "100×100mm" in it })
        val shortInitial = EmbPattern(listOf(Stitch(1500, 0, Command.STITCH), Stitch(2500, 0, Command.STITCH)), emptyList())
        assertTrue(Preflight.check(shortInitial, "a.pes").isEmpty())
    }

    @Test fun folderZipAndControlMotionAreCheckedButPostEndStitchesAreIgnored() {
        assertTrue(Preflight.check(pattern(), "folder/a.pes").any { "최상위" in it })
        assertTrue(Preflight.check(pattern(), "a.zip").any { "ZIP" in it })
        val input = EmbPattern(listOf(Stitch(0, 0, Command.STITCH), Stitch(1001, 0, Command.JUMP),
            Stitch(1001, 0, Command.END), Stitch(100000, 0, Command.STITCH)), emptyList())
        assertEquals(1, Preflight.check(input, "a.pes").size)
        assertTrue(Preflight.check(EmbPattern(listOf(Stitch(0, 0, Command.STITCH), Stitch(0, 0, Command.END),
            Stitch(100000, 0, Command.STITCH)), emptyList()), "a.pes").isEmpty())
    }
}
