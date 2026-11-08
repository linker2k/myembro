// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.machines

import io.github.linker2k.myembro.engine.edit.HoopSpec
import java.io.File
import kotlin.test.*

/** docs/machines.md catalog tables (from docs/research/hoop-verification.md) must equal the code, W×H mm. */
class MachineHoopTableTest {
    private val row = Regex("""^\| .*? \(`([^`]+)`\) \| (\d+)×(\d+) \| ([^|]*) \|""")
    private fun sizes(text: String) = Regex("""(\d+)×(\d+)""").findAll(text).map { it.groupValues[1].toInt() * 10 to it.groupValues[2].toInt() * 10 }.toList()

    @Test fun everyMachineHoopListMatchesTheDocumentTable() {
        val rows = File("../docs/machines.md").readLines().mapNotNull { row.find(it) }.associateBy { it.groupValues[1] }
        val profiles = MachineProfiles.all.filter { it.id != "free" }
        assertEquals(58, profiles.size)
        assertEquals(profiles.map { it.id }.toSet(), rows.keys)
        for (p in profiles) {
            val (_, _, w, h, hoops) = rows.getValue(p.id).groupValues
            assertEquals(w.toInt() * 10 to h.toInt() * 10, p.maximumArea.width to p.maximumArea.height, p.id)
            val (continuous, coverage) = hoops.split(";").let { it[0] to it.getOrElse(1) { "" } }
            assertEquals(sizes(continuous), p.hoops.filterNot { it.requiresRepositioning }.map { it.sewingArea.width to it.sewingArea.height }, p.id)
            assertEquals(sizes(coverage), p.hoops.filter { it.requiresRepositioning }.map { it.coverage.width to it.coverage.height }, p.id)
            for (hoop in p.hoops.filterNot { it.requiresRepositioning }) assertTrue(hoop.sewingArea in HoopSpec.BROTHER_FRAMES, p.id)
            assertTrue(p.hoops.filter { it.requiresRepositioning }.all { it.coverage == HoopSpec.SA434_COVERAGE && it.sewingArea == HoopSpec.H100 }, p.id)
        }
        // Spot checks against hoop-verification.md: PR small frame 60×40; V3LE left unchanged.
        assertTrue(MachineProfiles.find("kr-pr1x")!!.supports(HoopSpec.brotherFrame(60, 40)))
        assertFalse(MachineProfiles.find("kr-pr1x")!!.supports(HoopSpec.brotherFrame(60, 20)))
        assertEquals(listOf(2000 to 3000, 2000 to 2000, 1300 to 1800, 1000 to 1000), MachineProfiles.find("kr-v3le")!!.hoops.map { it.sewingArea.width to it.sewingArea.height })
    }
}
