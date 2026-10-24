// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.sewout.SewoutReport
import io.github.linker2k.myembro.usb.UsbTransferRecord

internal data class ManualJumpAdvice(val count:Int,val machine:String)
internal fun manualJumpAdvice(count:Int?,machine:String?,automaticJumpTrim:Boolean?):ManualJumpAdvice? =
    if(automaticJumpTrim==false && count!=null && count>0 && !machine.isNullOrBlank())
        ManualJumpAdvice(count,machine.removePrefix("Brother ")) else null

internal fun manualJumpAdvice(report:SewoutReport):ManualJumpAdvice? =
    if(report.analysisComplete)manualJumpAdvice(report.statistics.manualJumpThreadCount,report.machine.name,report.machine.automaticJumpTrim.value) else null

internal fun manualJumpAdvice(record:UsbTransferRecord):ManualJumpAdvice? {
    if(record.files.isEmpty() || record.files.any {it.automaticJumpTrim!=false})return null
    val machine=record.files.map {it.machineName}.distinct().singleOrNull()
    return manualJumpAdvice(record.manualJumpThreadCount,machine,false)
}

@Composable
internal fun ManualJumpAdviceText(advice:ManualJumpAdvice?) {
    if(advice!=null) Text(pluralStringResource(R.plurals.manual_jump_cleanup,advice.count,advice.count,advice.machine),
        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
