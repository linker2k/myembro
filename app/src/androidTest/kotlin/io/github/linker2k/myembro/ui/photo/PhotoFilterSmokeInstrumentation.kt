// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoCorrection
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import java.io.File

/** Opt-in 5556 smoke; reads repository launcher artwork from the independent test fixture only. */
class PhotoFilterSmokeInstrumentation: Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        try {
            val out=File(targetContext.filesDir,"filter-shots").apply { mkdirs() }
            val input=File(out,"repository-launcher.png")
            context.assets.open("photo-filters/launcher-artwork-192/input.png").use { from -> input.outputStream().use { from.copyTo(it) } }
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var vm:PhotoViewModel
            runOnMainSync {
                vm=PhotoViewModel(targetContext.applicationContext as Application)
                activity.setContent { MyembroTheme { PhotoScreen(vm,EmbroiderySettings(),{}, {}, {}) } }
                vm.load(Uri.fromFile(input))
            }
            fun ready() {
                val end=System.currentTimeMillis()+30_000
                while(System.currentTimeMillis()<end) {
                    var done=false;runOnMainSync { done=!vm.busy }
                    if(done) { waitForIdleSync();Thread.sleep(300);return }
                    Thread.sleep(50)
                }
                error("Timed out")
            }
            ready();check(vm.source!=null);check(vm.correction==PhotoCorrection.ORIGINAL)
            runOnMainSync { vm.next() };ready()
            for(filter in PhotoCorrection.entries) {
                runOnMainSync { vm.changeCorrection(filter) };ready()
                check(vm.correction==filter && vm.separatedPreview!=null && vm.error==null && vm.outline!=null && vm.render==null && vm.report==null) { "$filter preview missing: ${vm.error}" }
                out.resolve("${filter.name.lowercase()}.png").outputStream().use { uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            runOnMainSync { vm.changeStyle(PhotoMode.COLOR) };ready()
            check(vm.correction==PhotoCorrection.ORIGINAL)
            finish(0,Bundle().apply { putString("result","PHOTO_FILTER_UI_PASS screenshots=4 default=ORIGINAL no_stitches=true color_resets_otsu=true") })
        } catch(failure:Throwable) { finish(1,Bundle().apply { putString("error",failure.stackTraceToString()) }) }
    }
}
