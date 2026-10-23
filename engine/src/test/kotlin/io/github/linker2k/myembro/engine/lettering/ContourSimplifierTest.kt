// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContourSimplifierTest {
    @Test
    fun douglasPeuckerRemovesFineNoiseAndKeepsEndpoints() {
        val path = (0..200).map { it / 20.0 to if (it % 2 == 0) 0.0 else 0.01 }
        val reduced = ContourSimplifier.simplify(listOf(path), 0.02, 0.0).single()
        assertEquals(listOf(path.first(), path.last()), reduced)
        assertEquals(path, ContourSimplifier.simplify(listOf(path), 0.0, 0.0).single())
    }

    @Test
    fun minimumSegmentPassMergesShortEdgesWithoutDeletingSharpCorners() {
        val path = listOf(0.0 to 0.0, 0.1 to 0.0, 0.2 to 0.0, 2.0 to 0.0, 2.0 to 0.1, 2.0 to 2.0)
        val reduced = ContourSimplifier.simplify(listOf(path), 0.0, 0.3).single()
        assertEquals(listOf(0.0 to 0.0, 2.0 to 0.0, 2.0 to 2.0), reduced)
        val sharp = listOf(0.0 to 0.0, 0.1 to 0.0, 0.1 to 0.1)
        assertEquals(sharp, ContourSimplifier.simplify(listOf(sharp), 0.01, 0.3).single())
    }

    @Test
    fun keepsClosedContoursAndCountersSeparateEvenAtLargeTolerance() {
        val outer = listOf(0.0 to 0.0, 4.0 to 0.0, 4.0 to 4.0, 0.0 to 4.0, 0.0 to 0.0)
        val inner = listOf(1.0 to 1.0, 2.0 to 1.0, 2.0 to 2.0, 1.0 to 2.0, 1.0 to 1.0)
        val result = ContourSimplifier.simplify(listOf(outer, inner), 100.0, 100.0)
        assertEquals(2, result.size)
        result.forEach { path ->
            assertEquals(path.first(), path.last())
            assertTrue(path.distinct().size >= 3)
        }
    }

    @Test
    fun fromContoursCanDisableSimplificationOrMergeShortSegments() {
        val dense = (0..200).map { it / 20.0 to 0.0 }
        val raw = LetteringEngine.fromContours(listOf(dense), 10.0, simplify = false, minSegmentMm = 0.3)
        val merged = LetteringEngine.fromContours(listOf(dense), 10.0, minSegmentMm = 0.3)
        assertTrue(merged.stitchCount <= raw.stitchCount)
        assertEquals(raw.bounds, merged.bounds)
        assertEquals(100, assertNotNull(merged.bounds).width)
        for ((a, b) in merged.stitches.zipWithNext()) {
            if (b.command == io.github.linker2k.myembro.engine.model.Command.STITCH) {
                assertTrue(hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) <= 25.0)
            }
        }
    }

    @Test
    fun emptyDuplicateAndInvalidInputIsHandled() {
        assertEquals(listOf(emptyList()), ContourSimplifier.simplify(listOf(emptyList()), 0.01, 0.2))
        assertEquals(listOf(listOf(0.0 to 0.0)), ContourSimplifier.simplify(listOf(listOf(0.0 to 0.0, 0.0 to 0.0)), 0.01, 0.2))
        assertFailsWith<IllegalArgumentException> { ContourSimplifier.simplify(emptyList(), -1.0, 0.1) }
        assertFailsWith<IllegalArgumentException> { ContourSimplifier.simplify(emptyList(), 0.1, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { ContourSimplifier.simplify(listOf(listOf(0.0 to Double.POSITIVE_INFINITY)), 0.1, 0.1) }
        assertFailsWith<IllegalArgumentException> { LetteringEngine.fromContours(emptyList(), 10.0, minSegmentMm = -1.0) }
    }
}
