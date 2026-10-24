// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.*
import io.github.linker2k.myembro.usb.*
import java.io.File

/** Original synthetic 4×1mm contours and a labeled mock result; no physical USB transfer claimed. */
class JumpAdviceInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){try {
        check(targetContext.packageName.endsWith(".jumpadvice"))
        val stitches=listOf(Stitch(0,10,Command.STITCH),Stitch(10,10,Command.STITCH),Stitch(10,0,Command.STITCH),
            Stitch(0,0,Command.STITCH),Stitch(0,10,Command.STITCH),Stitch(30,10,Command.JUMP),
            Stitch(30,0,Command.STITCH),Stitch(40,0,Command.STITCH),Stitch(40,10,Command.STITCH),
            Stitch(30,10,Command.STITCH),Stitch(30,10,Command.END))
        val bytes=PesWriter.write(EmbPattern(stitches,listOf(EmbPattern.DEFAULT_PALETTE[0])))
        val cleanup=JumpThreadCleanup.assessPes(bytes,SewoutMachines.SE400)
        check(cleanup.manualCutCount==1)
        File(targetContext.filesDir,"designs").mkdirs()
        File(targetContext.filesDir,"designs/JUMPS.pes").writeBytes(bytes)
        check(targetContext.getSharedPreferences("myembro_settings",Context.MODE_PRIVATE).edit()
            .putString("machine_model","Brother SE400").putString("theme_mode","LIGHT").commit())
        val record=UsbTransferRecord(drive="UI smoke (synthetic record)",files=listOf(
            UsbTransferredFile("JUMPS.PES",bytes.size.toLong(),manualJumpThreadCount=cleanup.manualCutCount,
                automaticJumpTrim=false,machineName="Brother SE400")))
        UsbTransferHistory(File(targetContext.filesDir,"usb-transfers.json")).add(record)
        check(manualJumpAdvice(record)==ManualJumpAdvice(1,"SE400"))
        finish(-1,Bundle().apply {putString("result","JUMP_ADVICE_FIXTURE_READY count=1; synthetic USB record only")})
    }catch(e:Throwable){finish(0,Bundle().apply {putString("error",e.stackTraceToString())})}}
}
