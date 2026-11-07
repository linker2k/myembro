// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from pyembroidery 1.5.1 DstWriter.py / default EmbEncoder.py (MIT).
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.*
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs

/** Upstream default header is not extended; trim_at=3. */
data class DstWriteSettings(val extendedHeader:Boolean=false,val trimAt:Int=3)

/** Tajima DST, default ROUND=True, FULL_JUMP=False, maximum axis delta=121 units. */
object DstWriter {
    internal fun encodeRecord(dx:Int,dy:Int,command:Command):ByteArray {
        var x=dx;var y=-dy;var b0=0;var b1=0;var b2=0
        when(command){
            Command.STITCH,Command.JUMP->{
                if(command==Command.JUMP)b2=b2 or 128
                b2=b2 or 3
                fun takeX(threshold:Int,value:Int,positive:Int,negative:Int,byte:Int){
                    var bits=0
                    if(x>threshold){bits=bits or (1 shl positive);x-=value}
                    if(x< -threshold){bits=bits or (1 shl negative);x+=value}
                    when(byte){0->b0=b0 or bits;1->b1=b1 or bits;2->b2=b2 or bits}
                }
                fun takeY(threshold:Int,value:Int,positive:Int,negative:Int,byte:Int){
                    var bits=0
                    if(y>threshold){bits=bits or (1 shl positive);y-=value}
                    if(y< -threshold){bits=bits or (1 shl negative);y+=value}
                    when(byte){0->b0=b0 or bits;1->b1=b1 or bits;2->b2=b2 or bits}
                }
                takeX(40,81,2,3,2);takeX(13,27,2,3,1);takeX(4,9,2,3,0);takeX(1,3,0,1,1);takeX(0,1,0,1,0)
                takeY(40,81,5,4,2);takeY(13,27,5,4,1);takeY(4,9,5,4,0);takeY(1,3,7,6,1);takeY(0,1,7,6,0)
                require(x==0&&y==0){"The delta given to the DST writer exceeds maximum allowed"}
            }
            Command.COLOR_CHANGE,Command.STOP->b2=0xc3
            Command.END->b2=0xf3
            Command.TRIM->Unit // The writer expands trim into jump records instead.
        }
        return byteArrayOf(b0.toByte(),b1.toByte(),b2.toByte())
    }
    fun write(pattern:EmbPattern,settings:DstWriteSettings=DstWriteSettings()):ByteArray {
        val normalized=LegacyWriterEncoder.encode(pattern,121,false);val stitches=normalized.stitches
        val out=ByteArrayOutputStream()
        fun text(s:String){out.write(s.toByteArray(Charsets.UTF_8))}
        fun fmt(s:String,vararg args:Any)=String.format(Locale.ROOT,s,*args)
        val name=pattern.metadata["name"]?:"Untitled"
        text("LA:"+name+" ".repeat((16-name.codePointCount(0,name.length)).coerceAtLeast(0))+"\r")
        text(fmt("ST:%7d\r",stitches.size));text(fmt("CO:%3d\r",stitches.count{it.command==Command.COLOR_CHANGE}))
        text(fmt("+X:%5d\r",abs(stitches.maxOf{it.x}).toLong()));text(fmt("-X:%5d\r",abs(stitches.minOf{it.x}).toLong()))
        text(fmt("+Y:%5d\r",abs(stitches.maxOf{it.y}).toLong()));text(fmt("-Y:%5d\r",abs(stitches.minOf{it.y}).toLong()))
        val ax=stitches.last().x.toLong();val ay=-stitches.last().y.toLong()
        text(fmt("AX:%s%5d\r",if(ax>=0)"+" else "-",abs(ax)));text(fmt("AY:%s%5d\r",if(ay>=0)"+" else "-",abs(ay)))
        text(fmt("MX:+%5d\r",0));text(fmt("MY:+%5d\r",0));text("PD:******\r")
        if(settings.extendedHeader){
            pattern.metadata["author"]?.let{text("AU:$it\r")};pattern.metadata["copyright"]?.let{text("CP:$it\r")}
            for(t in normalized.threads)text("TC:${t.hex},${t.description?:"None"},${t.catalogNumber?:"None"}\r")
        }
        out.write(0x1a);while(out.size()<512)out.write(0x20)
        var x=0;var y=0
        for(s in stitches){
            val dx=Math.rint(s.x-x).toInt();val dy=Math.rint(s.y-y).toInt();x+=dx;y+=dy
            if(s.command==Command.TRIM){
                var delta=-4;out.write(encodeRecord(-delta/2,-delta/2,Command.JUMP))
                for(p in 1 until settings.trimAt-1){out.write(encodeRecord(delta,delta,Command.JUMP));delta=-delta}
                out.write(encodeRecord(delta/2,delta/2,Command.JUMP))
            }else out.write(encodeRecord(dx,dy,s.command))
        }
        return out.toByteArray()
    }
}
