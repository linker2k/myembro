// Ported from Ink/Stitch lib/stitches/meander_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** No upstream meander tests exist in v3.3.0; fixtures lock down source graph operations. */
class MeanderFillTest {
    private val factory = GeometryFactory()
    private fun square(size: Double = 100.0) = factory.createPolygon(arrayOf(Coordinate(0.0, 0.0), Coordinate(size, 0.0),
        Coordinate(size, size), Coordinate(0.0, size), Coordinate(0.0, 0.0)))
    private fun grid() = MeanderFill.tileFromSvg("grid", """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><path d="M0 0L10 0"/><path d="M0 0L0 10"/></svg>""".toByteArray())
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun blake2sMatchesStandardEmptyAndAbcVectors() {
        assertEquals("69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9", hex(MeanderPrng.blake2s(byteArrayOf())))
        assertEquals("508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982", hex(MeanderPrng.blake2s("abc".toByteArray())))
        // Independently computed with Python stdlib hashlib, without importing/executing Ink/Stitch.
        assertEquals("c13eb20b85b1d6a72d52af717429fc54eacc63fecde1295b26f0fa251bdcf40e", hex(MeanderPrng.blake2s("x".repeat(64).toByteArray())))
        assertEquals("77f538c970521d7f5b94c5e8c88b8ce35661dfc6d61b5bc837da31e543cc6b81", hex(MeanderPrng.blake2s("x".repeat(65).toByteArray())))
        assertEquals(0x4d51e36dL.toDouble() / 4294967295.0, MeanderPrng("seed", 2).next())
        val first = MeanderPrng("seed", 2)
        val second = MeanderPrng("seed", 2)
        assertEquals(List(20) { first.next() }, List(20) { second.next() })
        assertFalse(MeanderPrng("seed", 2).next() == MeanderPrng("seed", 3).next())
    }

    @Test fun allPinnedOriginalSvgTilesLoadWithNondegenerateParallelograms() {
        val patterns = MeanderFill.availablePatterns()
        assertEquals(75, patterns.size)
        assertEquals("N3-11a", patterns.first())
        for (id in patterns) {
            val tile = MeanderFill.loadTile(id)
            assertEquals(id, tile.id)
            assertTrue(tile.segments.isNotEmpty(), id)
            assertTrue(tile.shift0.length() > 0 && tile.shift1.length() > 0, id)
        }
        assertEquals("weird-one", MeanderFill.loadTile("not-a-pattern").id)
    }

    @Test fun tileParserUsesEndpointsTransformsCenterAndParallelogram() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="6mm" height="6mm"><g transform="translate(10,20)"><path d="M0 0L10 0"/><path d="M0 0L0 10"/><polygon class="para" points="0,0 20,0 25,30 5,30"/></g></svg>"""
        val tile = MeanderFill.tileFromSvg("parallelogram", svg.toByteArray())
        assertEquals(listOf(FillPoint(-5.0, -5.0) to FillPoint(5.0, -5.0),
            FillPoint(-5.0, -5.0) to FillPoint(-5.0, 5.0)), tile.segments)
        assertEquals(FillPoint(20.0, 0.0), tile.shift0)
        assertEquals(FillPoint(5.0, 30.0), tile.shift1)
    }

    @Test fun originalDeadEndRemovalLeavesOnlyGridCycleCore() {
        val graph = MeanderFill.tileGraph(square(), grid(), MeanderFillParameters())
        assertTrue(graph.size > 50)
        assertTrue(graph.values.all { it.size >= 2 })
        for ((node, neighbors) in graph) for (neighbor in neighbors) {
            assertTrue(node in graph.getValue(neighbor))
            assertTrue(square().contains(FillGeometry.line(listOf(node, neighbor))))
        }
    }

    @Test fun seededEdgeAndEdgePairDetoursMakeSimpleReproduciblePath() {
        fun path(seed: String): List<FillPoint> {
            val graph = MeanderFill.tileGraph(square(), grid(), MeanderFillParameters())
            val (start, end) = MeanderFill.startingAndEndingNodes(graph, square(), null, null)
            val rng = MeanderPrng(seed, 0)
            return MeanderFill.generatePath(graph, start, end, rng::next)
        }
        val result = path("fixed")
        assertEquals(result, path("fixed"))
        assertFalse(result == path("different"))
        assertTrue(result.size > 50)
        assertEquals(result.size, result.distinct().size)
        assertEquals(FillPoint(5.0, 5.0), result.first())
        assertEquals(FillPoint(95.0, 95.0), result.last())
        assertTrue(result.zipWithNext().all { (a, b) -> a.distance(b) == 10.0 })
    }

    @Test fun nearestComponentMstAddsOnlyTheNeededBridges() {
        val a = FillPoint(0.0, 0.0); val b = FillPoint(1.0, 0.0)
        val c = FillPoint(10.0, 0.0); val d = FillPoint(11.0, 0.0)
        val e = FillPoint(20.0, 0.0); val f = FillPoint(21.0, 0.0)
        val graph = linkedMapOf(a to linkedSetOf(b), b to linkedSetOf(a), c to linkedSetOf(d),
            d to linkedSetOf(c), e to linkedSetOf(f), f to linkedSetOf(e))
        MeanderFill.ensureConnected(graph)
        assertEquals(10, graph.values.sumOf { it.size })
        assertTrue(c in graph.getValue(b))
        assertTrue(e in graph.getValue(d))
    }

    @Test fun postProcessingKeepsOriginalAlternatingZigzagAndRepeatBehavior() {
        val points = listOf(FillPoint(0.0, 0.0), FillPoint(10.0, 0.0), FillPoint(20.0, 0.0), FillPoint(30.0, 0.0))
        assertEquals(listOf(FillPoint(0.0, 2.0), FillPoint(10.0, -2.0), FillPoint(20.0, 2.0), FillPoint(30.0, 0.0)),
            MeanderFill.zigzagStitch(points, 4.0))
        val normal = MeanderFill.postProcess(points, MeanderFillParameters())
        val repeated = MeanderFill.postProcess(points, MeanderFillParameters(repeats = 3))
        assertEquals(normal + normal.reversed() + normal + normal.reversed(), repeated)
        val bean = MeanderFill.postProcess(points, MeanderFillParameters(bean_stitch_repeats = listOf(1)))
        assertEquals(1 + (normal.size - 1) * 3, bean.size)
    }

    @Test fun publicFillUsesPinnedTileAndBoundsItsRunningStitches() {
        val shape = square(120.0)
        val options = MeanderFillParameters(random_seed = "public-fixture")
        val points = MeanderFill.fill(shape, options)
        assertTrue(points.size > 30)
        assertEquals(points, MeanderFill.fill(shape, options))
        assertTrue(points.all { shape.buffer(1e-7).covers(FillGeometry.point(it)) })
        assertTrue(points.zipWithNext().all { (a, b) -> a.distance(b) <= 2.5 * 96.0 / 25.4 + 1e-5 })
    }

    @Test fun rotatedNonuniformTileGraphRejectsSegmentsCrossingAHole() {
        val hole = factory.createLinearRing(arrayOf(Coordinate(55.0, 55.0), Coordinate(105.0, 55.0),
            Coordinate(105.0, 105.0), Coordinate(55.0, 105.0), Coordinate(55.0, 55.0)))
        val shape = factory.createPolygon(square(160.0).exteriorRing, arrayOf(hole))
        val options = MeanderFillParameters(meander_angle = 30.0, meander_scale_percent = listOf(70.0, 130.0), random_seed = "hole")
        val graph = MeanderFill.tileGraph(shape, grid(), options)
        assertTrue(graph.size > 100)
        for ((point, neighbors) in graph) for (next in neighbors)
            assertTrue(shape.contains(FillGeometry.line(listOf(point, next))))
        val stitches = MeanderFill.fill(shape, options, tile = grid())
        assertTrue(stitches.none { it.x > 55.01 && it.x < 104.99 && it.y > 55.01 && it.y < 104.99 })
    }

    @Test fun maliciousSvgCannotLoadEntitiesAndTooSmallShapesFailExplicitly() {
        assertFails { MeanderFill.fill(square(0.1), tile = grid()) }
        assertFails { MeanderFill.tileFromSvg("unsafe", """<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///does-not-exist">]><svg width="10" height="10"><path d="&x;"/></svg>""".toByteArray()) }
    }

    @Test fun smoothingUsesOriginalChaikinThenRunningStitchOrderAndMmUnits() {
        val raw = listOf(FillPoint(0.0, 0.0), FillPoint(20.0, 0.0), FillPoint(20.0, 20.0), FillPoint(40.0, 20.0))
        val options = MeanderFillParameters(smoothness_mm = 0.2, running_stitch_length_mm = 1.5, running_stitch_tolerance_mm = 0.02)
        val expected = FillRunning.run(FillGeometry.smoothPath(raw, 0.2 * 96.0 / 25.4), 1.5 * 96.0 / 25.4, 0.02 * 96.0 / 25.4)
        val result = MeanderFill.postProcess(raw, options)
        assertEquals(expected.size, result.size)
        expected.zip(result).forEach { (a,b) -> assertTrue(a.distance(b)<1e-9) }
        assertFalse(result == MeanderFill.postProcess(raw, options.copy(smoothness_mm = 0.0)))
        assertEquals(raw.first(), result.first())
        assertEquals(raw.last(), result.last())
    }

    @Test fun clippingAnOutsideExcursionUsesOriginalShapeBorderInsteadOfJumping() {
        val raw = listOf(FillPoint(10.0, 20.0), FillPoint(70.0, 20.0), FillPoint(70.0, 40.0), FillPoint(10.0, 40.0))
        val original = square(50.0)
        val options = MeanderFillParameters(clip = true, running_stitch_length_mm = 1.0, running_stitch_tolerance_mm = 0.02)
        val result = MeanderFill.postProcess(raw, options, original)
        assertEquals(raw.first(), result.first())
        assertEquals(raw.last(), result.last())
        assertTrue(result.any { it.x == 50.0 && it.y in 20.0..40.0 })
        assertTrue(result.zipWithNext().all { (a, b) -> original.buffer(1e-7).covers(FillGeometry.line(listOf(a, b))) })
        assertTrue(result.zipWithNext().all { (a, b) -> a.distance(b) <= 96.0 / 25.4 + 1e-5 })
    }

    @Test fun clippedSmoothZigzagStaysWithinOriginalHoleBoundary() {
        val hole = factory.createLinearRing(arrayOf(Coordinate(40.0, 40.0), Coordinate(60.0, 40.0),
            Coordinate(60.0, 60.0), Coordinate(40.0, 60.0), Coordinate(40.0, 40.0)))
        val original = factory.createPolygon(square().exteriorRing, arrayOf(hole))
        val raw = listOf(FillPoint(10.0, 50.0), FillPoint(90.0, 50.0), FillPoint(90.0, 30.0), FillPoint(10.0, 30.0))
        val options = MeanderFillParameters(clip = true, smoothness_mm = 0.1, zigzag_spacing_mm = 1.0,
            zigzag_width_mm = 2.0, running_stitch_length_mm = 1.0, running_stitch_tolerance_mm = 0.01)
        val result = MeanderFill.postProcess(raw, options, original)
        assertTrue(result.size > 50)
        val allowed = original.buffer(0.01 * 96.0 / 25.4 + 1e-6)
        assertTrue(result.zipWithNext().all { (a, b) -> allowed.covers(FillGeometry.line(listOf(a, b))) })
    }

    @Test fun publicClipCanUseAnOriginalShapeSmallerThanTheExpandedFillShape() {
        val original = square(70.0)
        val result = MeanderFill.fill(square(), MeanderFillParameters(clip = true, smoothness_mm = 0.15,
            random_seed = "expanded", running_stitch_tolerance_mm = 0.01), originalShape = original, tile = grid())
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { original.buffer(0.01 * 96.0 / 25.4 + 1e-6).covers(FillGeometry.point(it)) })
    }
}
