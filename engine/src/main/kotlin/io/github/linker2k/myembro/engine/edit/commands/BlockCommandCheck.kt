// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit.commands

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.sewout.*

/** Keep generation/encoding failures separate from completed sewing-check blockers. */
internal object BlockCommandCheck {
    data class Failure(val code: CommandError, val message: String, val kind: BlockGenerationKind? = null)
    private val machine = SewoutMachine("output", "Selected hoop", Specification(), Specification(), knownHoopsMm=emptySet())
    private fun generation(e: Exception, fallback: BlockGenerationKind) = Failure(CommandError.GENERATION_FAILED,
        e.message ?: "Block generation failed", (e as? BlockGenerationException)?.kind ?: fallback)
    private fun blocked(report: SewoutReport) = Failure(CommandError.SEWOUT_BLOCKED,
        report.findings.filter { it.severity==Severity.BLOCKER }.joinToString("; ") { "${it.code}: ${it.message}" }.ifBlank { "ANALYSIS_INCOMPLETE" })

    fun check(candidate: Project, id: String, satinRegions: Set<String>): Failure? {
        val pattern = try {
            val shape=candidate.find(id) as ShapeObject
            for(region in shape.regions.filter { it.id in satinRegions && shape.overrides.containsKey(it.id) && it.stitch.type==BlockStitch.Type.SATIN }) {
                val satin=BlockStitchCapabilities.forRegion(region,shape.transform).satin
                if(!satin.possible) {
                    val kind=when(satin.reason) {
                        "SATIN_GEOMETRY_UNSUPPORTED" -> BlockGenerationKind.SATIN_GEOMETRY_UNSUPPORTED
                        "SATIN_STITCH_TOO_LONG" -> BlockGenerationKind.SATIN_STITCH_TOO_LONG
                        else -> BlockGenerationKind.SATIN_GENERATION_FAILED
                    }
                    throw BlockGenerationException(kind,satin.reason ?: "Satin generation failed")
                }
            }
            candidate.flatten()
        } catch(e: java.util.concurrent.CancellationException) { throw e
        } catch(e: InterruptedException) { throw e
        } catch(e: Exception) { return generation(e,if(satinRegions.isEmpty()) BlockGenerationKind.REGION_ORDER else BlockGenerationKind.REGION_GENERATION) }
        val options=SewoutOptions(hoop=candidate.hoop,origin=DesignOrigin.APP_GENERATED)
        val placed=SewoutCheck.check(GeneratedPatternProcessor.forSewout(pattern),machine,
            options.copy(placement=Placement.HOOP_COORDINATES))
        if(!placed.analysisComplete || placed.hasBlockingIssues) return blocked(placed)
        val bytes=try {
            PesWriter.write(pattern,PesWriteSettings(hoop=candidate.hoop)).also { PecViewportCheck.requireValid(it,candidate.hoop) }
        } catch(e: java.util.concurrent.CancellationException) { throw e
        } catch(e: InterruptedException) { throw e
        } catch(e: PesOutputSafety.Rejected) {
            return if(!e.report.analysisComplete || e.report.hasBlockingIssues) blocked(e.report) else generation(e,BlockGenerationKind.OUTPUT_ENCODING)
        } catch(e: Exception) { return generation(e,BlockGenerationKind.OUTPUT_ENCODING) }
        val report=SewoutCheck.checkPes(bytes,machine,options)
        return if(!report.analysisComplete || report.hasBlockingIssues) blocked(report) else null
    }
}
