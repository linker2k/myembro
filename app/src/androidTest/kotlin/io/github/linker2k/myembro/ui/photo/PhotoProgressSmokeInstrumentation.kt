// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Large synthetic input, controlled blocked worker and monotonic clock for exact 60s UI checks. */
class PhotoProgressSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            val out=File(targetContext.filesDir,"photo-progress-smoke").apply { mkdirs() }
            val image=Bitmap.createBitmap(2048,1024,Bitmap.Config.ARGB_8888)
            Canvas(image).apply {
                drawColor(Color.WHITE)
                val paint=Paint().apply { color=Color.RED }
                drawCircle(512f,512f,300f,paint)
                paint.color=Color.BLUE;drawRect(1150f,212f,1750f,812f,paint)
            }
            val input=File(out,"large-synthetic.png")
            input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            val entered=CountDownLatch(1);val interrupted=AtomicBoolean()
            val clock=AtomicLong(1_000L)
            val generator=ThreadTextureGenerator { _,_,_,_,_ ->
                entered.countDown()
                try { Thread.sleep(Long.MAX_VALUE);error("unreachable") }
                catch(failure:InterruptedException) { interrupted.set(true);throw failure }
            }
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var vm:PhotoViewModel
            fun content() { activity.setContent { MyembroTheme { PhotoScreen(vm,EmbroiderySettings(),{}, {}, {}) } } }
            runOnMainSync {
                vm=PhotoViewModel(targetContext.applicationContext as Application,generator)
                vm.generationClock=clock::get
                content();vm.load(Uri.fromFile(input))
            }
            fun await(label:String, condition:()->Boolean) {
                val end=SystemClock.uptimeMillis()+60_000
                while(SystemClock.uptimeMillis()<end) {
                    var ready=false;runOnMainSync { ready=condition() }
                    if(ready) { waitForIdleSync();return };SystemClock.sleep(50)
                };error("Timed out: $label")
            }
            fun shot(name:String) {
                waitForIdleSync();val frame=CountDownLatch(1)
                runOnMainSync { activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { frame.countDown() } } }
                check(frame.await(10,TimeUnit.SECONDS))
                out.resolve(name).outputStream().use { uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            await("load") { !vm.busy };runOnMainSync { vm.transform(.1f,0f,0f);vm.next() }
            await("preview") { !vm.busy };runOnMainSync { vm.changeStyle(PhotoMode.COLOR) }
            await("color") { !vm.busy };runOnMainSync { vm.changeTextureEnabled(true) }
            await("texture preview") { !vm.busy };check(vm.previewReady)
            runOnMainSync { vm.next() };check(entered.await(20,TimeUnit.SECONDS))
            clock.set(60_999);await("59.999") { vm.generationElapsedMs==59_999L };check(!vm.generationNeedsDecision)
            clock.set(61_000);await("60 seconds") { vm.generationNeedsDecision };shot("01-wait-decision.png")
            runOnMainSync { vm.extendGeneration() };check(!vm.generationNeedsDecision)
            // Dispose/recompose the screen: ViewModel elapsed time and allowance must survive.
            runOnMainSync { content() }
            check(!vm.generationNeedsDecision && vm.generationElapsedMs==60_000L)
            shot("02-extended.png")
            clock.set(121_000);await("second decision") { vm.generationNeedsDecision }
            runOnMainSync { vm.cancel() }
            await("interrupted") { interrupted.get() && !vm.busy }
            check(vm.render==null)
            finish(0,Bundle().apply { putString("result","PHOTO_PROGRESS_PASS 59999=no_prompt 60000=prompt extended=120000 cancel=interrupted screen_recompose=preserved large_input=2048x1024 controlled_clock=true") })
        } catch(failure:Throwable) { finish(1,Bundle().apply { putString("error",failure.stackTraceToString()) }) }
    }
}
