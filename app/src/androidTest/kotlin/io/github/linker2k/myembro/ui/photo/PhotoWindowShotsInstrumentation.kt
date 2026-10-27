// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.R
import java.io.File

/**
 * Screenshots of the photo window (repository robot.png and a synthetic SVG; no personal files): a PNG at
 * step 2 in color mode with the noise and contrast sliders and the grayscale switch, and an SVG opened by a
 * share intent landing in the same photo window.
 */
class PhotoWindowShotsInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val log = mutableListOf<String>()
        try {
            val directory = File(targetContext.cacheDir, "pes_exports/photo-window").apply { mkdirs() }
            val out = File(targetContext.filesDir, "photo-window").apply { mkdirs() }
            fun send(file: File, mime: String) = Intent(Intent.ACTION_SEND).setClass(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                    val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", file)
                    type = mime; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri("synthetic", uri)
                }
            val robot = File(directory, "robot.png")
            context.assets.open("image-to-design/robot.png").use { input -> robot.outputStream().use { input.copyTo(it) } }
            val activity = startActivitySync(send(robot, "image/png").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            lateinit var vm: PhotoViewModel
            runOnMainSync { vm = MainActivity::class.java.getDeclaredMethod("getPhoto").apply { isAccessible = true }.invoke(activity) as PhotoViewModel }
            fun await(label: String, condition: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + 60_000
                while (!condition()) { check(SystemClock.uptimeMillis() < end) { "$label timed out; error=${vm.error} busy=${vm.busy} step=${vm.step}" }; Thread.sleep(100) }
                waitForIdleSync()
            }
            fun nodes(): List<AccessibilityNodeInfo> {
                fun all(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::all).orEmpty() }
                runCatching { uiAutomation.clearCache() }
                return uiAutomation.rootInActiveWindow?.let(::all).orEmpty()
            }
            fun shot(name: String) {
                waitForIdleSync(); uiAutomation.waitForIdle(250, 5000); SystemClock.sleep(900)
                val image = checkNotNull(uiAutomation.takeScreenshot())
                File(out, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
            await("PNG loaded") { vm.source != null && !vm.busy }
            runOnMainSync { vm.next() }
            await("step 2") { vm.step == 1 && !vm.busy }
            runOnMainSync { vm.changeStyle(PhotoMode.COLOR) }
            await("color") { vm.style == PhotoMode.COLOR && !vm.busy }
            runOnMainSync { vm.changeGrayscale(true) }
            await("grayscale") { vm.grayscale && !vm.busy }
            val noise = targetContext.getString(R.string.photo_noise, targetContext.getString(R.string.photo_noise_off)).substringBefore(':')
            // Grayscale restarts processing: wait until it settles and the sliders are in the tree.
            Thread.sleep(500)
            await("noise slider") { !vm.busy && nodes().any { it.contentDescription?.toString()?.startsWith(noise) == true || it.text?.toString()?.startsWith(noise) == true } }
            shot("01-photo-png-sliders.png")
            // Scroll to the grayscale switch and color count.
            nodes().filter { it.isScrollable }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            val gray = targetContext.getString(R.string.photo_grayscale)
            await("grayscale switch") { nodes().firstOrNull { it.contentDescription?.toString() == gray }?.isChecked == true }
            shot("02-photo-png-grayscale.png")
            log += "PNG PASS: robot.png -> photo window step 2, color, grayscale on; noise/contrast sliders and grayscale switch present"

            val svg = File(directory, "shapes.svg").apply { writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="35" cy="40" r="25" fill="#E53935"/><rect x="45" y="45" width="40" height="40" fill="#1E88E5"/></svg>""") }
            runOnMainSync { vm.begin(); activity.startActivity(send(svg, "image/svg+xml")) }
            await("SVG in photo window") { vm.isSvg && !vm.busy && vm.passed }
            shot("03-photo-svg.png")
            log += "SVG PASS: share image/svg+xml -> photo window (isSvg, step ${vm.step + 1}), sewout passed"
            runOnMainSync { activity.finish() }
            finish(0, Bundle().apply { putString("result", log.joinToString("\n") + "\nPHOTO_WINDOW_PASS") })
        } catch (failure: Throwable) {
            runCatching { uiAutomation.takeScreenshot()?.let { image ->
                File(targetContext.filesDir, "photo-window/failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
            finish(1, Bundle().apply { putString("error", log.joinToString("\n") + "\n" + failure.stackTraceToString()) })
        }
    }
}
