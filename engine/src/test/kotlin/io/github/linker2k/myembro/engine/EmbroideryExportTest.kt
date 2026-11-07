// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*
class EmbroideryExportTest {
    private fun source():ByteArray {
        val p=EmbPattern(listOf(100 to -50,100 to 50,-100 to 50,-100 to -50,100 to -50).map{Stitch(it.first,it.second,Command.STITCH)},listOf(PecThreads.forIndex(5)))
        return PesWriter.write(p)
    }
    @Test fun allFormatsUseOneCheckedPatternAndMatchingExtensionAndBytes(){
        val bytes=source();val before=bytes.copyOf();val p=PesReader.read(PesExport.prepare(bytes,"rose.pes").bytes)
        val date=JefWriteSettings(date="20000102030405")
        for(f in ExportFormat.entries){
            val result=EmbroideryExport.prepare(bytes,"rose.pes",f,jefSettings=date)
            val expected=when(f){ExportFormat.PES->bytes;ExportFormat.DST->DstWriter.write(p);ExportFormat.EXP->ExpWriter.write(p);ExportFormat.JEF->JefWriter.write(p,date);ExportFormat.VP3->Vp3Writer.write(p)}
            assertEquals("rose.${f.extension}",result.fileName);assertEquals("application/octet-stream",f.mimeType)
            assertContentEquals(expected,result.bytes);assertContentEquals(before,bytes)
        }
    }
    @Test fun aBlockedHeaderCannotEscapeThroughAnotherFormat(){
        val bytes=source();bytes[22+520]=(bytes[22+520]+1).toByte()
        for(f in ExportFormat.entries)assertFailsWith<IllegalArgumentException>(f.name){EmbroideryExport.prepare(bytes,"bad.pes",f)}
    }
    @Test fun densityThirtyIsAllowedAndThirtyOneIsBlockedForEveryFormat(){
        fun dense(n:Int)=PesWriter.write(EmbPattern(List(n){i->Stitch(i%2,(i/2)%2,Command.STITCH)},listOf(EmbThread(0))),PesWriteSettings(inkstitchCommands=true))
        for(f in ExportFormat.entries){
            assertTrue(EmbroideryExport.prepare(dense(30),"allowed.pes",f).bytes.isNotEmpty())
            val error=assertFailsWith<IllegalArgumentException>(f.name){EmbroideryExport.prepare(dense(31),"blocked.pes",f)}
            assertContains(error.message.orEmpty(),"DENSITY_HOTSPOT")
        }
    }
    @Test fun invalidAndTruncatedInputIsRejectedForEveryFormat(){
        for(f in ExportFormat.entries)for(bytes in listOf(byteArrayOf(),"#PES0001".toByteArray()))
            assertFailsWith<Exception>(f.name){EmbroideryExport.prepare(bytes,"bad.pes",f)}
    }
}
