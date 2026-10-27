// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.texture

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.sewout.*
import java.io.File
import java.util.Locale
import kotlin.math.floor
import kotlinx.serialization.json.*

/** Opt-in extended measurement, not a CI test or a change to plotter/sewing defaults. */
object TextureRasterSweep {
    @JvmStatic fun main(args: Array<String>) {
        val out=File(args.firstOrNull() ?: "/tmp/myembro-texture-pitch").apply { mkdirs() }
        val started=System.nanoTime()
        val rows=mutableListOf("pitch_mm\timage\tframe_mm\traster\tactual_pitch_mm\tplot_lines\tstitches\tpeak_0.5mm\tverdict\tblockers\tgeneration_ms")
        val pass=linkedMapOf<Int,Boolean>()
        val peaks=linkedMapOf<Int,Int>()
        for (hundredths in 20..60 step 5) {
            var allPass=true;var maxPeak=0
            for (image in TextureSyntheticFixtures.names) for (mm in listOf(30.0,40.0,60.0,90.0)) {
                val edge=floor(mm*100/hundredths).toInt()
                val pixels=TextureSyntheticFixtures.pixels(image,edge)
                val t=System.nanoTime()
                val palette=ThreadTextureAdapter.brotherPalette(pixels,if(image=="ramp")4 else 2)
                // Deliberately test input scales before a runtime preset exists; no gate is bypassed.
                val plot=SketchLines.render(edge,edge,pixels)
                val result=ThreadTextureAdapter.fromSketch(plot,edge,edge,mm,palette,HoopSpec.H100)
                val blockers=result.sewout.findingSeverities.filterValues { it==Severity.BLOCKER }.keys.map { it.name }.toMutableSet()
                var peak=result.sewout.statistics.peakCellPenetrations
                if(blockers.isEmpty()) {
                    try {
                        val bytes=result.pes()
                        check(PecViewportCheck.check(bytes,HoopSpec.H100).valid)
                        check(PesReader.read(bytes).stitchCount>0)
                        val actual=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
                        blockers+=actual.findingSeverities.filterValues { it==Severity.BLOCKER }.keys.map { it.name }
                        peak=maxOf(peak,actual.statistics.peakCellPenetrations)
                    } catch (e:PesOutputSafety.Rejected) {
                        blockers+=e.report.findingSeverities.filterValues { it==Severity.BLOCKER }.keys.map { it.name }
                        blockers+=e.outputErrors.filter { it.startsWith("PEC_") }.map { it.substringBefore(':') }
                        peak=maxOf(peak,e.report.statistics.peakCellPenetrations)
                    }
                } else check(runCatching { result.pes() }.isFailure)
                val passed=blockers.isEmpty() && result.sewout.analysisComplete && plot.termination==SketchTermination.LUMINANCE
                if(!passed && blockers.isEmpty()) blockers+="INCOMPLETE_OR_SEARCH_FAILED"
                allPass=allPass && passed;maxPeak=maxOf(maxPeak,peak)
                rows+="${hundredths/100.0}\t$image\t$mm\t$edge\t${mm/edge}\t${plot.lines.size}\t${result.pattern.stitchCount}\t$peak\t${if(passed)"PASS" else "BLOCK"}\t${blockers.sorted().joinToString(",").ifEmpty { "none" }}\t${String.format(Locale.ROOT,"%.3f",(System.nanoTime()-t)/1e6)}"
                File(out,"results.tsv").writeText(rows.joinToString("\n",postfix="\n"))
            }
            pass[hundredths]=allPass;peaks[hundredths]=maxPeak
            println("PITCH ${hundredths/100.0}: ${if(allPass)"12/12 PASS" else "BLOCK present"}, peak=$maxPeak")
        }
        val passing=pass.filterValues { it }.keys.toList()
        val normal=passing.firstOrNull()
        val coarse=passing.filter { normal!=null && it>normal }.take(2)
        val policy=buildJsonObject {
            put("schemaVersion",1);put("sourceCommit","cebdc60");put("seed",0)
            put("rounding","floor(frameMm / pitchMm), so actual pitch is never finer than requested");put("matrixCases",rows.size-1);put("testedFrameWidthsMm",buildJsonArray { listOf(30,40,60,90).forEach { add(it) } })
            put("normalMmPerPixel",normal?.let { JsonPrimitive(it/100.0) } ?: JsonNull)
            put("coarseMmPerPixel",buildJsonArray { coarse.forEach { add(it/100.0) } })
            put("fineMinimumMmPerPixel",normal?.let { JsonPrimitive(it/100.0) } ?: JsonNull)
            put("allPassingPitchesMmPerPixel",buildJsonArray { passing.forEach { add(it/100.0) } })
            put("ready",normal!=null && coarse.size==2)
            put("scope","3 synthetic images x 4 frame sizes; not a guarantee for arbitrary photographs")
            put("onBlock","너무 빽빽해요: 크기를 키우거나 디테일을 낮추세요")
        }
        File(out,"policy.json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),policy)+"\n")
        File(out,"summary.tsv").writeText("pitch_mm\tpass_count\tmax_peak\n"+pass.keys.joinToString("\n") { p ->
            val count=rows.drop(1).count { it.substringBefore('\t')==(p/100.0).toString() && it.split('\t')[8]=="PASS" }
            "${p/100.0}\t$count/12\t${peaks[p]}"
        }+"\n")
        println("POLICY $policy")
        println("ELAPSED_SECONDS ${(System.nanoTime()-started)/1e9}")
    }
}
