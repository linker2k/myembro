// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

/** FORWARD = one place earlier in sewing order; BACKWARD = one place later. */
enum class BlockOrderMove { FORWARD, BACKWARD, FIRST, LAST }

internal fun moveRegions(order: List<String>, selected: Set<String>, move: BlockOrderMove): List<String> {
    require(selected.isNotEmpty() && selected.all { it in order }) { "UNKNOWN_REGION" }
    val next=order.toMutableList()
    when(move) {
        BlockOrderMove.FIRST -> return order.filter { it in selected } + order.filterNot { it in selected }
        BlockOrderMove.LAST -> return order.filterNot { it in selected } + order.filter { it in selected }
        BlockOrderMove.FORWARD -> for(i in 1 until next.size) if(next[i] in selected && next[i-1] !in selected) {
            val old=next[i-1];next[i-1]=next[i];next[i]=old
        }
        BlockOrderMove.BACKWARD -> for(i in next.size-2 downTo 0) if(next[i] in selected && next[i+1] !in selected) {
            val old=next[i+1];next[i+1]=next[i];next[i]=old
        }
    }
    return next
}
