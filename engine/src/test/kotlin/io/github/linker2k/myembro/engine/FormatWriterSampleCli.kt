// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine
import io.github.linker2k.myembro.engine.io.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json as KotlinJson
import java.io.File

/** Local comparison: source stitches are decoded only by our PesReader, shared via JSON. */
object FormatWriterSampleCli {
    @JvmStatic fun main(args:Array<String>){
        val input=File(args[0]);val out=File(args[1]);val json=File(out,"inputs").apply{mkdirs()};val bytes=File(out,"kotlin").apply{mkdirs()}
        val files=input.listFiles()!!.filter{it.extension.lowercase()=="pes"}.sortedBy{it.name}
        val names=mutableListOf<String>()
        for(file in files){
            val p=PesReader.read(file.readBytes());val id=file.nameWithoutExtension;names+=id
            val row=buildJsonObject {
                put("id",id);put("metadata",buildJsonObject{p.metadata.forEach{(k,v)->put(k,v)}})
                put("threads",buildJsonArray{p.threads.forEach{t->add(buildJsonObject{
                    put("color",t.color and 0xffffff);t.description?.let{put("description",it)};t.catalogNumber?.let{put("catalog",it)}
                    t.brand?.let{put("brand",it)};t.chart?.let{put("chart",it)}
                })}})
                put("stitches",buildJsonArray{p.stitches.forEach{s->add(buildJsonArray{add(s.x);add(s.y);add(s.command.name)})}})
            }
            File(json,"$id.json").writeText(row.toString())
            File(bytes,"$id.dst").writeBytes(DstWriter.write(p));File(bytes,"$id.exp").writeBytes(ExpWriter.write(p))
            File(bytes,"$id.jef").writeBytes(JefWriter.write(p,"20000102030405"));File(bytes,"$id.vp3").writeBytes(Vp3Writer.write(p))
        }
        File(out,"manifest.json").writeText(buildJsonArray{names.forEach{add(it)}}.toString())
        println("FORMAT_WRITER_EXPORT count=${files.size} input=$input output=$out")
    }
}

internal object FormatWriterSamples {
    fun compare(ext:String,write:(io.github.linker2k.myembro.engine.model.EmbPattern)->ByteArray){
        for(name in listOf("sample_02_lightning","sample_03_crown","sample_05_rocket")){
            val source=checkNotNull(javaClass.getResourceAsStream("/format-writers/samples/$name.pes")).use{it.readBytes()}
            FormatWriterFixtures.compare("samples/$name.$ext",write(PesReader.read(source)))
        }
    }
}
