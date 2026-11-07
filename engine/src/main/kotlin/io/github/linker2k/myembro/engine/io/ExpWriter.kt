// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from pyembroidery 1.5.1 ExpWriter.py / default EmbEncoder.py (MIT).
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.*
import java.io.ByteArrayOutputStream

/** Melco EXP. Default ROUND=True, FULL_JUMP=True, maximum axis delta=127 units. */
object ExpWriter {
    fun write(pattern:EmbPattern):ByteArray {
        val normalized=LegacyWriterEncoder.encode(pattern,127,true)
        val out=ByteArrayOutputStream();var x=0;var y=0
        for(s in normalized.stitches){
            val dx=Math.rint(s.x-x).toInt();val dy=Math.rint(s.y-y).toInt();x+=dx;y+=dy
            when(s.command){
                Command.STITCH->{out.write(dx and 255);out.write(-dy and 255)}
                Command.JUMP->{out.write(byteArrayOf(0x80.toByte(),0x04,dx.toByte(),(-dy).toByte()))}
                Command.TRIM->out.write(byteArrayOf(0x80.toByte(),0x80.toByte(),0x07,0))
                Command.COLOR_CHANGE,Command.STOP->out.write(byteArrayOf(0x80.toByte(),0x01,0,0))
                Command.END->Unit
            }
        }
        return out.toByteArray()
    }
}
