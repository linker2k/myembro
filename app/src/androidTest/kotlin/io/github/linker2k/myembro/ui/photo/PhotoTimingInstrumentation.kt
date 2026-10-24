// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.app.Instrumentation
import android.os.Bundle
import android.graphics.*
import io.github.linker2k.myembro.engine.inkstitch.svg.image.RedworkPipeline
import io.github.linker2k.myembro.engine.model.EmbThread
import java.io.File

/** Opt-in real ART timing; generates its own 12MP image, never reads user media. */
class PhotoTimingInstrumentation : Instrumentation() {
    private var privateInput=false
    private var onlyPreview=false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); privateInput=arguments?.getString("input")?.startsWith("local-private")==true; onlyPreview=arguments?.getString("input")=="local-private-preview"; start() }
    override fun onStart() {
        try {
            val out=File(targetContext.filesDir,"photo-timing").apply { mkdirs() }
            val file=if(privateInput) File(targetContext.filesDir,"private-timing-input.jpg") else File(out,"synthetic-12mp.jpg")
            if (!privateInput) {
            val image=Bitmap.createBitmap(4000,3000,Bitmap.Config.ARGB_8888)
            val row=IntArray(4000)
            for(y in 0 until 3000) {
                for(x in row.indices) {
                    val v=when {
                        y<900 -> 220+(x*13+y*7)%30
                        x in 1200..2700 && y in 1300..2600 -> if((x/85+y/75)%2==0) 35 else 115
                        (x-700L)*(x-700)+(y-1600L)*(y-1600)<450L*450 -> 35+(x*17+y*23)%85
                        else -> 140+(x*7+y*11)%100
                    }
                    row[x]=Color.rgb(v,v,v)
                }
                image.setPixels(row,0,4000,0,y,4000,1)
            }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG,90,it) }; image.recycle()
            }
            if (onlyPreview) {
                lateinit var vm: PhotoViewModel
                runOnMainSync {
                    vm=PhotoViewModel(targetContext.applicationContext as android.app.Application)
                    vm.load(android.net.Uri.fromFile(file))
                }
                val loadStart=System.nanoTime()
                while(vm.busy && (System.nanoTime()-loadStart)<5_000_000_000L) Thread.sleep(10)
                check(vm.source!=null && vm.error==null)
                val previewStart=System.nanoTime()
                runOnMainSync { vm.next() }
                while(vm.busy && (System.nanoTime()-previewStart)<2_000_000_000L) Thread.sleep(10)
                val elapsed=(System.nanoTime()-previewStart)/1e6
                check(!vm.busy && vm.outline!=null && vm.render==null && vm.report==null) { "Preview missing/late: $elapsed ms" }
                runOnMainSync { vm.cancel() }
                finish(-1,Bundle().apply { putString("result","VM_PREVIEW_UNDER_2S ms=$elapsed generated_stitches=false") })
                return
            }
            val log=StringBuilder()
            for(filtered in listOf(false,true)) {
                val start=System.nanoTime()
                val dimensions=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(file.path,dimensions)
                val decodeOptions=BitmapFactory.Options().apply {
                    inSampleSize=1
                    while(maxOf(dimensions.outWidth,dimensions.outHeight)/inSampleSize>1536) inSampleSize*=2
                }
                val decoded=BitmapFactory.decodeFile(file.path,decodeOptions)
                log.append("filtered=$filtered decode_ms=${(System.nanoTime()-start)/1e6} decoded=${decoded.width}x${decoded.height}\n")
                val scaled=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
                val side=minOf(decoded.width,decoded.height);val left=(decoded.width-side)/2;val top=(decoded.height-side)/2
                Canvas(scaled).drawBitmap(decoded,Rect(left,top,left+side,top+side),Rect(0,0,192,192),Paint(Paint.FILTER_BITMAP_FLAG))
                val rgb=IntArray(192*192);scaled.getPixels(rgb,0,192,0,0,192,192)
                rgb.indices.forEach { rgb[it]=rgb[it] and 0xFFFFFF }
                var stage="start";var tick=System.nanoTime()
                val progress:(String)->Unit={ next -> if(next!=stage) {
                    log.append("$stage ms=${(System.nanoTime()-tick)/1e6}\n");stage=next;tick=System.nanoTime()
                } }
                val thread=EmbThread(0xFF000000.toInt())
                val simplified=if(filtered) io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoRasterFilters.median3(192,192,rgb) else rgb
                val outline=RedworkPipeline.trace(192,192,simplified,99.9,0,thread,if(filtered) .5 else 0.0,128,checkpoint=progress)
                log.append("contours=${outline.contourCount}\n")
                try {
                    val result=RedworkPipeline.generate(outline,thread,photoMode=filtered,checkpoint=progress)
                    log.append("$stage ms=${(System.nanoTime()-tick)/1e6} stitches=${result.pattern.stitchCount}\n")
                } catch (failure:IllegalArgumentException) {
                    log.append("generation_failed=${failure.message?.substringBefore(":")}\n")
                }
                decoded.recycle();scaled.recycle()
            }
            File(out,"timing.txt").writeText(log.toString())
            finish(-1,Bundle().apply { putString("result",log.toString()) })
        } catch (error: Throwable) { finish(0,Bundle().apply { putString("error",error.stackTraceToString()) }) }
    }
}
