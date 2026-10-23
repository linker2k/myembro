// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class ViewerPlaybackTimelineTest {
    private val source = listOf(Command.JUMP, Command.STITCH, Command.TRIM, Command.COLOR_CHANGE,
        Command.JUMP, Command.STITCH, Command.STITCH, Command.END, Command.STITCH, Command.TRIM)
        .mapIndexed { i, c -> Stitch(i, i, c) }

    @Test fun timelineUsesNeedleTimePreservesControlMarkersAndStopsAtFirstEnd() {
        val pattern = EmbPattern(source, emptyList())
        val timeline = ViewerPlaybackTimeline(pattern)
        assertEquals(7, timeline.endIndex)
        assertEquals(3, timeline.needleCount)
        assertEquals(listOf(2, 3), timeline.marks.map { it.commandIndex })
        assertEquals(listOf(3f / 8, 4f / 8), timeline.marks.map { it.fraction })
        assertEquals(1, timeline.colorChanges); assertEquals(1, timeline.trims)
        assertEquals(1, timeline.advance(-1, 1))
        assertEquals(5, timeline.advance(1, 1))
        assertEquals(7, timeline.advance(5, 1))
        assertEquals(7, timeline.advance(-1, Int.MAX_VALUE))
        assertEquals(source, pattern.stitches)
    }

    @Test fun scrubToControlIndexKeepsCorrectSewnCountAndNextNeedle() {
        val timeline = ViewerPlaybackTimeline(EmbPattern(source, emptyList()))
        assertEquals(0, timeline.needlesThrough(-1))
        assertEquals(1, timeline.needlesThrough(3))
        assertEquals(5, timeline.advance(3, 1))
        assertEquals(3, timeline.needlesThrough(7))
    }

    @Test fun emptyEndOnlyAndMissingEndAreHandled() {
        val empty = ViewerPlaybackTimeline(EmbPattern(emptyList(), emptyList()))
        assertEquals(-1, empty.endIndex); assertEquals(0, empty.needleCount)
        assertEquals(-1, empty.advance(-1, 1))
        val end = ViewerPlaybackTimeline(EmbPattern(listOf(Stitch(0, 0, Command.END)), emptyList()))
        assertEquals(0, end.endIndex); assertTrue(end.marks.isEmpty())
        val missing = ViewerPlaybackTimeline(EmbPattern(source.take(7), emptyList()))
        assertEquals(6, missing.endIndex); assertEquals(6, missing.advance(-1, 3))
    }
    @Test fun autoPreviewRateDependsOnlyOnDesignLengthAndNeverMachineSpeed() {
        assertEquals(0.0, viewerPlaybackRate(0))
        assertEquals(1.0, viewerPlaybackRate(8))
        assertEquals(100.0, viewerPlaybackRate(2000))
        assertEquals(5000.0, viewerPlaybackRate(100000))
        assertFailsWith<IllegalArgumentException> { viewerPlaybackRate(-1) }
    }

    @Test fun binaryBadgePassesOnlyCompletedReportsWithoutBlockers() {
        val p = EmbPattern(listOf(Stitch(0, 0, Command.STITCH), Stitch(30, 0, Command.STITCH), Stitch(30, 0, Command.END)), emptyList())
        val report = checkViewerSewout(p, io.github.linker2k.myembro.engine.sewout.SewoutMachines.SE400,
            io.github.linker2k.myembro.engine.edit.HoopSpec.H100, false)
        assertFalse(viewerCheckBlocked(report)) // Non-blocking warnings remain in the details sheet.
        assertTrue(viewerCheckBlocked(null))
        assertTrue(viewerCheckBlocked(report.copy(analysisComplete = false)))
        assertTrue(viewerCheckBlocked(report.copy(severityCounts = mapOf(io.github.linker2k.myembro.engine.sewout.Severity.BLOCKER to 1))))
    }

}
