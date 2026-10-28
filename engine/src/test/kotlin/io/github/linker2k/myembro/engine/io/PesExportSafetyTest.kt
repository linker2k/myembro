// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlin.test.*

class PesExportSafetyTest {
    // Oracle writer constructs hostile synthetic input; production settings are unchanged.
    private fun dense(count: Int) = PesWriter.write(EmbPattern(
        List(count) { i -> Stitch(i % 2, (i / 2) % 2, Command.STITCH) }, listOf(EmbThread(0))),
        PesWriteSettings(inkstitchCommands = true))

    @Test fun `imported 30 is preserved and 31 is rejected at both PES versions`() {
        val accepted = dense(30)
        for (version in PesVersion.entries) {
            val result = PesExport.prepare(accepted, "imported.pes", PesWriteSettings(version))
            assertFalse(result.converted)
            assertContentEquals(accepted, result.bytes)
            assertEquals(DesignOrigin.IMPORTED, result.origin)
            val blocked = dense(31)
            val before = blocked.copyOf()
            val error = assertFailsWith<IllegalArgumentException> { PesExport.prepare(blocked, "blocked.pes", PesWriteSettings(version)) }
            assertContains(error.message!!, "DENSITY_HOTSPOT")
            assertContentEquals(before, blocked)
        }
    }

    @Test fun `generated density above 30 blocks and selected hoop is enforced`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PesExport.requireSafe(dense(31), HoopSpec.H100, DesignOrigin.APP_GENERATED)
        }
        assertContains(error.message!!, "DENSITY_HOTSPOT")
        val bytes = checkNotNull(javaClass.getResourceAsStream("/fixtures/stops.v1.pes")).use { it.readBytes() }
        assertFailsWith<IllegalArgumentException> {
            PesExport.prepare(bytes, "imported.pes", PesWriteSettings(hoop = HoopSpec("tiny", 1, 1)))
        }
    }
}
