// SPDX-License-Identifier: GPL-3.0-or-later
// Uses the existing ports of Ink/Stitch lib/elements/stroke.py and
// lib/stitches/running_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c
// (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.elements.Stroke
import io.github.linker2k.myembro.engine.inkstitch.elements.StrokeParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.EmbThread
import org.jgrapht.alg.tour.NearestNeighborHeuristicTSP
import org.jgrapht.graph.DefaultWeightedEdge
import org.jgrapht.graph.SimpleWeightedGraph

/** Silhouette is the existing default. OUTLINE uses the same traced rings, including holes. */
enum class PhotoStyle { SILHOUETTE, OUTLINE }

internal object PhotoOutline {
    data class Route(val paths: List<List<Point>>, val before: Double, val after: Double)

    fun paths(outline: RedworkOutline): List<List<Point>> = outline.paths.flatMap { path ->
        SvgPath.flatten(path,tolerance=Units.mmToPixels(0.1)).map { line ->
            val points=line.points.map { Point(it.x,it.y) }
            if(points.size>1 && points.first()!=points.last()) points+points.first() else points
        }
    }.filter { it.size>1 }

    /** Existing JGraphT 1.5.2 algorithm, fixed first vertex; no custom tour solver.
     * https://jgrapht.org/javadoc-1.5.2/org.jgrapht.core/org/jgrapht/alg/tour/NearestNeighborHeuristicTSP.html
     * Cost is the open sequence of closed-ring anchors, not the solver's return-to-start cycle.
     */
    fun route(paths: List<List<Point>>): Route {
        fun cost(items: List<List<Point>>) = items.zipWithNext().sumOf { (a,b) -> a.last().distance(b.first()) }
        val before=cost(paths)
        if(paths.size<3) return Route(paths,before,before)
        val graph=SimpleWeightedGraph<Int,DefaultWeightedEdge>(DefaultWeightedEdge::class.java)
        paths.indices.forEach { graph.addVertex(it) }
        for(i in paths.indices) for(j in i+1 until paths.size) {
            if(Thread.currentThread().isInterrupted) throw InterruptedException()
            graph.setEdgeWeight(graph.addEdge(i,j),paths[i].first().distance(paths[j].first()))
        }
        val order=NearestNeighborHeuristicTSP<Int,DefaultWeightedEdge>(listOf(0)).getTour(graph).vertexList.dropLast(1)
        val candidate=order.map { paths[it] }
        val after=cost(candidate)
        return if(after<before) Route(candidate,before,after) else Route(paths,before,before)
    }

    fun groups(route: Route,thread: EmbThread): List<StitchGroup> = route.paths.flatMap { path ->
        if(Thread.currentThread().isInterrupted) throw InterruptedException()
        // One bean repeat = forward/back/forward, not three bean repeats (=7 passes).
        // Explicit source trim_after separates disconnected contours and preserves tie-off boundaries.
        Stroke(listOf(path),thread,StrokeParameters(bean_stitch_repeats=listOf(1),trim_after=true))
            .toStitchGroups(null)
    }
}
