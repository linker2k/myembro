// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.EditSession
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.roundToInt

/** Opt-in on development emulator-5556. Real Android software Canvas and PNG codec, no user input. */
class RenderPreviewInstrumentation: Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        val results=mutableListOf<String>()
        try {
            fun decode(project:Project,size:Int):Pair<Bitmap,ByteArray> {
                val block=ProjectPreviewRenderer.renderPreview(project,size)
                check(block.keys==setOf("type","data","mimeType"))
                check(block.getValue("type").jsonPrimitive.content=="image")
                check(block.getValue("mimeType").jsonPrimitive.content=="image/png")
                val encoded=block.getValue("data").jsonPrimitive.content
                check('\n' !in encoded && '\r' !in encoded)
                val png=Base64.decode(encoded,Base64.NO_WRAP)
                check(png.take(8)==listOf(137,80,78,71,13,10,26,10).map { it.toByte() })
                val bitmap=requireNotNull(BitmapFactory.decodeByteArray(png,0,png.size))
                check(bitmap.width==size && bitmap.height==size)
                return bitmap to png
            }
            for(size in listOf(255,1025)) {
                val error=runCatching { ProjectPreviewRenderer.renderPreview(Project(),size) }.exceptionOrNull()
                check(error is IllegalArgumentException) { "Accepted width $size" }
                results+="REJECT $size"
            }
            for(size in listOf(256,1024)) {
                val (bitmap,_)=decode(Project(),size)
                val pixels=IntArray(size*size);bitmap.getPixels(pixels,0,size,0,0,size,size)
                check(pixels.all { Color.alpha(it)==255 })
                check(bitmap.getPixel(0,0)==Color.WHITE && bitmap.getPixel(size/2,size/2)==Color.WHITE)
                val edge=(size*.05f).roundToInt()
                check(bitmap.getPixel(edge,size/2)!=Color.WHITE) { "Missing left hoop edge at $size" }
                check(bitmap.getPixel(size/2,edge)!=Color.WHITE) { "Missing top hoop edge at $size" }
                bitmap.recycle();results+="PNG $size square/decoded/opaque/white/hoop PASS"
            }
            val (rect,_)=decode(Project(HoopSpec("wide",1000,500)),512)
            check(rect.getPixel(256,26)==Color.WHITE)
            check(rect.getPixel(256,141)!=Color.WHITE)
            check(rect.getPixel(26,256)!=Color.WHITE)
            rect.recycle();results+="RECTANGULAR_HOOP aspect/placement PASS"

            val pattern=EmbPattern(listOf(Stitch(-200,-100,Command.STITCH),Stitch(200,-100,Command.STITCH),
                Stitch(200,-100,Command.COLOR_CHANGE),Stitch(-200,100,Command.JUMP),
                Stitch(-200,100,Command.STITCH),Stitch(200,100,Command.STITCH)),
                listOf(EmbThread(Color.RED),EmbThread(Color.BLUE)))
            val obj=StitchObject("two-colors","Synthetic lines",pattern,Transform(x=200.0,y=100.0))
            val project=Project(objects=listOf(obj))
            val session=EditSession(project)
            val before=session.snapshot();val json=ProjectJson.encode(project)
            val (bitmap,png)=decode(session.snapshot().project,512)
            val x=(256+200*.4608f).roundToInt()
            val red=bitmap.getPixel(x,256);val blue=bitmap.getPixel(x,x)
            check(Color.red(red)==255 && Color.green(red)<255 && Color.blue(red)<255) { "Missing placed red run" }
            check(Color.blue(blue)==255 && Color.red(blue)<255 && Color.green(blue)<255) { "Missing placed blue run" }
            check(bitmap.getPixel(x,(256+100*.4608f).roundToInt())==Color.WHITE) { "Jump was drawn as thread" }
            check(bitmap.getPixel(256,(256-100*.4608f).roundToInt())==Color.WHITE) { "Object was recentered" }
            bitmap.recycle()
            check(before==session.snapshot() && json==ProjectJson.encode(session.project)) { "Render mutated session/project" }
            File(targetContext.cacheDir,"render-preview-synthetic.png").writeBytes(png)
            results+="THREAD_COLORS placement/no-jumps/readOnly PASS"
            val hidden=project.copy(objects=listOf(obj.copy(visible=false)))
            check(ProjectPreviewRenderer.renderPreview(hidden,256)==ProjectPreviewRenderer.renderPreview(Project(),256))
            results+="HIDDEN_OBJECT ignored PASS"
            finish(0,Bundle().apply { putString("result",results.joinToString("\n")+"\nRENDER_PREVIEW_PASS checks=7") })
        } catch(failure:Throwable) {
            finish(1,Bundle().apply { putString("error",results.joinToString("\n")+"\n"+failure.stackTraceToString()) })
        }
    }
}
