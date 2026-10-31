// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.lifecycle.ViewModelProvider
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.model.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.serialization.json.*

/** Local UI measurement on 5556 only; own display fixture, never exports an unsafe PES. */
class RealisticViewerInstrumentation:Instrumentation() {
    private var count=5000
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);count=arguments?.getString("count")?.toInt()?:5000;start() }
    private fun pattern():EmbPattern {
        require(count in listOf(5000,50000))
        val stitches=ArrayList<Stitch>()
        for(i in 0 until count) {
            val at=i%12500;val row=at/125;val col=at%125
            val x=20+(if(row%2==0)col else 124-col)*6;val y=20+row*7
            if(at==0&&i>0) { val p=stitches.last();stitches+=Stitch(p.x,p.y,Command.TRIM);stitches+=Stitch(x,y,Command.JUMP) }
            stitches+=Stitch(x,y,Command.STITCH)
        }
        val p=stitches.last();stitches+=Stitch(p.x,p.y,Command.END)
        return EmbPattern(stitches,listOf(EmbThread(0xFF205EB5.toInt(),"Synthetic blue")),mapOf("name" to "Owned display benchmark"))
    }
    private fun hash(pattern:EmbPattern):String {
        val md=MessageDigest.getInstance("SHA-256")
        fun add(i:Int) { for(c in 0..3)md.update((i ushr(24-8*c)).toByte()) }
        pattern.stitches.forEach { add(it.x);add(it.y);add(it.command.ordinal) };pattern.threads.forEach { add(it.color) }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    override fun onStart() {
        val sampling=AtomicBoolean(false)
        try {
            val out=File(targetContext.filesDir,"realistic-viewer").apply { mkdirs() }
            val input=pattern();val before=hash(input)
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            lateinit var vm:ViewerViewModel
            runOnMainSync {
                vm=ViewModelProvider(activity)[ViewerViewModel::class.java]
                @Suppress("UNCHECKED_CAST")
                fun set(name:String,value:Any?) {
                    val f=ViewerViewModel::class.java.getDeclaredField(name+"\$delegate").apply { isAccessible=true }
                    (f.get(vm) as MutableState<Any?>).value=value
                }
                set("render",RenderData(input));set("fileName","Synthetic $count")
                vm.seekPlayback(input.stitches.lastIndex/2)
                MainActivity::class.java.getDeclaredMethod("showViewer").apply { isAccessible=true }.invoke(activity)
            }
            fun nodes():List<AccessibilityNodeInfo> {
                fun visit(n:AccessibilityNodeInfo):List<AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).flatMap { n.getChild(it)?.let(::visit).orEmpty() }
                runCatching { uiAutomation.clearCache() }
                return uiAutomation.rootInActiveWindow?.let(::visit).orEmpty()
            }
            val label=targetContext.getString(R.string.viewer_realistic)
            fun ready()=nodes().any { it.viewIdResourceName?.endsWith("viewer_realistic_image")==true }
            fun click() {
                val end=SystemClock.uptimeMillis()+10000
                while(SystemClock.uptimeMillis()<end) {
                    val n=nodes().firstOrNull { it.text?.toString()==label }
                    val parent=n?.let { generateSequence(it) { p->p.parent }.firstOrNull { p->p.isClickable } }
                    if(parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)return
                    Thread.sleep(50)
                }
                error("Realistic toggle unavailable")
            }
            fun awaitCondition(condition:()->Boolean) {
                val end=SystemClock.uptimeMillis()+10000
                while(!condition()) { check(SystemClock.uptimeMillis()<end) { "UI state did not settle" };Thread.sleep(30) }
            }
            waitForIdleSync();Thread.sleep(500)
            check(!ready()) { "Realistic should start OFF" }
            val baseline=Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val peakHeap=AtomicLong();val peakPss=AtomicLong()
            sampling.set(true)
            val sampler=thread(isDaemon=true,name="viewer-memory-probe") {
                while(sampling.get()) {
                    val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
                    peakPss.accumulateAndGet(info.totalPss.toLong(),::maxOf)
                    val rt=Runtime.getRuntime();peakHeap.accumulateAndGet(rt.totalMemory()-rt.freeMemory(),::maxOf)
                    Thread.sleep(50)
                }
            }
            val start=SystemClock.elapsedRealtimeNanos();click()
            val deadline=SystemClock.uptimeMillis()+120000
            while(!ready()) { check(SystemClock.uptimeMillis()<deadline) { "Preview timeout" };Thread.sleep(50) }
            waitForIdleSync()
            val elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
            sampling.set(false);sampler.join()
            fun shot(name:String) {
                waitForIdleSync();Thread.sleep(150)
                val bitmap=checkNotNull(uiAutomation.takeScreenshot())
                File(out,name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
            }
            shot("realistic-$count.png")
            val canvas=nodes().first { it.viewIdResourceName?.endsWith("viewer_realistic_image")==true }
            val rect=android.graphics.Rect();canvas.getBoundsInScreen(rect)
            val now=SystemClock.uptimeMillis()
            for((i,action) in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).withIndex()) {
                val e=MotionEvent.obtain(now,now+i*30,action,rect.centerX()+i*25f,rect.centerY()+i*15f,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
                check(uiAutomation.injectInputEvent(e,true));e.recycle()
            }
            waitForIdleSync();check(ready());shot("realistic-$count-pan.png")
            // Two-finger pinch: the completed bitmap is transformed, never regenerated for zoom.
            val props=Array(2) { index->MotionEvent.PointerProperties().apply { id=index;toolType=MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates=Array(2) { MotionEvent.PointerCoords().apply { pressure=1f;size=1f } }
            val pinchStart=SystemClock.uptimeMillis()
            fun pinchEvent(action:Int,pointers:Int,distance:Float) {
                coordinates[0].x=rect.centerX()-distance;coordinates[1].x=rect.centerX()+distance
                coordinates.forEach { it.y=rect.centerY().toFloat() }
                val e=MotionEvent.obtain(pinchStart,SystemClock.uptimeMillis(),action,pointers,props,coordinates,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                check(uiAutomation.injectInputEvent(e,true));e.recycle();Thread.sleep(30)
            }
            pinchEvent(MotionEvent.ACTION_DOWN,1,80f)
            pinchEvent(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,80f)
            for(distance in listOf(100f,130f,170f))pinchEvent(MotionEvent.ACTION_MOVE,2,distance)
            pinchEvent(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,170f)
            pinchEvent(MotionEvent.ACTION_UP,1,170f)
            waitForIdleSync();check(ready());shot("realistic-$count-zoom.png")
            click();awaitCondition { !ready() };waitForIdleSync()
            // Start then cancel, checking that no stale completion repaints the old image.
            click();awaitCondition { ready()||nodes().any { it.viewIdResourceName?.endsWith("viewer_realistic_loading")==true } }
            val loadingObserved=nodes().any { it.viewIdResourceName?.endsWith("viewer_realistic_loading")==true }
            if(count==50000&&loadingObserved)shot("realistic-50000-loading.png")
            click();awaitCondition { !ready() };Thread.sleep(300);waitForIdleSync();check(!ready())
            // Document replacement resets OFF and cancels an in-flight old-color render.
            val replacement=EmbPattern(input.stitches,listOf(EmbThread(0xFFB03030.toInt(),"Synthetic red")))
            fun replace(pattern:EmbPattern) { runOnMainSync {
                val f=ViewerViewModel::class.java.getDeclaredField("render\$delegate").apply { isAccessible=true }
                @Suppress("UNCHECKED_CAST") val state=f.get(vm) as MutableState<RenderData?>
                state.value=RenderData(pattern)
            } }
            replace(replacement);waitForIdleSync();awaitCondition { !ready() }
            click();awaitCondition { ready()||nodes().any { it.viewIdResourceName?.endsWith("viewer_realistic_loading")==true } }
            replace(input);awaitCondition { !ready() };Thread.sleep(300);check(!ready())
            check(before==hash(input)) { "Pattern mutated" }
            // A normal bundled PES uses the real ViewModel load path; preserve its original bytes.
            runOnMainSync { vm.openSample() }
            awaitCondition { !vm.loading&&vm.render!=null&&vm.fileName=="flower.pes" }
            waitForIdleSync();awaitCondition { !ready() }
            awaitCondition { !vm.checkingSewout }
            val safetyBefore=vm.sewoutReport?.hasBlockingIssues
            val sourceField=ViewerViewModel::class.java.getDeclaredField("sourceBytes").apply { isAccessible=true }
            val sourceBefore=(sourceField.get(vm) as ByteArray).copyOf()
            click();awaitCondition { ready() };shot("realistic-bundled-flower.png")
            check(safetyBefore==vm.sewoutReport?.hasBlockingIssues) { "Safety result changed" }
            check(sourceBefore.contentEquals(sourceField.get(vm) as ByteArray)) { "PES source bytes mutated" }
            val pesSha=MessageDigest.getInstance("SHA-256").digest(sourceBefore).joinToString("") { "%02x".format(it.toInt() and 255) }
            val report=buildJsonObject {
                put("canvasLeft",rect.left);put("canvasTop",rect.top);put("canvasWidth",rect.width());put("canvasHeight",rect.height())
                put("loadingObserved",loadingObserved);safetyBefore?.let { put("bundledSafetyBlockedBeforeAfter",it) }
                put("stitches",count);put("firstImageMs",elapsed);put("sampledPeakHeapBytes",peakHeap.get())
                put("baselinePssKiB",baseline.totalPss);put("sampledPeakPssKiB",peakPss.get());put("memorySamplePeriodMs",50)
                put("bundledPesSha256BeforeAfter",pesSha);put("patternSha256Before",before);put("patternSha256After",hash(input))
                put("defaultOff",true);put("panImageRetained",true);put("zoomImageRetained",true);put("toggleOffCancelled",true);put("replacementCancels",true)
                put("renderer","actual ViewerScreen/StitchCanvas, API26 Canvas backend, zero retained tile-cache budget")
            }
            File(out,"viewer-$count.json").writeText(report.toString()+"\n")
            finish(0,Bundle().apply { putString("result","REALISTIC_VIEWER_PASS $report") })
        } catch(failure:Throwable) {
            sampling.set(false)
            finish(1,Bundle().apply { putString("error",failure.stackTraceToString()) })
        }
    }
}
