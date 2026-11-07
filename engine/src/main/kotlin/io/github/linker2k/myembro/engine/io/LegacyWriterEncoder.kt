// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from pyembroidery 1.5.1 EmbEncoder.py (MIT; see THIRD_PARTY_NOTICES).
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Default Transcoder path for the six commands represented by EmbPattern.
 * No ties, explicit trim, transforms, or sew-to override are enabled by these writers.
 * Keep interpolated positions fractional until the writer's cumulative round-to-even step.
 */
internal object LegacyWriterEncoder {
    data class Point(val x:Double,val y:Double,val command:Command)
    data class Encoded(val stitches:List<Point>,val threads:List<EmbThread>)
    fun encode(pattern:EmbPattern,maxLength:Int,fullJump:Boolean,maxStitch:Int=maxLength):Encoded {
        val out=mutableListOf<Point>();val threads=mutableListOf<EmbThread>()
        var x=0.0;var y=0.0;var trimmed=true;var order=-1
        fun add(command:Command,xx:Double=x,yy:Double=y){out+=Point(xx,yy,command)}
        fun change(){
            order++
            // Upstream get_thread_or_filler uses a random color when the source has no thread.
            threads+=pattern.threads.getOrNull(order) ?: EmbThread(0xff000000.toInt() or kotlin.random.Random.nextInt(0x1000000))
            if(order!=0)add(Command.COLOR_CHANGE)
            trimmed=true
        }
        fun gap(xx:Double,yy:Double,limit:Int=maxLength){
            val dx=xx-x;val dy=yy-y
            if(abs(dx)>limit||abs(dy)>limit){
                val steps=max(ceil(abs(dx/limit)),ceil(abs(dy/limit))).toInt()
                val sx=dx/steps;val sy=dy/steps
                var qx=x;var qy=y
                for(i in 1 until steps){qx+=sx;qy+=sy;add(Command.JUMP,qx,qy);x=qx;y=qy}
            }
        }
        for(s in pattern.stitches){
            val xx=s.x.toDouble();val yy=s.y.toDouble()
            when(s.command){
                Command.STITCH->{
                    if(trimmed){
                        if(order==-1)change()
                        trimmed=false;gap(xx,yy)
                        if(fullJump&&(x!=xx||y!=yy)){add(Command.JUMP,xx,yy);x=xx;y=yy}
                    }else gap(xx,yy,maxStitch)
                    add(Command.STITCH,xx,yy);x=xx;y=yy
                }
                Command.JUMP->{gap(xx,yy);add(Command.JUMP,xx,yy);x=xx;y=yy}
                Command.TRIM->if(!trimmed){add(Command.TRIM);trimmed=true}
                Command.COLOR_CHANGE->change()
                Command.STOP->{add(Command.STOP);trimmed=true}
                Command.END->{add(Command.END);return Encoded(out,threads)}
            }
        }
        add(Command.END)
        return Encoded(out,threads)
    }
}
