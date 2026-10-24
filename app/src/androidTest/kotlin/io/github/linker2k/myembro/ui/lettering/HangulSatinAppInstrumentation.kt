// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import android.app.Instrumentation
import android.os.Bundle
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.io.PesWriter
import java.io.File

/** Explicit actual Android renderer + persisted TextObject replay, no substitute font geometry. */
class HangulSatinAppInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        try {
            val out=File(targetContext.filesDir,"hangul-satin-app").apply { mkdirs() }
            val state=kotlinx.coroutines.runBlocking { automaticLetteringState(targetContext,"noto-sans-kr","정민",15.0) }
            check(state.centerlineSatin && state.style==StitchStyle.SATIN)
            val settings=LetteringSettings("정민","noto-sans-kr",15.0,1.0,LetteringAlignment.CENTER,false,LetteringCurve.STRAIGHT,0.0,stitchStyle=state.style)
            val result=renderLettering(targetContext,settings,EmbThread(0xff000000.toInt(),"Black 40wt"),HoopSpec.H100)
            check(result.textObject.centerlineSatin!=null)
            check(result.pattern.metadata["myembro.lettering.backend"]==HangulSatin.BACKEND)
            val saved=ProjectJson.encode(Project(objects=listOf(result.textObject)))
            File(out,"saved.myembro").writeText(saved)
            val restored=ProjectJson.decode(saved).objects.single() as TextObject
            check(restored.localPattern().stitches==result.textObject.localPattern().stitches)
            check(restored.copy(heightMm=20.0).localPattern().stitchCount>0)
            File(out,"APPSATIN.PES").writeBytes(PesWriter.write(result.pattern))
            File(out,"result.txt").writeText("PASS actual Android renderLettering; automatic SATIN; offline save/load exact; resize20mm; stitches=${result.pattern.stitchCount}; components=${restored.centerlineSatin!!.characters.zip(restored.centerlineSatin!!.components).distinct().size}\n")
            finish(-1,Bundle().apply { putString("result","PASS files/hangul-satin-app") })
        } catch(error:Throwable) { finish(0,Bundle().apply { putString("error",error.stackTraceToString()) }) }
    }
}
