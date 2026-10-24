// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.engine.model.EmbThread
import java.io.File

/** Actual renderer/export + actual two-chip screen; public pinned SVG fonts must be installed. */
class SingleLineWeightInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            val out=File(targetContext.filesDir,"single-line-weight").apply{mkdirs()}
            val thread=EmbThread(0xff000000.toInt(),"Black 40wt")
            val summary=StringBuilder("font\theight\tstyle\tstitches\n")
            lateinit var initial: TextObject
            for((id,height) in listOf("hershey-script-1" to 10.0,"hershey-script-1" to 15.0,"ems-readability" to 10.0)) {
                val state=kotlinx.coroutines.runBlocking{automaticLetteringState(targetContext,id,"Yeonwoo",height)}
                check(state.singleLineWeightEnabled)
                for(bold in listOf(false,true)) {
                    val style=letteringStyleForWeight(state,bold)
                    val settings=LetteringSettings("Yeonwoo",id,height,0.0,LetteringAlignment.CENTER,false,LetteringCurve.STRAIGHT,0.0,
                        stitchStyle=style)
                    val result=renderLettering(targetContext,settings,thread,HoopSpec.H100)
                    check(!result.unsupported && !result.outsideHoop)
                    check(result.textObject.stitchStyle==style)
                    val restored=ProjectJson.decode(ProjectJson.encode(Project(objects=listOf(result.textObject)))).objects.single() as TextObject
                    check(restored.stitchStyle==style && restored.localPattern().stitches==result.textObject.localPattern().stitches)
                    File(out,"WT-${id}-${height.toInt()}-${style}.PES").writeBytes(PesWriter.write(result.pattern))
                    summary.append("$id\t$height\t$style\t${result.pattern.stitchCount}\n")
                    if(id=="hershey-script-1" && height==10.0 && !bold)initial=result.textObject
                }
            }
            val service=uiAutomation.serviceInfo
            service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            uiAutomation.serviceInfo=service
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            runOnMainSync { activity.setContent {
                LetteringScreen(onBack={},onAdd={},onOpenFonts={},initialObject=initial,hoop=HoopSpec.H100)
            } }
            Thread.sleep(1800)
            uiAutomation.executeShellCommand("input swipe 540 1900 540 1250 350").close()
            Thread.sleep(700)
            fun screenshot(name:String) { val image=uiAutomation.takeScreenshot();File(out,name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle() }
            screenshot("normal.png")
            val boldText=targetContext.getString(R.string.lettering_weight_bold)
            fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
                if(node==null)return null
                if(node.text?.toString()==label)return node
                for(i in 0 until node.childCount)find(node.getChild(i),label)?.let{return it}
                return null
            }
            val node=find(uiAutomation.rootInActiveWindow,boldText) ?: error("Bold chip not present: $boldText")
            var button:AccessibilityNodeInfo?=node
            while(button!=null && !button.isClickable)button=button.parent
            check(button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
            Thread.sleep(1500)
            screenshot("bold.png")
            // Thickness-only change must be considered an edit by BackHandler.
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(400)
            check(find(uiAutomation.rootInActiveWindow,targetContext.getString(R.string.lettering_discard_title))!=null)
            screenshot("discard.png")
            summary.append("PASS actual chip click; thickness-only discard confirmation; save/load stitches exact\n")
            File(out,"result.tsv").writeText(summary.toString())
            finish(-1,Bundle().apply{putString("result","PASS files/single-line-weight")})
        } catch(e:Throwable) { finish(0,Bundle().apply{putString("error",e.stackTraceToString())}) }
    }
}
