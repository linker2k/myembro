// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class ProjectStatisticsTest {
    @Test fun outputCountsIncludeBoundariesThreadChangesAndStops() {
        val source = EmbPattern(listOf(
            Stitch(0, 0, Command.STITCH), Stitch(0, 0, Command.STOP),
            Stitch(20, 20, Command.JUMP), Stitch(40, 40, Command.STITCH), Stitch(40, 40, Command.END),
        ), listOf(red))
        val p = Project(objects = listOf(rectangle(), StitchObject("stopped", "stopped", source)))
        val stats = p.statistics(stitchesPerMinute = 60, secondsPerTrim = 5, secondsPerColorChange = 7)
        assertEquals(ProjectStatistics(4, 3, 1, 1, 1, 23, false), stats)
        val flat = p.flatten()
        assertEquals(flat.estimatedSeconds(), p.statistics().estimatedSeconds)
        assertEquals(flat.jumpCount, p.statistics().jumpCount)
        assertEquals(flat.trimCount, p.statistics().trimCount)
        assertEquals(flat.colorChangeCount, p.statistics().colorChangeCount)
    }

    @Test fun hiddenEmptyAndTerminatedGeometryDoesNotContribute() {
        val terminated = StitchObject("end", "end", EmbPattern(listOf(
            Stitch(0, 0, Command.END), Stitch(9000, 9000, Command.STITCH),
        ), listOf(blue)))
        val p = Project(objects = listOf(rectangle().copy(visible = false), terminated))
        assertEquals(ProjectStatistics(0, 0, 0, 0, 0, 0, false), p.statistics())
        assertEquals(p.statistics(), Project().statistics())
    }

    @Test fun transformedOutputDeterminesHoopOverflow() {
        val p = Project(objects = listOf(rectangle(x = 449.0)))
        assertFalse(p.statistics().exceedsHoop)
        assertTrue(p.scaleBy("a", 1.2).statistics().exceedsHoop)
        assertFalse(p.scaleBy("a", 1.2).update("a") { it.withVisible(false) }.statistics().exceedsHoop)
    }

    @Test fun invalidEstimateParametersFailClearly() {
        assertFailsWith<IllegalArgumentException> { Project().statistics(stitchesPerMinute = 0) }
        assertFailsWith<IllegalArgumentException> { Project().statistics(stitchesPerMinute = -1) }
        assertFailsWith<IllegalArgumentException> { Project().statistics(secondsPerTrim = -1) }
        assertFailsWith<IllegalArgumentException> { Project().statistics(secondsPerColorChange = -1) }
    }
}
