// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import org.locationtech.jts.geom.LineString

/** Optional thread-local inspection of actual routing candidates; never changes predicate results. */
internal object LineCrossesTrace {
    private val listener=ThreadLocal<((LineString,LineString,Boolean,String)->Unit)?>()
    fun <T> observe(sink: (LineString,LineString,Boolean,String)->Unit, action: ()->T): T {
        val previous=listener.get();listener.set(sink)
        return try { action() } finally { listener.set(previous) }
    }
    fun emit(row: LineString,candidate: LineString,result: Boolean,path: String) { listener.get()?.invoke(row,candidate,result,path) }
}
