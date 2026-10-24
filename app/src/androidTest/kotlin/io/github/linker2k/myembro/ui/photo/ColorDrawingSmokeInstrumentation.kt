// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import io.github.linker2k.myembro.engine.io.PesWriter
import java.io.File

/** Opt-in actual screen smoke. Creates its own image; never opens MediaStore or user files. */
class ColorDrawingSmokeInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        try {
            val out=File(targetContext.filesDir,"color-smoke").apply { mkdirs() }
            val image=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(image);val paint=Paint()
            paint.color=0xfff6c541.toInt();canvas.drawCircle(64f,64f,51f,paint)
            paint.color=0xff252525.toInt();canvas.drawRect(37f,38f,44f,54f,paint);canvas.drawRect(84f,38f,91f,54f,paint)
            paint.color=0xffb34d43.toInt();canvas.drawRect(48f,83f,80f,86f,paint)
            val input=File(out,"original-synthetic-face.png")
            input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var vm:PhotoViewModel
            runOnMainSync {
                vm=PhotoViewModel(targetContext.applicationContext as Application)
                activity.setContent { MyembroTheme { PhotoScreen(vm,EmbroiderySettings(),{}, {}, {}) } }
                vm.load(Uri.fromFile(input))
            }
            fun ready() {
                val until=System.currentTimeMillis()+40_000
                while(System.currentTimeMillis()<until) {
                    var done=false;runOnMainSync { done=!vm.busy }
                    if(done) { waitForIdleSync();return };Thread.sleep(80)
                }
                error("Timed out")
            }
            fun screenshot(name:String) {
                waitForIdleSync();Thread.sleep(400)
                out.resolve(name).outputStream().use { uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            ready();check(vm.source!=null)
            runOnMainSync { vm.next() };ready();check(vm.outline!=null) { "initial ${vm.error}" }
            runOnMainSync { vm.changeStyle(PhotoMode.COLOR) };ready();check(vm.colorPreview!=null) { "color ${vm.error}" }
            runOnMainSync { vm.changeColorCount(3) };ready();check(vm.colorCount==3)
            runOnMainSync { vm.changeSize(30.0) };ready();check(kotlin.math.abs(requireNotNull(vm.inkWidthMm)-30.0)<=2*vm.widthMm/(vm.colorPreview!!.width-2)) { "ink=${vm.inkWidthMm} frame=${vm.widthMm} error=${vm.error}" }
            screenshot("color-preview-ko.png")
            runOnMainSync { vm.next() };ready()
            check(vm.render!=null) { "generate ${vm.error}" }
            check(vm.passed) { vm.report?.findings.toString() }
            check(vm.requiredThreads.size in 2..3 && vm.requiredThreads.all { !it.catalogNumber.isNullOrBlank() && !it.description.isNullOrBlank() })
            check(vm.requiredThreads.none { it.catalogNumber=="47" }) { "Antialias Beige must not become a thread" }
            screenshot("color-result-ko.png")
            out.resolve("color-face.pes").writeBytes(PesWriter.write(vm.render!!.pattern,EmbroiderySettings().writeSettings))
            val summary="COLOR_UI_SMOKE_PASS colors=${vm.colorCount} ink_width_mm=${vm.inkWidthMm} stitches=${vm.report!!.statistics.stitchCount} threads=${vm.requiredThreads.map { it.catalogNumber to it.description }}"
            out.resolve("result.txt").writeText(summary)
            finish(0,Bundle().apply { putString("result",summary) })
        } catch(failure:Throwable) { finish(1,Bundle().apply { putString("error",failure.stackTraceToString()) }) }
    }
}
