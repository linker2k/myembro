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

/** Opt-in 5556 smoke using a synthetic wide drawing, never personal pictures. */
class PhotoContainSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            val directory = File(targetContext.filesDir, "photo-contain-smoke").apply { mkdirs() }
            val image = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
            Canvas(image).apply {
                drawColor(Color.WHITE)
                val ink = Paint().apply { color = Color.BLACK }
                drawCircle(65f, 100f, 40f, ink)
                drawRect(160f, 60f, 240f, 140f, ink)
                drawCircle(335f, 100f, 40f, ink)
            }
            val input = File(directory, "wide-synthetic.png")
            input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var vm: PhotoViewModel
            runOnMainSync {
                vm = PhotoViewModel(targetContext.applicationContext as Application)
                activity.setContent { MyembroTheme { PhotoScreen(vm, EmbroiderySettings(), {}, {}, {}) } }
                vm.load(Uri.fromFile(input))
            }
            fun ready() {
                val deadline = SystemClock.uptimeMillis() + 60_000
                while (SystemClock.uptimeMillis() < deadline) {
                    var done = false
                    runOnMainSync { done = !vm.busy }
                    if (done) { waitForIdleSync(); return }
                    SystemClock.sleep(50)
                }
                error("Photo preparation timed out")
            }
            fun shot(name: String) {
                waitForIdleSync()
                val frame = java.util.concurrent.CountDownLatch(1)
                runOnMainSync { activity.window.decorView.postOnAnimation {
                    activity.window.decorView.postOnAnimation { frame.countDown() }
                } }
                check(frame.await(10, java.util.concurrent.TimeUnit.SECONDS))
                directory.resolve(name).outputStream().use {
                    check(uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            ready(); check(vm.source != null)
            shot("01-cover.png")
            runOnMainSync { vm.transform(0.1f, 0f, 0f) }
            check(vm.zoom == 0.5f)
            shot("02-contain.png")
            runOnMainSync { vm.next() }; ready()
            check(vm.outline != null) { "outline error=${vm.error}" }
            shot("03-next-preview.png")
            runOnMainSync { vm.next() }; ready()
            check(vm.render != null) { "result error=${vm.error}" }
            val displayDeadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < displayDeadline &&
                uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Generating stitches")?.isNotEmpty() == true) {
                SystemClock.sleep(50)
            }
            check(uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Generating stitches")?.isEmpty() == true)
            shot("04-result.png")
            finish(0, Bundle().apply { putString("result", "PHOTO_CONTAIN_PASS zoom=${vm.zoom} stitches=${vm.render!!.pattern.stitchCount}") })
        } catch (failure: Throwable) {
            finish(1, Bundle().apply { putString("error", failure.stackTraceToString()) })
        }
    }
}
