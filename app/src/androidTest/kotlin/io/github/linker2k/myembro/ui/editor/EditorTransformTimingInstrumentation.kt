// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.app.Instrumentation
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.ui.RenderData
import io.github.linker2k.myembro.ui.lettering.*
import kotlinx.coroutines.runBlocking
import java.io.File

/** Opt-in actual Android TTF and Canvas timing, no private input. */
class EditorTransformTimingInstrumentation : Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        try {
            val generated=runBlocking {
                val state=automaticLetteringState(targetContext,"noto-sans-kr","가",20.0)
                renderLettering(targetContext,LetteringSettings("가","noto-sans-kr",20.0,0.0,
                    LetteringAlignment.CENTER,false,LetteringCurve.STRAIGHT,0.0,stitchStyle=state.style),EmbThread(0xFFB5492A.toInt()),HoopSpec.H100)
            }
            check(!generated.unsupported)
            val text=generated.textObject
            val local=text.localPattern()
            val base=RenderData(text.placedPattern())
            val bitmap=Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);val paint=Paint()
            val before=mutableListOf<Double>();val after=mutableListOf<Double>()
            repeat(4) { i ->
                val t=text.transform.copy(x=i*.37,y=i*.2,rotationDeg=i*.7,scaleX=1+i*.01,scaleY=1+i*.01)
                var start=System.nanoTime()
                val rebuilt=text.copy(transform=t)
                val data=RenderData(rebuilt.placedPattern())
                canvas.drawLines(data.stitchLines,paint)
                before+=(System.nanoTime()-start)/1e6
                start=System.nanoTime()
                val current=text.withTransform(t)
                check(current.localPattern()===local)
                current.previewBounds()
                canvas.save();canvas.translate(t.x.toFloat(),t.y.toFloat());canvas.rotate(t.rotationDeg.toFloat())
                canvas.scale(t.scaleX.toFloat(),t.scaleY.toFloat());canvas.drawLines(base.stitchLines,paint);canvas.restore()
                after+=(System.nanoTime()-start)/1e6
            }
            val out=File(targetContext.filesDir,"editor-transform-timing.txt")
            val result="TEXT_TRANSFORM_ANDROID text=public_Noto_Sans_KR_glyph stitches=${local.stitchCount} before_mean_ms=${before.average()} before_max_ms=${before.max()} after_mean_ms=${after.average()} after_max_ms=${after.max()} frames=4"
            out.writeText(result)
            finish(-1,Bundle().apply { putString("result",result) })
        } catch(error:Throwable) { finish(0,Bundle().apply { putString("error",error.stackTraceToString()) }) }
    }
}
