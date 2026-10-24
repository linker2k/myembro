// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/utils/autoroute.py:find_path/_get_shortest_path
// and lib/stitches/auto_satin.py:path_to_operations@b0edd96311ece82ee48816dc93466274b12c2a9c
// (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches.utils

/** Graph traversal kernel only. No rail slicing, inferred geometry or app integration.
 * Node order follows insertion order (as NetworkX graph dictionaries do). Edges are unweighted.
 */
object SatinRouteGraph {
    data class Edge(val start: Int, val end: Int, val satinSegment: Int? = null)
    data class Step(val start: Int, val end: Int, val satinSegment: Int?, val underpath: Boolean)
    private enum class Label { FORWARD, REVERSE, NONTREE }
    private data class Visit(val start: Int, val end: Int, val label: Label)
    private class Graph(edges: List<Edge>, val directed: Boolean) {
        val adjacency=linkedMapOf<Int,LinkedHashSet<Int>>()
        val segments=mutableMapOf<Pair<Int,Int>,Int?>()
        init { edges.forEach { add(it.start,it.end,it.satinSegment) } }
        fun add(a:Int,b:Int,segment:Int?) {
            adjacency.getOrPut(a) { linkedSetOf() }.add(b)
            adjacency.getOrPut(b) { linkedSetOf() }
            segments[a to b]=segment
            if(!directed) { adjacency.getValue(b).add(a); segments[b to a]=segment }
        }
        fun remove(a:Int,b:Int) { adjacency[a]?.remove(b); adjacency[b]?.remove(a) }
        fun undirected():Graph = Graph(adjacency.flatMap { (a,neighbors) -> neighbors.map { b -> Edge(a,b,segments[a to b]) } },false)
    }

    /** autoroute.find_path: shortest backbone, then depth-first branches with backtracking.
     * [directed] corresponds to preserve_order graph construction; traversal copies it undirected.
     * Unknown/disconnected supplied nodes fail explicitly (the DOM caller must first add jumps).
     */
    fun route(edges:List<Edge>,start:Int,end:Int,directed:Boolean=false):List<Step> {
        require(edges.isNotEmpty())
        val original=Graph(edges,directed)
        require(start in original.adjacency && end in original.adjacency)
        val path=shortest(original,start,end)
        val graph=original.undirected()
        path.zipWithNext().forEach { (a,b) -> graph.remove(a,b) }
        val traversed=mutableListOf<Pair<Int,Int>>()
        var previous:Int?=null
        for(node in path) {
            previous?.let { traversed+=it to node }; previous=node
            // The source materializes dfs_labeled_edges BEFORE deleting any edges.
            for(v in depthFirst(graph,node)) {
                if(v.start==v.end) continue
                when(v.label) {
                    Label.FORWARD -> { traversed+=v.start to v.end; graph.remove(v.start,v.end) }
                    Label.REVERSE -> traversed+=v.end to v.start
                    Label.NONTREE -> if(v.end in graph.adjacency.getValue(v.start)) {
                        traversed+=v.start to v.end; traversed+=v.end to v.start; graph.remove(v.start,v.end)
                    }
                }
            }
        }
        // auto_satin.path_to_operations: only the LAST traversal of a satin is satin.
        // Earlier traversals become running underpaths that the final satin covers.
        val undirected=original.undirected(); val seen=mutableSetOf<Int>()
        return traversed.asReversed().map { (a,b) ->
            val segment=undirected.segments[a to b]
            Step(a,b,segment,segment!=null && !seen.add(segment))
        }.asReversed()
    }

    /** NetworkX unweighted shortest_path semantics via bidirectional breadth-first search.
     * Bidirectional expansion preserves shortest-path tie order on the supplied adjacency.
     */
    private fun shortest(g:Graph,start:Int,end:Int):List<Int> {
        if(start==end) return listOf(start)
        val predecessor=linkedMapOf<Int,Int?>(start to null)
        val successor=linkedMapOf<Int,Int?>(end to null)
        val incoming=linkedMapOf<Int,MutableList<Int>>()
        g.adjacency.forEach { (a,neighbors) -> neighbors.forEach { b -> incoming.getOrPut(b) { mutableListOf() }.add(a) } }
        var forward=listOf(start); var reverse=listOf(end); var meeting:Int?=null
        search@ while(forward.isNotEmpty() && reverse.isNotEmpty()) {
            val next=mutableListOf<Int>()
            if(forward.size<=reverse.size) {
                for(v in forward) for(w in g.adjacency.getValue(v)) {
                    if(w !in predecessor) { predecessor[w]=v; next+=w }
                    if(w in successor) { meeting=w; break@search }
                }
                forward=next
            } else {
                for(v in reverse) for(w in if(g.directed) incoming[v].orEmpty() else g.adjacency.getValue(v)) {
                    if(w !in successor) { successor[w]=v; next+=w }
                    if(w in predecessor) { meeting=w; break@search }
                }
                reverse=next
            }
        }
        var node=requireNotNull(meeting) { "Disconnected route graph: add upstream jumps before routing" }
        val result=mutableListOf(node)
        while(predecessor[node]!=null) { node=predecessor.getValue(node)!!; result+=node }
        result.reverse(); node=meeting
        while(successor[node]!=null) { node=successor.getValue(node)!!; result+=node }
        return result
    }
    private fun depthFirst(g:Graph,start:Int):List<Visit> {
        data class Frame(val node:Int,val children:Iterator<Int>)
        val result=mutableListOf(Visit(start,start,Label.FORWARD)); val visited=mutableSetOf(start)
        val stack=mutableListOf(Frame(start,g.adjacency.getValue(start).iterator()))
        while(stack.isNotEmpty()) {
            val frame=stack.last()
            if(frame.children.hasNext()) {
                val child=frame.children.next()
                if(visited.add(child)) {
                    result+=Visit(frame.node,child,Label.FORWARD)
                    stack+=Frame(child,g.adjacency.getValue(child).iterator())
                } else result+=Visit(frame.node,child,Label.NONTREE)
            } else {
                stack.removeAt(stack.lastIndex)
                result+=Visit(stack.lastOrNull()?.node ?: start,frame.node,Label.REVERSE)
            }
        }
        return result
    }
}
