// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.model

/** SE400 safety policy: retain nonzero lock geometry, discard stationary commands and same-place trim restarts.
 * Readers never call this. Source indices allow generated lock/anchor provenance to be remapped. */
object ThreadStartCleanup {
    data class Result(val stitches: List<Stitch>, val sourceIndices: List<Int>, val removedNeedles: Int)
    fun clean(stitches: List<Stitch>): Result {
        val out=mutableListOf<Stitch>();val indices=mutableListOf<Int>()
        var x=0;var y=0;var removed=0
        fun samePlaceRestart(index: Int, point: Stitch): Boolean {
            var stationaryJump = false
            for (j in index+1 until stitches.size) {
                val next = stitches[j]
                when (next.command) {
                    Command.JUMP -> {
                        if (next.x != point.x || next.y != point.y) return false
                        stationaryJump = true
                    }
                    Command.STITCH -> return stationaryJump || (next.x == point.x && next.y == point.y)
                    Command.TRIM -> Unit
                    Command.STOP, Command.COLOR_CHANGE, Command.END -> return false
                }
            }
            return false
        }
        for ((index,s) in stitches.withIndex()) {
            if (s.command == Command.TRIM && samePlaceRestart(index,s)) { continue }
            val zero=s.x==x && s.y==y
            if (s.command==Command.JUMP && zero) continue
            if (s.command==Command.STITCH && zero) { removed++;continue }
            out+=s;indices+=index
            x=s.x;y=s.y
        }
        return Result(out,indices,removed)
    }
}
