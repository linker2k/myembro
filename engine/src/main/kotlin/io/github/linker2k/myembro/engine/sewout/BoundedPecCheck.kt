// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.Stitch

/** Frozen Python gate v5 decode(): bounded PEC walk, including combined TRIM+JUMP flags.
 * The public reader remains unchanged; this is the byte-level validity/command check used for sewout. */
internal fun boundedPecStitches(bytes: ByteArray): List<Stitch> {
    fun u(index: Int) = bytes[index].toInt() and 255
    require(bytes.size >= 12) { "Incomplete PES header" }
    val pointer = (0..3).sumOf { u(8+it).toLong() shl (8*it) }
    require(pointer >= 12 && pointer+528 <= bytes.size) { "Invalid PEC pointer/header" }
    val p=pointer.toInt()
    require(u(p)==76 && u(p+1)==65 && u(p+2)==58) { "Invalid PEC label" }
    val length=(0..2).sumOf { u(p+514+it) shl (8*it) }
    val end=(pointer+512+length).toInt()
    require(length>=17 && end<=bytes.size) { "PEC stitch-block length exceeds file" }
    var position=p+528;var x=0;var y=0
    val result=mutableListOf<Stitch>()
    fun next(): Int { require(position<end) { "Truncated PEC instruction" };return u(position++) }
    fun signed12(value: Int) = if (value and 2048 != 0) value-4096 else value
    fun signed7(value: Int) = if (value and 64 != 0) value-128 else value
    while (position<end) {
        val a=next()
        if (a==255 && (position==end || u(position)==0)) {
            result+=Stitch(x,y,Command.END)
            return result
        }
        var b=next()
        if (a==254 && b==176) { next();result+=Stitch(x,y,Command.COLOR_CHANGE);continue }
        var flags=0
        val dx=if (a and 128 != 0) {
            flags=flags or (a and 48)
            val value=signed12(((a and 15) shl 8) or b);b=next();value
        } else signed7(a)
        val dy=if (b and 128 != 0) {
            flags=flags or (b and 48)
            signed12(((b and 15) shl 8) or next())
        } else signed7(b)
        if (flags and 32 != 0) result+=Stitch(x,y,Command.TRIM)
        x+=dx;y+=dy
        result+=Stitch(x,y,if (flags!=0) Command.JUMP else Command.STITCH)
    }
    error("Missing PEC END within declared stitch block")
}
