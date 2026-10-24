// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.Command

/** Exact integer checks on encoded PEC destinations, in 0.1mm. No epsilon or recentering. */
object PecViewportCheck {
    data class Result(val minX:Int, val minY:Int, val maxX:Int, val maxY:Int,
        val headerWidth:Int, val headerHeight:Int, val errors:List<String>) {
        val valid:Boolean get()=errors.isEmpty()
    }
    fun check(bytes:ByteArray, hoop:HoopSpec):Result {
        val commands=boundedPecStitches(bytes)
        val points=commands.filter { it.command==Command.STITCH || it.command==Command.JUMP }
        fun u(i:Int)=bytes[i].toInt() and 255
        val p=(0..3).sumOf { u(8+it) shl (8*it) }
        fun u16(i:Int)=u(i) or (u(i+1) shl 8)
        val width=u16(p+520);val height=u16(p+522)
        val minX=points.minOfOrNull { it.x } ?: 0; val minY=points.minOfOrNull { it.y } ?: 0
        val maxX=points.maxOfOrNull { it.x } ?: 0; val maxY=points.maxOfOrNull { it.y } ?: 0
        val errors=buildList {
            if(points.isEmpty())add("PEC_EMPTY: no recorded STITCH/JUMP destinations")
            if(minX!=SewoutLimits.PEC_MIN_COORD_UNITS_0P1MM || minY!=SewoutLimits.PEC_MIN_COORD_UNITS_0P1MM)add("PEC_ORIGIN: min=($minX,$minY), required=(0,0)")
            if(maxX>SewoutLimits.hoopMaxXUnits0p1mm(hoop) || maxY>SewoutLimits.hoopMaxYUnits0p1mm(hoop))add("PEC_HOOP: max=($maxX,$maxY), hoop=(${hoop.width},${hoop.height}) exclusive size bound")
            if(width.toLong()!=maxX.toLong()-minX || height.toLong()!=maxY.toLong()-minY)
                add("PEC_DIMENSIONS: header=($width,$height), actual=(${maxX.toLong()-minX},${maxY.toLong()-minY})")
        }
        return Result(minX,minY,maxX,maxY,width,height,errors)
    }
    /** require, rather than JVM assert: enabled in release builds too. */
    fun requireValid(bytes:ByteArray,hoop:HoopSpec):ByteArray {
        val result=check(bytes,hoop)
        require(result.valid) { result.errors.joinToString("; ") }
        return bytes
    }
}
