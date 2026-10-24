// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitches.utils

import kotlin.test.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.utils.SatinRouteGraph.Edge

/** Source-derived analytic graph cases; NOT an official Ink/Stitch execution oracle. */
class SatinRouteGraphTest {
    @Test fun straightBackboneSewsOnce() {
        val route=SatinRouteGraph.route(listOf(Edge(0,1,0),Edge(1,2,1)),0,2)
        assertEquals(listOf(0 to 1,1 to 2),route.map { it.start to it.end })
        assertTrue(route.none { it.underpath })
    }
    @Test fun branchIsUnderpathFirstSatinOnReturn() {
        val route=SatinRouteGraph.route(listOf(Edge(0,1,0),Edge(1,2,1),Edge(1,3,2)),0,2)
        assertEquals(listOf(0 to 1,1 to 3,3 to 1,1 to 2),route.map { it.start to it.end })
        assertEquals(listOf(false,true,false,false),route.map { it.underpath })
        assertEquals(3,route.count { !it.underpath })
    }
    @Test fun cycleAndNonTreeEdgesCoveredWithoutDoubleSatin() {
        val edges=listOf(Edge(0,1,0),Edge(1,2,1),Edge(2,0,2),Edge(2,3,3))
        val route=SatinRouteGraph.route(edges,0,3)
        assertEquals(listOf(0,1,2,3),route.filter { !it.underpath }.mapNotNull { it.satinSegment }.sorted())
        assertEquals(0,route.first().start); assertEquals(3,route.last().end)
        assertTrue(route.zipWithNext().all { (a,b) -> a.end==b.start })
    }
    @Test fun jumpRemainsJumpEvenWhenBacktracked() {
        val route=SatinRouteGraph.route(listOf(Edge(0,1,0),Edge(1,2,1),Edge(1,3,null)),0,2)
        assertEquals(2,route.count { it.satinSegment==null })
        assertTrue(route.filter { it.satinSegment==null }.none { it.underpath })
    }
    @Test fun closedStartEndTraversesAllBranches() {
        val route=SatinRouteGraph.route(listOf(Edge(0,1,0),Edge(1,2,1)),0,0)
        assertEquals(listOf(0 to 1,1 to 2,2 to 1,1 to 0),route.map { it.start to it.end })
        assertEquals(2,route.count { it.underpath })
    }
    @Test fun directedBackboneConstrainsElementOrder() {
        val edges=listOf(Edge(0,1,0),Edge(1,0,0),Edge(1,2),Edge(2,3,1),Edge(3,2,1))
        val route=SatinRouteGraph.route(edges,0,3,directed=true)
        assertEquals(listOf(0 to 1,1 to 2,2 to 3),route.map { it.start to it.end })
        assertTrue(route.none { it.underpath })
    }
    @Test fun disconnectedRejectedWithoutInventedConnector() {
        assertFailsWith<IllegalArgumentException> { SatinRouteGraph.route(listOf(Edge(0,1,0),Edge(2,3,1)),0,3) }
    }
}
