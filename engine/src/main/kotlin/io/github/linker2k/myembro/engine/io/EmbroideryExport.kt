// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

enum class ExportFormat(val extension:String,val brand:String) {
    PES("pes","Brother"),DST("dst","Tajima"),JEF("jef","Janome"),EXP("exp","Melco"),VP3("vp3","Husqvarna");
    val mimeType get()="application/octet-stream"
    fun fileName(name:String)=name.substringBeforeLast('.',name)+".$extension"
}

/** All formats first pass the exact same PES preparation/output gates. No destination is opened here. */
object EmbroideryExport {
    data class Result(val bytes:ByteArray,val fileName:String)
    fun prepare(data:ByteArray,fileName:String,format:ExportFormat=ExportFormat.PES,
        settings:PesWriteSettings=PesWriteSettings(),jefSettings:JefWriteSettings=JefWriteSettings()):Result {
        val checked=PesExport.prepare(data,fileName,settings)
        if(format==ExportFormat.PES)return Result(checked.bytes,checked.fileName)
        // Feed every writer the same decoded, validated wire pattern; preserve PES pass-through.
        val p=PesReader.read(checked.bytes)
        val bytes=when(format){
            ExportFormat.DST->DstWriter.write(p)
            ExportFormat.JEF->JefWriter.write(p,jefSettings)
            ExportFormat.EXP->ExpWriter.write(p)
            ExportFormat.VP3->Vp3Writer.write(p)
            ExportFormat.PES->error("handled above")
        }
        return Result(bytes,format.fileName(checked.fileName))
    }
}
