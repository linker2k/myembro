// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.app.Instrumentation
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.ViewerViewModel
import java.io.File

/** 5558 only. Own synthetic Downloads SVG + repository robot/PES fixture; no personal files. */
class PictureIntakeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val log = mutableListOf<String>()
        var download: android.net.Uri? = null
        var imported: File? = null
        try {
            val directory = File(targetContext.cacheDir, "pes_exports/picture-intake").apply { mkdirs() }
            val out = File(targetContext.filesDir, "picture-intake-smoke").apply { mkdirs() }
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="40" height="40" fill="#ffcc00"/><rect x="60" y="60" width="30" height="30" fill="#000000"/></svg>"""
            val file = File(directory,"download.svg").apply { writeText(svg) }
            fun uri(file: File) = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", file)
            fun request(file: File, action: String, mime: String): Intent {
                val source = uri(file)
                return Intent(action).setClass(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                        if (action == Intent.ACTION_VIEW) setDataAndType(source,mime)
                        else {
                            type = mime
                            if (action == Intent.ACTION_SEND_MULTIPLE) putParcelableArrayListExtra(Intent.EXTRA_STREAM,arrayListOf(source))
                            else putExtra(Intent.EXTRA_STREAM,source)
                            clipData = ClipData.newRawUri("synthetic",source)
                        }
                    }
            }
            val activity = startActivitySync(request(file,Intent.ACTION_SEND,"text/xml").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            fun <T> model(method: String): T = MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible=true }.invoke(activity) as T
            lateinit var vm: PhotoViewModel
            runOnMainSync { vm=model("getPhoto") }
            fun await(label: String, condition: () -> Boolean) {
                val end=SystemClock.uptimeMillis()+30_000
                while(!condition()) { check(SystemClock.uptimeMillis()<end) { "$label timed out; error=${vm.error} busy=${vm.busy} svg=${vm.isSvg} step=${vm.step} source=${vm.source!=null} report=${vm.report!=null}" }; Thread.sleep(100) }
                waitForIdleSync()
            }
            fun nodes(): List<AccessibilityNodeInfo> {
                fun all(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).flatMap { n.getChild(it)?.let(::all).orEmpty() }
                return uiAutomation.rootInActiveWindow?.let(::all).orEmpty()
            }
            fun find(vararg labels: String) = nodes().firstOrNull { it.text?.toString() in labels || it.contentDescription?.toString() in labels }
            fun click(vararg labels: String) {
                await(labels.joinToString()) { find(*labels)!=null }
                var node=checkNotNull(find(*labels));while(!node.isClickable) node=checkNotNull(node.parent)
                check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Disabled ${labels.toList()}" };waitForIdleSync()
            }
            fun shot(name: String) {
                waitForIdleSync();uiAutomation.waitForIdle(250,5000);SystemClock.sleep(700)
                val image=checkNotNull(uiAutomation.takeScreenshot())
                File(out,name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
            }
            await("SEND SVG text/xml") { vm.isSvg && !vm.busy && vm.passed }
            log+="ROUTE PASS: SEND text/xml + SVG -> photo vector result, sewout PASS"
            for ((action,mime) in listOf(Intent.ACTION_SEND to "application/octet-stream",Intent.ACTION_SEND_MULTIPLE to "text/plain",Intent.ACTION_VIEW to "application/xml")) {
                runOnMainSync { vm.begin();activity.startActivity(request(file,action,mime)) }
                await("$action $mime SVG") { vm.isSvg && !vm.busy && vm.passed }
                log+="ROUTE PASS: $action $mime + SVG -> photo vector result, sewout PASS"
            }
            val robot=File(directory,"opaque.bin")
            context.assets.open("image-to-design/robot.png").use { input -> robot.outputStream().use { input.copyTo(it) } }
            runOnMainSync { vm.begin();activity.startActivity(request(robot,Intent.ACTION_SEND,"application/octet-stream")) }
            await("PNG octet-stream") { vm.source!=null && !vm.busy }
            check(!vm.isSvg && vm.error==null)
            log+="ROUTE PASS: SEND application/octet-stream + PNG bytes (.bin) -> photo choose/crop"

            // MediaStore enforces MIME suffixes: test a real .svg and an XML-typed SVG body.
            val resolver=targetContext.contentResolver
            for ((index,mime) in listOf("image/svg+xml","text/xml").withIndex()) {
                if (vm.source == null) {
                    runOnMainSync { vm.begin(); activity.startActivity(request(robot,Intent.ACTION_SEND,"application/octet-stream")) }
                    await("return to photo picker") { vm.source!=null && !vm.busy }
                }
                val requestedName="myembro-intake-${System.currentTimeMillis()}.svg"
                download=checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,requestedName);put(MediaStore.MediaColumns.MIME_TYPE,mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/")
                }))
                resolver.openOutputStream(download!!).use { checkNotNull(it).write(svg.toByteArray()) }
                val display=resolver.query(download!!,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)!!.use {
                    check(it.moveToFirst());it.getString(0)
                }
                if(index==0) check(display.endsWith(".svg"))
                check(resolver.getType(download!!) == mime)
                click(targetContext.getString(R.string.photo_pick_again))
                await("SAF picker") { uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true }
                if(find(display)==null) {
                    if(find("Downloads","다운로드")==null) click("Show roots","루트 표시","탐색 창 표시","탐색 메뉴 열기")
                    click("Downloads","다운로드")
                }
                await("Downloads SVG selectable") { find(display)!=null }
                shot("0${index*2+1}-downloads-svg-selectable.png")
                click(display)
                await("SAF SVG loaded") { vm.isSvg && !vm.busy && vm.passed }
                shot("0${index*2+2}-saf-svg-pass.png")
                log+="SAF PASS: Downloads/$display (actual MIME $mime) selected using real OpenDocument picker -> vector PES sewout PASS"
                resolver.delete(download!!,null,null);download=null
            }
            val bad=File(directory,"not-a-picture.txt").apply { writeText("plain text, not a picture") }
            runOnMainSync { vm.load(uri(bad)) }
            await("nonpicture rejection") { !vm.busy && vm.error!=null }
            check(vm.error==R.string.picture_intake_not_picture)
            log+="PICKER_BOUNDARY PASS: nonpicture content -> localized not-a-picture message"

            val pesName="intake-synthetic-${System.currentTimeMillis()}.pes"
            val pes=File(directory,pesName)
            context.assets.open("fixtures/flower.v1.pes").use { input -> pes.outputStream().use { input.copyTo(it) } }
            lateinit var viewer: ViewerViewModel
            runOnMainSync { viewer=model("getViewer");activity.startActivity(request(pes,Intent.ACTION_SEND,"application/octet-stream")) }
            await("PES viewer") { viewer.fileName==pesName && viewer.render!=null }
            imported=File(targetContext.filesDir,"designs/$pesName")
            log+="PES_REGRESSION PASS: real PES bytes + application/octet-stream -> unchanged importer/viewer, never photo"
            download?.let { targetContext.contentResolver.delete(it,null,null) }; download=null
            imported?.delete(); imported=null
            finish(0,Bundle().apply { putString("result",log.joinToString("\n")+"\nPICTURE_INTAKE_PASS") })
        } catch(error: Throwable) {
            download?.let { targetContext.contentResolver.delete(it,null,null) }; download=null
            imported?.delete(); imported=null
            finish(1,Bundle().apply { putString("error",log.joinToString("\n")+"\n"+error.stackTraceToString()) }) }
        finally { download?.let { targetContext.contentResolver.delete(it,null,null) }; imported?.delete() }
    }
}
