// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.io.PecWriter
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class OrderOptimizationTest {
    private fun point(id: String, x: Double, thread: EmbThread = blue): StitchObject =
        rectangle(id, x, width = 0, height = 0).let { it.copy(source = EmbPattern(it.source.stitches, listOf(thread))) }

    @Test fun proposalReducesTravelAndApplyingItIsExplicit() {
        val p = Project(objects = listOf(point("far", 100.0), point("near", 10.0), point("middle", 20.0)))
        val sewnBefore = p.flatten().stitches
        val suggestion = p.suggestOrder()
        assertEquals(listOf("near", "middle", "far"), suggestion.objectIds)
        assertEquals(200.0, suggestion.travelDistanceBefore)
        assertEquals(100.0, suggestion.travelDistanceAfter)
        assertTrue(suggestion.improved)
        assertSame(p, p.optimizeOrder(preserveOriginalOrder = true))
        assertEquals(listOf("far", "near", "middle"), p.objects.map { it.id })
        assertEquals(sewnBefore, p.flatten().stitches)
        val optimized = p.optimizeOrder()
        assertEquals(suggestion.objectIds, optimized.objects.map { it.id })
        assertEquals(p.objects.toSet(), optimized.objects.toSet())
        assertEquals(p.flatten().stitchCount, optimized.flatten().stitchCount)
        assertEquals(p.flatten().trimCount, optimized.flatten().trimCount)
        assertEquals(0, optimized.flatten().colorChangeCount)
        val flat = optimized.flatten()
        assertEquals(PecWriter.normalize(io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.forSewout(flat)).stitches, PesReader.read(PesWriter.write(flat)).stitches)
    }

    @Test fun outgoingTravelCannotMakeTheSuggestionWorse() {
        // Nearest first (near, far) would cost 230; the original route including red costs 210.
        val p = Project(objects = listOf(point("far", -100.0), point("near", 10.0), point("red", 10.0, red)))
        val suggestion = p.suggestOrder()
        assertFalse(suggestion.improved)
        assertEquals(210.0, suggestion.travelDistanceBefore)
        assertEquals(suggestion.travelDistanceBefore, suggestion.travelDistanceAfter)
        assertSame(p, p.optimizeOrder())
    }

    @Test fun differentThreadIdentityMulticolorAndNonSewingObjectsAreBarriers() {
        val multicolor = StitchObject("multi", "multi", EmbPattern(listOf(
            Stitch(0, 0, Command.STITCH), Stitch(0, 0, Command.COLOR_CHANGE),
            Stitch(10, 10, Command.STITCH), Stitch(10, 10, Command.END),
        ), listOf(blue, red)))
        val hidden = point("hidden", 500.0).copy(visible = false)
        val empty = StitchObject("empty", "empty", EmbPattern(emptyList(), emptyList()))
        val p = Project(objects = listOf(
            point("first", 100.0), point("catalog", 10.0, blue.copy(catalogNumber = "other")),
            point("third", 20.0), multicolor, point("fourth", -100.0), hidden,
            point("fifth", -10.0), empty, point("sixth", 0.0),
        ))
        assertEquals(p.objects.map { it.id }, p.suggestOrder().objectIds)
        assertSame(p, p.optimizeOrder())
        assertEquals(p.flatten().threads, p.optimizeOrder().flatten().threads)
    }

    @Test fun routeUsesPlacedEndpointsAndPreservesInternalSewingOrder() {
        val far = rectangle("far", 400.0).copy(transform = Transform(400.0, 100.0, 90.0, -1.0, 1.2))
        val near = rectangle("near", 20.0)
        val middle = rectangle("middle", 150.0)
        val p = Project(objects = listOf(far, near, middle))
        val optimized = p.optimizeOrder()
        assertTrue(p.suggestOrder().improved)
        for (obj in p.objects) assertSame(obj, optimized.find(obj.id))
        assertEquals(p.objects.sumOf { it.localPattern().stitchCount }, optimized.flatten().stitchCount)
        assertEquals(p.suggestOrder(), p.suggestOrder())
    }

    @Test fun emptySingletonAndTiesKeepTheirOrder() {
        val empty = Project()
        assertSame(empty, empty.optimizeOrder())
        assertEquals(OrderSuggestion(emptyList(), 0.0, 0.0), empty.suggestOrder())
        val single = Project(objects = listOf(point("single", 30.0)))
        assertSame(single, single.optimizeOrder())
        val tied = Project(objects = listOf(point("a", 10.0), point("b", 10.0)))
        assertSame(tied, tied.optimizeOrder())
        assertEquals(listOf("a", "b"), tied.suggestOrder().objectIds)
    }
}
