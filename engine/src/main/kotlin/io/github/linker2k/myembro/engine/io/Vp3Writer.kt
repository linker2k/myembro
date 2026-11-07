// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from pyembroidery 1.5.1 Vp3Writer.py (MIT).
package io.github.linker2k.myembro.engine.io
import io.github.linker2k.myembro.engine.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object Vp3Writer {
    fun write(pattern:EmbPattern):ByteArray=writeWithFiller(pattern){EmbThread(0xff000000.toInt() or kotlin.random.Random.nextInt(0x1000000))}
    /** Filler injection is internal for the original's random empty-block fallback oracle. */
    internal fun writeWithFiller(pattern:EmbPattern,filler:()->EmbThread):ByteArray {
        val p=LegacyWriterEncoder.encode(pattern,3200,false,maxStitch=255)
        val f=Buffer();val stitches=p.stitches
        val blocks=mutableListOf<Pair<List<LegacyWriterEncoder.Point>,EmbThread>>()
        var start=0;var thread=0
        fun threadAt()=p.threads.getOrNull(thread++)?:filler()
        for(i in stitches.indices)if(stitches[i].command==Command.COLOR_CHANGE){blocks+=stitches.subList(start,i) to threadAt();start=i}
        blocks+=stitches.subList(start,stitches.size) to threadAt()
        val minX=stitches.minOf{it.x};val minY=stitches.minOf{it.y};val maxX=stitches.maxOf{it.x};val maxY=stitches.maxOf{it.y}
        f.raw("%vsm%".toByteArray());f.u8(0);f.string16("Produced by     Software Ltd")
        val file=f.block(2);f.string16("")
        f.i32((maxX*100).toInt());f.i32((minY* -100).toInt());f.i32((minX*100).toInt());f.i32((maxY* -100).toInt())
        f.i32(stitches.count{it.command!=Command.END});f.u8(0);f.u8(blocks.size);f.u8(12);f.u8(0);f.u8(1)
        val design=f.block(3);val w=maxX-minX;val h=maxY-minY;val hw=w/2;val hh=h/2;val cx=maxX-hw;val cy=maxY-hh
        f.i32(cx.toInt()*100);f.i32(cy.toInt()* -100);repeat(3){f.u8(0)}
        f.i32(hw.toInt()* -100);f.i32(hw.toInt()*100);f.i32(hh.toInt()* -100);f.i32(hh.toInt()*100)
        f.i32(w.toInt()*100);f.i32(h.toInt()*100);f.string16("");f.raw(byteArrayOf(100,100))
        f.i32(4096);f.i32(0);f.i32(0);f.i32(4096);f.raw(byteArrayOf(120,120,80,80,1,0));f.string16("Produced by     Software Ltd");f.i16(blocks.size)
        for((i,block)in blocks.withIndex()){
            val (ss,t)=block;val color=f.block(5)
            val fx=if(i==0)0.0 else ss.firstOrNull()?.x?:0.0;val fy=if(i==0)0.0 else ss.firstOrNull()?.y?:0.0
            val lx=ss.lastOrNull()?.x?:0.0;val ly=ss.lastOrNull()?.y?:0.0
            f.i32((fx-cx).toInt()*100);f.i32((-(fy-cy)).toInt()*100)
            f.raw(byteArrayOf(1,0));f.u8(t.red);f.u8(t.green);f.u8(t.blue);f.raw(byteArrayOf(0,0,0,5,40))
            f.string8(t.catalogNumber?:"");f.string8(t.description?:t.hex);f.string8(t.brand?:"")
            f.i32((lx-fx).toInt()*100);f.i32((-(ly-fy)).toInt()*100)
            val sewing=f.block(1);f.raw(byteArrayOf(10,0xf6.toByte(),0));var x=fx;var y=fy
            for(s in ss){
                when(s.command){
                    Command.END->{f.raw(byteArrayOf(0x80.toByte(),3));break}
                    Command.TRIM->{f.raw(byteArrayOf(0x80.toByte(),3));continue}
                    Command.COLOR_CHANGE,Command.STOP,Command.JUMP->continue
                    else->Unit
                }
                val dx=(s.x-x).toInt();val dy=(s.y-y).toInt();x+=dx;y+=dy
                if(dx in -127..127&&dy in -127..127){f.u8(dx);f.u8(dy)}else{f.raw(byteArrayOf(0x80.toByte(),1));f.i16(dx);f.i16(dy);f.raw(byteArrayOf(0x80.toByte(),2))}
            }
            f.patch(sewing);f.u8(0);f.patch(color)
        }
        f.patch(design);f.patch(file);return f.bytes()
    }
    private class Buffer {
        private val out=ByteArrayOutputStream();private val data=DataOutputStream(out);private val patches=mutableListOf<Pair<Int,Int>>()
        fun u8(v:Int)=data.writeByte(v)
        fun i16(v:Int)=data.writeShort(v)
        fun i32(v:Int)=data.writeInt(v)
        fun raw(b:ByteArray)=data.write(b)
        fun string8(s:String){i16(s.codePointCount(0,s.length));raw(s.toByteArray(Charsets.UTF_8))}
        fun string16(s:String){i16(s.codePointCount(0,s.length)*2);raw(s.toByteArray(Charsets.UTF_16BE))}
        fun block(type:Int):Int {raw(byteArrayOf(0,type.toByte(),0));val at=out.size();i32(0);return at}
        fun patch(at:Int){patches+=at to (out.size()-at-4)}
        fun bytes():ByteArray=out.toByteArray().also{b->for((at,v)in patches)for(i in 0..3)b[at+i]=(v ushr (24-8*i)).toByte()}
    }
}
