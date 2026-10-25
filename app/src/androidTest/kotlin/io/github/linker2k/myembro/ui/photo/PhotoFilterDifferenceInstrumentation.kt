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
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.inkstitch.svg.image.*
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import java.io.File
import java.security.MessageDigest

/** Own opaque low-contrast gradient only. No production algorithm/settings changes to force differences. */
class PhotoFilterDifferenceInstrumentation: Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun rgbBytes(pixels:IntArray)=ByteArray(pixels.size*3) { (pixels[it/3] ushr (16-it%3*8)).toByte() }
    override fun onStart() {
        try {
            val out=File(targetContext.filesDir,"filter-difference").apply { mkdirs() }
            val pixels=IntArray(128*128) { i ->
                val x=i%128;val y=i/128
                // Marker is an existing bright background color, for the current top-left background contract.
                if(x<4 && y<4) 0xff988c88.toInt()
                else 0xff000000.toInt() or ((128+x*24/127) shl 16) or
                    ((128+y*12/127) shl 8) or (128+(x+y)*8/254)
            }
            val bitmap=Bitmap.createBitmap(pixels,128,128,Bitmap.Config.ARGB_8888)
            val input=File(out,"synthetic-low-contrast-gradient.png")
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var vm:PhotoViewModel
            val settings=EmbroiderySettings()
            runOnMainSync {
                vm=PhotoViewModel(targetContext.applicationContext as Application)
                activity.setContent { MyembroTheme { PhotoScreen(vm,settings,{}, {}, {}) } }
                vm.load(Uri.fromFile(input))
            }
            fun ready() {
                val end=System.currentTimeMillis()+40_000
                while(System.currentTimeMillis()<end) {
                    var done=false;runOnMainSync { done=!vm.busy }
                    if(done) { waitForIdleSync();Thread.sleep(250);return }
                    Thread.sleep(50)
                }
                error("Timed out step ${vm.step}")
            }
            ready();check(vm.source!=null)
            runOnMainSync { vm.changeDetail(0) };ready()
            runOnMainSync { vm.changeStyle(PhotoMode.OUTLINE) };ready()
            runOnMainSync { vm.changeSize(30.0) };ready()
            val frameWidth=vm.widthMm
            val raw=IntArray(128*128);vm.source!!.getPixels(raw,0,128,0,0,128,128)
            check(raw.contentEquals(pixels)) { "Decoded input differs" }
            val rasterHashes=mutableListOf<String>();val maskHashes=mutableListOf<String>();val pesHashes=mutableListOf<String>()
            val log=StringBuilder("INPUT_RGB_RANGES R=128..152 G=128..140 B=128..136\n")
            val bytesField=PhotoViewModel::class.java.getDeclaredField("bytes").apply { isAccessible=true }
            for(filter in listOf(PhotoCorrection.ORIGINAL,PhotoCorrection.CONTRAST,PhotoCorrection.OTSU)) {
                runOnMainSync { if(vm.step==2) vm.back();vm.changeCorrection(filter) };ready()
                check(vm.widthMm==frameWidth) { "Physical frame changed between corrections" }
                check(vm.error==null && vm.outline!=null && vm.render==null && vm.report==null) { "$filter preview error ${vm.error}" }
                val actualPreview=requireNotNull(vm.separatedPreview)
                check(actualPreview.width==128 && actualPreview.height==128)
                val actual=IntArray(128*128);actualPreview.getPixels(actual,0,128,0,0,128,128)
                actual.indices.forEach { actual[it]=actual[it] and 0xffffff }
                val corrected=PhotoCorrections.apply(128,128,raw,true,filter)
                var expected=IntArray(0)
                val thread=EmbThread(0xffb5492a.toInt(),targetContext.getString(R.string.photo_name))
                val expectedOutline=if(corrected.mask!=null) RedworkPipeline.traceMask(128,128,corrected.mask!!,vm.widthMm,thread,
                    photoMinimumArea(0),128,onQuantized={expected=it})
                    else RedworkPipeline.trace(128,128,corrected.pixels,vm.widthMm,0,thread,
                        photoMinimumArea(0),128,onQuantized={expected=it})
                check(actual.contentEquals(expected)) { "$filter UI mask != engine filter result" }
                check(vm.outline!!.outlineSvg==expectedOutline.outlineSvg) { "$filter stale/wrong contours" }
                val rasterHash=sha(rgbBytes(corrected.pixels));val maskHash=sha(rgbBytes(actual))
                rasterHashes+=rasterHash;maskHashes+=maskHash
                out.resolve("${filter.name.lowercase()}-preview.png").outputStream().use { uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
                val tick=System.nanoTime()
                runOnMainSync { vm.next() };ready()
                check(vm.error==null && vm.render!=null && vm.report?.analysisComplete==true) { "$filter result error ${vm.error}" }
                lateinit var pes:ByteArray
                runOnMainSync { pes=(bytesField.get(vm) as ByteArray).copyOf() }
                check(pes.contentEquals(PesWriter.write(vm.render!!.pattern,settings.writeSettings)))
                val pesHash=sha(pes);pesHashes+=pesHash
                out.resolve("${filter.name.lowercase()}.pes").writeBytes(pes)
                check(!vm.report!!.hasBlockingIssues) { "$filter sewing blockers: ${vm.report!!.findings}" }
                val line="$filter frame_width_mm=$frameWidth ink=${actual.count { it==0 }} contours=${vm.outline!!.contourCount} stitches=${vm.render!!.pattern.stitchCount} blockers=${vm.report!!.findings.count { it.severity==io.github.linker2k.myembro.engine.sewout.Severity.BLOCKER }} generation_ms=${(System.nanoTime()-tick)/1e6} raster=$rasterHash mask=$maskHash pes=$pesHash"
                log.append(line).append('\n');out.resolve("result.txt").writeText(log.toString())
            }
            check(rasterHashes.toSet().size==3) { "Filtered rasters are identical" }
            check(maskHashes.toSet().size==3) { "App separation rasters are identical" }
            check(pesHashes.toSet().size==3) { "Cached app PES files are identical" }
            log.append("PHOTO_FILTER_DIFFERENCE_PASS rasters=3 masks=3 pes=3 ui_engine_exact=true screenshots=3\n")
            out.resolve("result.txt").writeText(log.toString())
            finish(0,Bundle().apply { putString("result",log.toString()) })
        } catch(failure:Throwable) { finish(1,Bundle().apply { putString("error",failure.stackTraceToString()) }) }
    }
}
