// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern

/** Final default-writer boundary. Imported source follows the explicit imported policy;
 * generated source markers enable generated density/lock/repetition/length blocking. */
object PesOutputSafety {
    fun errors(bytes:ByteArray,hoop:HoopSpec,source:EmbPattern):List<String> {
        val errors=PecViewportCheck.check(bytes,hoop).errors.toMutableList()
        var x=0;var y=0
        for((index,s)in boundedPecStitches(bytes).withIndex()) {
            if(s.command==Command.STITCH || s.command==Command.JUMP) {
                if(s.x==x && s.y==y)errors+="PEC_ZERO_MOVE: command $index ${s.command} at ($x,$y)"
                x=s.x;y=s.y
            }
        }
        val report=SewoutCheck.checkPes(bytes,machine=SewoutMachine("output","Selected hoop",Specification(),Specification(),knownHoopsMm=emptySet()),options=SewoutOptions(hoop=hoop,origin=SewoutCheck.originOf(source)))
        errors+=report.findings.filter { it.severity==Severity.BLOCKER }.map { "${it.code}: ${it.message}" }
        if(!report.analysisComplete)errors+="ANALYSIS_INCOMPLETE"
        return errors.distinct()
    }
    fun requireValid(bytes:ByteArray,hoop:HoopSpec,source:EmbPattern):ByteArray {
        val errors=errors(bytes,hoop,source)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        return bytes
    }
}
