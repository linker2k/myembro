// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.render.realistic

import kotlinx.serialization.json.*
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.*

/** Repository-owned DISPLAY workload, not a sewable PES or a gate bypass.
 * repeated: 2500 grid placements repeated, exact cache reuse (best case).
 * varied: same grid plus distinct subpixel phase/delta per segment, exact cache misses (stress case).
 * These fixture/measurement values are not stitch-generation defaults or application policy.
 */
object RealisticPreviewBenchmark {
    @JvmStatic fun main(args:Array<String>) {
        val count=args[0].toInt();require(count==5000||count==50000)
        val mode=args[1];require(mode in listOf("repeated","varied"))
        val out=File(args[2]).apply { mkdirs() }
        val dpi=600
        // Integer SVG page at 600dpi => exact scale 6.25, independent of viewport rounding.
        val scale=dpi/96.0;val width=(360*scale).toInt();val height=(320*scale).toInt()
        val colors=intArrayOf(0x184687,0x8E1D22,0x000000,0xA2A2A2)
        val segments=List(count) { i ->
            val grid=i%2500;val col=grid%25;val row=grid/25
            val phase=if(mode=="varied") i.toDouble()/count else 0.0
            val start=RenderPoint(4.0+col*14+phase,4.0+row*3+phase)
            val direction=grid%3
            val end=RenderPoint(start.x+(if(direction==2)0.0 else 12.0)+phase,
                start.y+(if(direction==2)12.0 else if(direction==1)3.0 else 0.0))
            RenderThreadSegment(start,end,colors[grid%4])
        }
        // Explicit experiment budget: no more cached pixel payload than one output raster.
        // Keys/object overhead is additional, covered by measured process/heap usage below.
        val cache=ThreadShapeCache(width.toLong()*height*4)
        val rasterizer=RealisticThreadRendererTest().rasterizer
        val memory=ManagementFactory.getMemoryMXBean()
        val initialHeap=memory.heapMemoryUsage.used
        val peak=AtomicLong(initialHeap);val running=AtomicBoolean(true)
        val gc=ManagementFactory.getGarbageCollectorMXBeans()
        val beforeGc=gc.sumOf { it.collectionCount.coerceAtLeast(0) }
        val sampler=thread(name="preview-heap-sampler",isDaemon=true) {
            while(running.get()) {
                peak.accumulateAndGet(memory.heapMemoryUsage.used,::max)
                Thread.sleep(1) // Measurement sampling interval, not a rendering parameter.
            }
        }
        val started=System.nanoTime()
        val output=RealisticThreadRenderer.render(width,height,dpi,segments,rasterizer,cache,scale)
        val elapsed=(System.nanoTime()-started)/1_000_000.0
        peak.accumulateAndGet(memory.heapMemoryUsage.used,::max)
        running.set(false);sampler.join()
        val afterGc=gc.sumOf { it.collectionCount.coerceAtLeast(0) }
        val status=File("/proc/self/status").takeIf { it.exists() }?.readLines().orEmpty()
        val rss=status.firstOrNull { it.startsWith("VmHWM:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()
        val digest=MessageDigest.getInstance("SHA-256")
        output.forEach { pixel -> for(channel in 0..3)digest.update((pixel ushr (24-channel*8)).toByte()) }
        val sha=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val report=buildJsonObject {
            put("segments",count);put("workload",mode);put("dpi",dpi)
            put("widthPx",width);put("heightPx",height);put("pixelsPerSvgUnit",scale)
            put("renderMs",elapsed);put("msPerStitch",elapsed/count)
            put("jvmMaxHeapBytes",Runtime.getRuntime().maxMemory());put("initialHeapBytes",initialHeap);put("peakSampledHeapBytes",peak.get())
            put("heapSampleIntervalMs",1);if(rss!=null)put("processPeakRssKiB",rss)
            put("cachePixelBudgetBytes",cache.maxPixelBytes);put("cachePixelBytes",cache.cachedPixelBytes)
            put("cacheHits",cache.hits);put("cacheMisses",cache.misses);put("gcCollections",afterGc-beforeGc)
            put("argbSha256",sha);put("jvm",System.getProperty("java.version"))
            put("timing","includes fixture-path/raster/filter/cache/blit, excludes fixture creation and hash/PNG encoding")
        }
        File(out,"$mode-$count.json").writeText(report.toString()+"\n")
        println("REALISTIC_BENCH $report")
    }
}
