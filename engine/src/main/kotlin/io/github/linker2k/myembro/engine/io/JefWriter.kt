// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from pyembroidery 1.5.1 JefWriter.py (MIT).
package io.github.linker2k.myembro.engine.io
import io.github.linker2k.myembro.engine.model.*
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class JefWriteSettings(val trims:Boolean=false,val trimAt:Int=3,val date:String?=null)

object JefWriter {
    /** Original default: local current timestamp. Tests alone pass a fixed date. */
    fun write(pattern:EmbPattern,date:String=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))):ByteArray {
        return write(pattern,JefWriteSettings(date=date))
    }
    fun write(pattern:EmbPattern,settings:JefWriteSettings):ByteArray {
        val date=settings.date?:LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val p=LegacyWriterEncoder.encode(pattern,127,true);val stitches=p.stitches
        val palette=mutableListOf<Int>();var lastIndex=-1;var lastThread:EmbThread?=null;var index=0;var toggled=false
        for(s in stitches){
            if(s.command==Command.COLOR_CHANGE||index==0){
                val thread=p.threads[index++] // Upstream rejects a completely empty design here.
                var nearest=JefThreads.nearest(thread.color)
                if(lastIndex==nearest&&lastThread?.sameThread(thread)==false)nearest=JefThreads.nearest(thread.color,nearest)
                palette+=nearest;lastIndex=nearest;lastThread=thread;toggled=false
            }
            if(s.command==Command.STOP){toggled=!toggled;palette+=if(toggled)0 else lastIndex}
        }
        val f=ByteArrayOutputStream()
        fun le(v:Int){for(i in 0..3)f.write(v ushr (i*8) and 255)}
        le(0x74+palette.size*8);le(0x14);f.write(date.toByteArray(Charsets.UTF_8));f.write(0);f.write(0);le(palette.size)
        var points=1
        for(s in stitches)when(s.command){Command.STITCH->points++;Command.JUMP,Command.COLOR_CHANGE,Command.STOP->points+=2;Command.TRIM->if(settings.trims)points+=2*settings.trimAt;Command.END->break}
        le(points)
        val w=Math.rint(stitches.maxOf{it.x}-stitches.minOf{it.x}).toInt();val h=Math.rint(stitches.maxOf{it.y}-stitches.minOf{it.y}).toInt()
        le(hoopSize(w,h));val hw=Math.rint(w/2.0).toInt();val hh=Math.rint(h/2.0).toInt()
        repeat(2){le(hw);le(hh)}
        for((x,y)in listOf(550-hw to 550-hh,250-hw to 250-hh,700-hw to 1000-hh,700-hw to 1000-hh)){
            repeat(2){le(if(minOf(x,y)>=0)x else -1);le(if(minOf(x,y)>=0)y else -1)}
        }
        palette.forEach{le(it)};repeat(palette.size){le(0x0d)}
        var x=0;var y=0
        for(s in stitches){
            val dx=Math.rint(s.x-x).toInt();val dy=Math.rint(s.y-y).toInt();x+=dx;y+=dy
            when(s.command){
                Command.STITCH->{f.write(dx and 255);f.write(-dy and 255)}
                Command.COLOR_CHANGE,Command.STOP->{f.write(0x80);f.write(1);f.write(dx and 255);f.write(-dy and 255)}
                Command.JUMP->{f.write(0x80);f.write(2);f.write(dx and 255);f.write(-dy and 255)}
                Command.TRIM->if(settings.trims)repeat(settings.trimAt){f.write(byteArrayOf(0x80.toByte(),2,0,0))}
                Command.END->break
            }
        }
        f.write(0x80);f.write(0x10);return f.toByteArray()
    }
    internal fun hoopSize(w:Int,h:Int)=when{
        w<500&&h<500->1
        w<1260&&h<1100->3
        w<1400&&h<2000->2
        w<2000&&h<2000->4
        else->0
    }
}
