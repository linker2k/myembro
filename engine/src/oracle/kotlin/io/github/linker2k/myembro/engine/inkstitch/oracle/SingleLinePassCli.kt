// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Diagnostic only: source font geometry and exact undirected machine edges, with S1 locks excluded. */
object SingleLinePassCli {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args[0]);val fontsDir=File(args[1]);val text=args.getOrElse(2){"Yeonwoo"}
        val height=args.getOrElse(3){"10"}.toDouble()
        root.mkdirs()
        val catalog=Json.parseToJsonElement(File("app/src/main/assets/fonts/catalog.json").readText()).jsonObject["fonts"]!!.jsonArray
        val results=buildJsonArray {
            for(raw in catalog) {
                val f=raw.jsonObject
                if(f["kind"]!!.jsonPrimitive.content!="single-line-svg")continue
                val id=f["id"]!!.jsonPrimitive.content
                val file=File(fontsDir,"$id.svg");if(!file.isFile)continue
                val sha=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
                check(sha==f["sha256"]!!.jsonPrimitive.content){"Font hash differs: $id"}
                val font=SvgFont.parse(file.readText())
                check(text.all{font.supports(it)}){"Unsupported $id"}
                val source=font.layout(text, font.unitsPerEm/height).map{line->line.map{it.x to -it.y}}
                val obj=TextObject("measure",text=text,fontId=id,heightMm=height,fontKind=FontKind.OUTLINE,
                    contours=source,contourSourceHeight=font.unitsPerEm,singleLine=true,stitchStyle=StitchStyle.RUNNING)
                for(style in listOf(StitchStyle.RUNNING,StitchStyle.BEAN)) {
                    val pattern=obj.copy(stitchStyle=style).localPattern()
                    val body=GeneratedPatternProcessor.stripGeneratedLocks(pattern)
                    val edges=body.stitches.zipWithNext().filter{(a,b)->a.command==Command.STITCH && b.command==Command.STITCH && (a.x!=b.x || a.y!=b.y)}
                    File(root,"$id-${style.name}.csv").writeText("x1,y1,x2,y2\n"+edges.joinToString("\n"){(a,b)->"${a.x},${a.y},${b.x},${b.y}"})
                    val counts=edges.groupingBy{(a,b)->
                        val p=a.x to a.y;val q=b.x to b.y
                        if(p.first<q.first || p.first==q.first && p.second<=q.second)listOf(p,q)else listOf(q,p)
                    }.eachCount()
                    val histogram=counts.values.groupingBy{it}.eachCount().toSortedMap()
                    add(buildJsonObject {
                        put("font",id);put("style",style.name);put("sha256",sha);put("text",text);put("heightMm",height)
                        put("bodyStitches",body.stitchCount);put("bodyEdges",edges.size);put("uniqueEdges",counts.size)
                        put("lockStitches",pattern.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY].orEmpty().split(',').count{it.isNotBlank()})
                        put("edgeCountHistogram",buildJsonObject{histogram.forEach{(n,count)->put(n.toString(),count)}})
                        put("sourceMm",buildJsonArray{source.forEach{path->add(buildJsonArray{path.forEach{(x,y)->add(buildJsonArray{add(x*height/font.unitsPerEm);add(y*height/font.unitsPerEm)})}})}})
                    })
                }
            }
        }
        File(root,"measurements.json").writeText(Json{prettyPrint=true}.encodeToString(JsonArray.serializer(),results))
        val embroidery=buildJsonArray {
            for(dir in File("app/src/main/assets/fonts/bundled").listFiles().orEmpty()) {
                val font=File(dir,"ltr.svg").inputStream().use { io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchFont.parse(it,File(dir,"font.json").readText()) }
                val placements=io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchLettering.layout(font,text,height,1.0)
                if(placements.any { p -> p.glyph.elements.any { it !is io.github.linker2k.myembro.engine.inkstitch.lettering.InkRun } })continue
                val pattern=io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchLettering.render(font,text,height,letterSpacingMm=1.0)
                val body=GeneratedPatternProcessor.stripGeneratedLocks(pattern)
                val edges=body.stitches.zipWithNext().filter{(a,b)->a.command==Command.STITCH && b.command==Command.STITCH && (a.x!=b.x || a.y!=b.y)}
                File(root,"${dir.name}-FONT_ORIGINAL.csv").writeText("x1,y1,x2,y2\n"+edges.joinToString("\n"){(a,b)->"${a.x},${a.y},${b.x},${b.y}"})
                val counts=edges.groupingBy{(a,b)->if(a.x<b.x || a.x==b.x && a.y<=b.y) listOf(a.x,a.y,b.x,b.y) else listOf(b.x,b.y,a.x,a.y)}.eachCount()
                add(buildJsonObject {
                    put("font",dir.name);put("style","FONT_ORIGINAL");put("text",text);put("heightMm",height)
                    put("bodyStitches",body.stitchCount);put("bodyEdges",edges.size);put("uniqueEdges",counts.size)
                    put("edgeCountHistogram",buildJsonObject { counts.values.groupingBy{it}.eachCount().toSortedMap().forEach{(n,count)->put(n.toString(),count)} })
                    put("sourceParameters",buildJsonArray { placements.flatMap{it.glyph.elements}.map{el->
                        buildJsonObject { el.params.names.filter{it.contains("repeat") || it.contains("method")}.forEach{key->put(key,el.params[key])} }
                    }.distinct().forEach{add(it)} })
                    put("sourceMm",buildJsonArray { placements.forEach { p -> p.glyph.elements.forEach { el ->
                        (el as io.github.linker2k.myembro.engine.inkstitch.lettering.InkRun).paths.forEach { path ->
                            add(buildJsonArray { path.points.forEach { point -> val mm=p.mm(point);add(buildJsonArray{add(mm.x);add(mm.y)}) } })
                        }
                    } } })
                })
            }
        }
        File(root,"embroidery.json").writeText(Json{prettyPrint=true}.encodeToString(JsonArray.serializer(),embroidery))
        println("Measured ${results.size/2} public SVG fonts, RUNNING and BEAN; text=$text height=$height mm -> $root")
    }
}
