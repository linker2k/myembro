// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern

/** Final default-writer boundary. Imported source follows the explicit imported policy;
 * generated source markers enable generated density/lock/repetition/length blocking. */
object PesOutputSafety {
    /** Carries the actual report so callers distinguish sewing blockers from encoding failures. */
    class Rejected(val report: SewoutReport, val outputErrors: List<String>) :
        IllegalArgumentException(outputErrors.joinToString("; "))
    data class Checked(val bytes:ByteArray,val report:SewoutReport)
    private val outputMachine=SewoutMachine("output","Selected hoop",Specification(),Specification(),knownHoopsMm=emptySet())
    private fun analyze(bytes:ByteArray,hoop:HoopSpec,source:EmbPattern,machine:SewoutMachine=outputMachine,origin:DesignOrigin=SewoutCheck.originOf(source)): Pair<List<String>,SewoutReport> {
        val errors=PecViewportCheck.check(bytes,hoop).errors.toMutableList()
        var x=0;var y=0
        for((index,s)in boundedPecStitches(bytes).withIndex()) {
            if(s.command==Command.STITCH || s.command==Command.JUMP) {
                if(s.x==x && s.y==y)errors+="PEC_ZERO_MOVE: command $index ${s.command} at ($x,$y)"
                x=s.x;y=s.y
            }
        }
        val report=SewoutCheck.checkPes(bytes,machine=machine,options=SewoutOptions(hoop=hoop,origin=origin))
        errors+=report.findings.filter { it.severity==Severity.BLOCKER }.map { "${it.code}: ${it.message}" }
        if(!report.analysisComplete)errors+="ANALYSIS_INCOMPLETE"
        return errors.distinct() to report
    }
    fun errors(bytes:ByteArray,hoop:HoopSpec,source:EmbPattern):List<String> = analyze(bytes,hoop,source).first
    fun requireValid(bytes:ByteArray,hoop:HoopSpec,source:EmbPattern):ByteArray {
        return check(bytes,hoop,source).bytes
    }
    /** One actual-PES analysis, reusable by callers that need both bytes and warning locations. */
    fun check(bytes:ByteArray,hoop:HoopSpec,source:EmbPattern,machine:SewoutMachine=outputMachine,origin:DesignOrigin=SewoutCheck.originOf(source)):Checked {
        val (errors,report)=analyze(bytes,hoop,source,machine,origin)
        if(errors.isNotEmpty()) throw Rejected(report,errors)
        return Checked(bytes,report)
    }
}
