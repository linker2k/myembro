// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.app.Instrumentation
import android.util.Log
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters
import kotlinx.serialization.json.*
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in ART timings of lazy ShapeObject override generation, not desktop JVM timings.
 * Synthetic 40mm geometry only. No timing threshold or sewing-safety claim. */
@Suppress("DEPRECATION")
class BlockGenerationTimingInstrumentation : Instrumentation() {
    private var selection = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); selection = arguments ?: Bundle(); start() }
    override fun onStart() {
        val status = Bundle().apply {
            putString("id", "BlockGenerationTiming"); putInt("numtests", 1); putInt("current", 1)
            putString("class", this@BlockGenerationTimingInstrumentation.javaClass.name); putString("test", "testFortyMillimeterBlocksThreeRuns")
        }
        sendStatus(1, status)
        val finished = AtomicBoolean(false)
        selection.getString("timeoutSeconds")?.toLong()?.let { seconds ->
            require(seconds > 0)
            Thread({
                Thread.sleep(seconds * 1000)
                if (finished.compareAndSet(false, true)) {
                    val message = "MEASUREMENT_TIMEOUT after ${seconds}s; shape=${selection.getString("shape")} method=${selection.getString("method")}; no successful median"
                    Log.e("BlockGenerationTiming", message)
                    status.putString("stack", message)
                    sendStatus(-2, status)
                    finish(-1, Bundle().apply { putString("stream", "FAILURES!!!\n$message") })
                }
            }, "measurement-budget").apply { isDaemon = true; start() }
        }
        try {
            testFortyMillimeterBlocksThreeRuns()
            finished.set(true)
            sendStatus(0, status)
            finish(-1, Bundle().apply { putString("stream", "OK (1 test)\n") })
        } catch (error: Throwable) {
            finished.set(true)
            status.putString("stack", error.stackTraceToString())
            sendStatus(-2, status)
            finish(-1, Bundle().apply { putString("stream", "FAILURES!!!\n${error.stackTraceToString()}") })
        }
    }
    fun testFortyMillimeterBlocksThreeRuns() {
        val report = StringBuilder("shape,method,run1_ms,run2_ms,run3_ms,median_elapsed_ms,stitches,errors\n")
        val failures = mutableListOf<String>()
        val methods = listOf("tatami_fill" to "N3-11a", "contour_fill" to "N3-11a",
            "circular_fill" to "N3-11a", "meander_fill" to "NC5-11a",
            "meander_fill" to "P3-1", "meander_fill" to "N3-11a")
        for (shape in listOf("square", "circle").filter { selection.getString("shape") == null || selection.getString("shape") == it }) {
            val path = if (shape == "square") "M0 0H40V40H0Z" else
                "M40 20A20 20 0 1 0 0 20A20 20 0 1 0 40 20Z"
            val base = ShapeObject.fromSvg("timing-$shape", "Synthetic $shape",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 40 40\"><path fill=\"#224488\" d=\"$path\"/></svg>", 40.0)
            check(base.regions.isNotEmpty())
            for ((method, tile) in methods.filter {
                (selection.getString("method") == null || selection.getString("method") == it.first) &&
                    !(selection.getString("skipCircular") == "true" && it.first == "circular_fill")
            }) {
                val samples = mutableListOf<Double>()
                val counts = mutableListOf<Int>()
                repeat(3) {
                    // Decode/preparation is deliberately outside the timer. Each object has a fresh lazy
                    // renderer; automatic_angle=false selects the explicit backend even for default tatami.
                    val data = ShapeObjectCodec.encode(base)
                    val defaults = FillParameters()
                    val block = buildJsonObject {
                        put("type", "FILL"); put("automatic_angle", false)
                        put("fill_method", method); put("angle", defaults.angle)
                        put("row_spacing_mm", defaults.row_spacing_mm)
                        put("meander_pattern", tile)
                        put("meander_scale_percent", buildJsonArray { add(100.0); add(100.0) })
                        put("zigzag_spacing_mm", JsonNull)
                    }
                    val payload = JsonObject(data + ("blocks" to buildJsonObject {
                        base.regions.forEach { put(it.id, block) }
                    }))
                    val obj = ShapeObjectCodec.decode(payload) as ShapeObject
                    check(obj.overrides.isNotEmpty())
                    val start = SystemClock.elapsedRealtimeNanos()
                    try {
                        val pattern = obj.localPattern()
                        check(pattern.stitchCount > 0)
                        counts += pattern.stitchCount
                    } catch (error: Exception) {
                        counts += -1
                        val failure = "$shape $method/$tile run=${it + 1}: ${error.javaClass.simpleName}: ${error.message}"
                        failures += failure
                        Log.e("BlockGenerationTiming", failure)
                    }
                    samples += (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    Log.i("BlockGenerationTiming", "sample $shape $method/$tile run=${it + 1} ms=${samples.last()}")
                }
                val label = if (method == "meander_fill") "$method/$tile" else method
                val row = "$shape,$label," + samples.joinToString(",") { String.format(Locale.ROOT, "%.3f", it) } +
                    ",${String.format(Locale.ROOT, "%.3f", samples.sorted()[1])},${counts.joinToString("/")},${counts.count { it < 0 }}"
                report.append(row).append('\n')
                File(targetContext.filesDir, "block-generation-timing.csv").writeText(report.toString())
                Log.i("BlockGenerationTiming", row)
                sendStatus(0, Bundle().apply { putString("stream", "BLOCK_TIMING $row\n") })
            }
        }
        val device = "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString()}"
        Log.i("BlockGenerationTiming", device)
        File(targetContext.filesDir, "block-generation-timing.csv").writeText(report.toString())
        sendStatus(0, Bundle().apply { putString("stream", "BLOCK_TIMING $device\n") })
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }
}
