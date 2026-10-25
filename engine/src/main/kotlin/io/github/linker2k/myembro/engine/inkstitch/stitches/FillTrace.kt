// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitches

/** Opt-in, thread-local observations only. No routing/geometry/default changes. */
object FillTrace {
    private val sink=ThreadLocal<((String,String)->Unit)?>()
    fun <T> observe(listener:(String,String)->Unit,action:()->T):T {
        val previous=sink.get();sink.set(listener)
        return try { action() } finally { sink.set(previous) }
    }
    internal fun emit(stage:String,payload:()->String) { sink.get()?.invoke(stage,payload()) }
    internal fun points(paths:List<List<FillPoint>>):String=paths.joinToString(";") { path->
        path.joinToString("|") { "${it.x.toBits()}:${it.y.toBits()}:${it.tags.sorted().joinToString(",")}" }
    }
    internal fun graph(graph:FillRouting.Graph):String=buildString {
        for((n,d) in graph.nodes)append("N:${n.x.toBits()},${n.y.toBits()},${d.outline},${d.projection.toBits()};")
        for(e in graph.edges.values)append("E:${e.id},${e.a.x.toBits()}:${e.a.y.toBits()},${e.b.x.toBits()}:${e.b.y.toBits()},${e.key},${e.segment},${e.weight.toBits()};")
    }
}
