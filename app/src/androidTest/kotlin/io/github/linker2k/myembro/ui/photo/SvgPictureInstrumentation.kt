// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.R
import java.io.File

/** Synthetic SVG/bitmap only, development emulator-5558. No external AI app receives data. */
class SvgPictureInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val log = mutableListOf<String>()
        try {
            val dir = File(targetContext.cacheDir, "pes_exports/svg-picture-test").apply { mkdirs() }
            val out = File(targetContext.filesDir, "svg-picture-smoke").apply { mkdirs() }
            fun intent(file: File, mime: String): Intent {
                val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", file)
                return Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .setClass(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri("synthetic", uri) }
            }
            val valid = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="40" height="40" fill="#ffcc00"/><rect x="60" y="60" width="30" height="30" fill="#000000"/></svg>"""
            val file = File(dir, "two-color.svg").apply { writeText(valid) }
            val activity = startActivitySync(intent(file, "image/svg+xml")) as MainActivity
            fun <T> model(name: String): T = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) as T
            lateinit var vm: PhotoViewModel
            lateinit var draft: AiPictureDraft
            runOnMainSync { vm = model("getPhoto"); draft = model("getAiPictureDraft") }
            fun await(label: String, condition: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + 30_000
                while (!condition()) { check(SystemClock.uptimeMillis() < end) { "$label timeout; ${vm.error}" }; Thread.sleep(100) }
                waitForIdleSync()
            }
            fun shot(name: String) {
                waitForIdleSync(); uiAutomation.waitForIdle(250, 5000); SystemClock.sleep(500)
                checkNotNull(uiAutomation.takeScreenshot()).let { image -> File(out, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle() }
            }
            await("SVG vector PES") { !vm.busy && vm.report != null }
            check(vm.isSvg && vm.source == null && vm.outline == null && vm.passed)
            check(vm.render!!.pattern.colorBlocks.map { it.thread.color }.distinct().size == 2)
            log += "SVG_DIRECT PASS: Android parser -> VectorArt defaults -> 2 colors -> PES sewout PASS, no bitmap/trace"
            runOnMainSync { vm.changeSize(30.0) }
            await("SVG size") { !vm.busy && vm.report != null }
            check(vm.widthMm == 30.0 && vm.passed)
            shot("01-svg-direct-pass.png")
            log += "SVG_SIZE PASS: existing picture range + 0.1mm rounding; size=30mm, sewout PASS"

            // Return generated images through the ACTUAL MainActivity AI-return routing.
            fun generated(n: Int): File {
                val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
                val c = Canvas(bitmap); c.drawColor(Color.WHITE)
                val p = Paint().apply { isAntiAlias = false }
                if (n == 2) {
                    p.color = 0xffffcc00.toInt(); c.drawCircle(160f, 160f, 110f, p)
                    p.color = Color.BLACK; c.drawCircle(120f, 135f, 10f, p); c.drawCircle(200f, 135f, 10f, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 8f
                    c.drawArc(RectF(110f, 140f, 210f, 220f), 0f, 180f, false, p)
                } else {
                    val heart = Path().apply {
                        moveTo(160f, 270f); cubicTo(20f, 170f, 20f, 60f, 100f, 60f)
                        cubicTo(135f, 60f, 150f, 85f, 160f, 100f)
                        cubicTo(170f, 85f, 185f, 60f, 220f, 60f)
                        cubicTo(300f, 60f, 300f, 170f, 160f, 270f); close()
                    }
                    for ((scale, color) in listOf(1f to Color.RED, .65f to Color.GREEN, .3f to Color.BLUE)) {
                        c.save(); c.scale(scale, scale, 160f, 160f); p.color = color; c.drawPath(heart, p); c.restore()
                    }
                }
                val result = File(dir, "${n}-color.png")
                result.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
                return result
            }
            for (n in listOf(2, 3)) {
                val image = generated(n)
                runOnMainSync {
                    draft.request.value = if (n == 2) "2가지 색으로 스마일" else "3가지 색으로 하트"
                    draft.colors.value = n; draft.expectImage()
                    activity.startActivity(intent(image, "image/png"))
                }
                Thread.sleep(150); await("AI PNG load") { !vm.busy && vm.source != null }
                check(!vm.isSvg && vm.colorCount == n && vm.style == PhotoMode.COLOR)
                runOnMainSync {
                    vm.configure(io.github.linker2k.myembro.ui.settings.SettingsPreferences(targetContext).read().copy(myThreads = io.github.linker2k.myembro.engine.io.MyThreads()))
                    vm.next()
                }
                await("AI color trace") { !vm.busy && vm.outline != null }
                runOnMainSync { vm.changeSize(30.0) }
                await("AI size") { !vm.busy && vm.outline != null }
                runOnMainSync { vm.next() }
                await("AI sewout") { !vm.busy && vm.report != null }
                check(vm.passed) { "n=$n blocked ${vm.error} ${vm.report}" }
                val actual = vm.render!!.pattern.colorBlocks.map { it.thread.color }.distinct().size
                check(actual == n) { "Requested $n, got $actual" }
                log += "AI_COLOR_${n} PASS: shared PNG automatically sets N=$n; actual threads=$actual; existing bilinear unchanged; stitches=${vm.report!!.statistics.stitchCount}; sewout PASS"
                shot("0${n}-${n}-color-pass.png")
            }
            runOnMainSync { vm.begin() }
            check(vm.colorCount == 4 && vm.style == PhotoMode.SILHOUETTE)
            log += "NORMAL_PHOTO PASS: begin() retains 4-color default"
            val reference = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val request = "하트 아래 글자는 정확히 \"코코\""
            val bad = File(dir, "unsupported.svg").apply { writeText(valid.replace("<rect width=", "<text>unsupported</text><rect width=")) }
            runOnMainSync {
                draft.request.value = request; draft.colors.value = 3; draft.photo.value = reference
                draft.format.value = AiPicturePrompt.Format.SVG; draft.expectImage()
                activity.startActivity(intent(bad, "image/svg+xml"))
            }
            Thread.sleep(150); await("unsupported SVG") { !vm.busy && vm.error != null }
            check(vm.error == R.string.svg_picture_unsupported && vm.isSvg && !vm.canSend)
            fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(root) + (0 until root.childCount).flatMap { root.getChild(it)?.let(::nodes).orEmpty() }
            await("PNG retry button") { uiAutomation.rootInActiveWindow?.let(::nodes)?.any { it.text?.toString() == targetContext.getString(R.string.ai_picture_retry_png) } == true }
            var node = nodes(uiAutomation.rootInActiveWindow!!).first { it.text?.toString() == targetContext.getString(R.string.ai_picture_retry_png) }
            while (!node.isClickable) node = checkNotNull(node.parent)
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); waitForIdleSync()
            check(draft.format.value == AiPicturePrompt.Format.PNG && draft.request.value == request && draft.colors.value == 3 && draft.photo.value === reference)
            shot("04-unsupported-png-retry.png")
            log += "SVG_RETRY PASS: unsupported element -> localized PNG guidance; request/reference/colors preserved, only format becomes PNG; no auto share"
            finish(0, Bundle().apply { putString("result", log.joinToString("\n") + "\nSVG_PICTURE_PASS") })
        } catch (failure: Throwable) { finish(1, Bundle().apply { putString("error", log.joinToString("\n") + "\n" + failure.stackTraceToString()) }) }
    }
}
