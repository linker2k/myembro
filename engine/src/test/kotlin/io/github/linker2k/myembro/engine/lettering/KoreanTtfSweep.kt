// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.PathIterator
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest

/** Local exhaustive JVM sweep. No Ink/Stitch execution and no private fonts. */
object KoreanTtfSweep {
    private val context=FontRenderContext(null,true,true)
    private data class Face(val id:String,val path:String,val font:Font) {
        val reference:Double by lazy {
            val points=outline(font,'자'.code).flatten()
            points.maxOf { it.second }-points.minOf { it.second }
        }
    }
    private fun faces(manifest:File):List<Face> = Json.parseToJsonElement(manifest.readText()).jsonObject.getValue("fonts").jsonArray.map {
        val f=it.jsonObject;val file=File(f.getValue("path").jsonPrimitive.content)
        val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { b->"%02x".format(b) }
        require(hash==f.getValue("sha256").jsonPrimitive.content) { "Font hash mismatch: $file" }
        Face(f.getValue("id").jsonPrimitive.content,file.path,Font.createFont(Font.TRUETYPE_FONT,file).deriveFont(1000f))
    }
    private fun outline(font:Font,code:Int):List<List<Pair<Double,Double>>> {
        val vector=font.createGlyphVector(context,String(Character.toChars(code)))
        val it=FlatteningPathIterator(vector.getGlyphOutline(0).getPathIterator(null),0.2)
        val paths=mutableListOf<List<Pair<Double,Double>>>()
        var path=mutableListOf<Pair<Double,Double>>();val p=DoubleArray(6)
        while(!it.isDone) {
            when(it.currentSegment(p)) {
                PathIterator.SEG_MOVETO->path=mutableListOf(p[0] to p[1])
                PathIterator.SEG_LINETO->path+=p[0] to p[1]
                PathIterator.SEG_CLOSE->if(path.size>=3) { path+=path.first();paths+=path }
            }
            it.next()
        }
        return paths
    }
    @JvmStatic fun main(args:Array<String>) {
        val fonts=faces(File(args[1]))
        if(args[0]=="coverage") {
            val common=(0xac00..0xd7a3).filter { cp->fonts.all { it.font.canDisplay(cp) } }
            File(args[2]).writeText(Json.encodeToString(JsonArray.serializer(),JsonArray(common.map(::JsonPrimitive))))
            println("COVERAGE: ${fonts.size} fonts; ${common.size} common Hangul syllables")
            return
        }
        require(args[0]=="run")
        val codes=Json.parseToJsonElement(File(args[2]).readText()).jsonObject.getValue("characters").jsonArray
            .map { it.jsonObject.getValue("codepoint").jsonPrimitive.int }.take(args.getOrNull(6)?.toInt() ?: 200)
        val out=File(args[3]).apply { mkdirs() };val workers=args[4].toInt()
        require(workers in 1..8 && codes.isNotEmpty())
        val failFast=args.getOrNull(7)=="fail-fast"
        val heights=args.getOrNull(5)?.split(',')?.map(String::toDouble) ?: (TatamiLettering.MIN_HEIGHT_MM.toInt()..TatamiLettering.MAX_HEIGHT_MM.toInt()).map { it.toDouble() }
        require(heights.all { it.toDouble() in TatamiLettering.MIN_HEIGHT_MM..TatamiLettering.MAX_HEIGHT_MM })
        val pool=Executors.newFixedThreadPool(workers)
        val cache=ConcurrentHashMap<String,List<List<Pair<Double,Double>>>>()
        val completed=AtomicInteger();val started=System.nanoTime()
        val futures=mutableListOf<Future<*>>()
        for(face in fonts) for(height in heights) futures+=pool.submit {
            val file=File(out,"${face.id}--${height}mm.csv")
            val old=if(file.isFile)file.readLines() else emptyList()
            val valid=old.drop(1).filter { line->val a=line.split(',');a.size==5 && a[0].toIntOrNull() in codes && a[1] in listOf("PASS","BLOCK") }
                .associateBy { it.substringBefore(',').toInt() }.values.toList()
            file.writeText("codepoint,verdict,stitches,elapsed_ms,reasons\n"+valid.joinToString("\n",postfix=if(valid.isEmpty()) "" else "\n"))
            val done=valid.map { it.substringBefore(',').toInt() }.toSet()
            completed.addAndGet(done.size)
            java.io.FileOutputStream(file,true).bufferedWriter().use { writer ->
                for(code in codes) {
                    if(failFast && valid.any { it.split(',')[1]=="BLOCK" }) break
                    if(code in done) continue
                    val began=System.nanoTime();var count=0;val reasons=mutableSetOf<String>()
                    try {
                        require(face.font.canDisplay(code)) { "UNSUPPORTED_GLYPH" }
                        val paths=cache.computeIfAbsent("${face.id}:$code") { outline(face.font,code) }
                        require(paths.isNotEmpty()) { "EMPTY_GLYPH" }
                        val pattern=LetteringEngine.fromContours(paths,height.toDouble(),sourceHeight=face.reference,
                            stitchStyle=StitchStyle.FILL,outline=true)
                        count=pattern.stitchCount
                        val bytes=PesWriter.writeV6(pattern)
                        val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
                        reasons+=report.findings.filter { it.severity==Severity.BLOCKER }.map { it.code.name }
                        if(report.hasBlockingIssues && reasons.isEmpty()) reasons+="BLOCKER_DETAILS_SUPPRESSED"
                        if(!report.analysisComplete) reasons+="ANALYSIS_INCOMPLETE"
                        if(reasons.isNotEmpty()) {
                            val examples=File(out,"examples").apply { mkdirs() }
                            for(reason in reasons) {
                                val marker=File(examples,"${face.id}--$reason.first")
                                if(marker.createNewFile()) {
                                    val name="${face.id}--U${code.toString(16)}--${height}mm.pes"
                                    File(examples,name).writeBytes(bytes);marker.writeText(name)
                                }
                            }
                        }
                    } catch(e:Exception) {
                        val reason="GENERATION_${e.javaClass.simpleName}";reasons+=reason
                        val example=File(out,"examples/${face.id}--$reason.error.txt").apply { parentFile.mkdirs() }
                        if(example.createNewFile()) example.writeText("U+${code.toString(16)} height=$height: ${e.stackTraceToString()}")
                    }
                    val elapsed=(System.nanoTime()-began)/1_000_000
                    writer.appendLine("$code,${if(reasons.isEmpty()) "PASS" else "BLOCK"},$count,$elapsed,${reasons.sorted().joinToString(";")}")
                    writer.flush();completed.incrementAndGet()
                    if(failFast && reasons.isNotEmpty()) break
                }
            }
            println("DONE ${face.id} ${height}mm ${completed.get()}/${fonts.size*codes.size*heights.size} elapsed_s=${(System.nanoTime()-started)/1_000_000_000}")
        }
        pool.shutdown()
        try { futures.forEach { it.get() } } finally { pool.shutdownNow() }
        println("COMPLETE ${completed.get()} cases; wall_ms=${(System.nanoTime()-started)/1_000_000}")
    }
}
