// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import io.github.linker2k.myembro.ui.theme.MyembroShapes
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.sewout.*
import io.github.linker2k.myembro.ui.format.Dimensions

/** Only completed checks get a badge. Missing/failed/incomplete reports never show Pass. */
@Composable
internal fun ViewerCheckBadge(vm: ViewerViewModel, modifier: Modifier = Modifier) {
    val report = vm.sewoutReport
    if (report == null && vm.checkingSewout) {
        val description = stringResource(R.string.sewout_loading)
        CircularProgressIndicator(modifier.size(20.dp).semantics { contentDescription = description }, strokeWidth = 2.dp)
        return
    }
    val blocked = viewerCheckBlocked(report)
    val scheme = MaterialTheme.colorScheme
    val reason = when {
        report == null -> R.string.sewout_failed
        report.findings.any { it.severity == Severity.BLOCKER } -> report.findings.first { it.severity == Severity.BLOCKER }.code.labelRes()
        !report.analysisComplete -> R.string.sewout_incomplete
        else -> R.string.playback_check_blocked_reason
    }
    Surface(onClick = vm::openSewout,
        modifier = modifier.then(if (blocked) Modifier.fillMaxWidth() else Modifier),
        shape = MyembroShapes.Chip,
        color = if (blocked) scheme.errorContainer else scheme.secondaryContainer,
        contentColor = if (blocked) scheme.onErrorContainer else scheme.onSecondaryContainer,
        border = BorderStroke(1.dp, if (blocked) scheme.error else scheme.outline)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (blocked) Icons.Default.Block else Icons.Default.CheckCircle, null, Modifier.size(18.dp))
            Text(stringResource(if (blocked) R.string.playback_check_blocked else R.string.playback_check_pass),
                style = MaterialTheme.typography.labelLarge)
            if (blocked) Text(stringResource(reason), modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun sewoutSummary(report: SewoutReport): String = stringResource(R.string.sewout_summary,
    report.severityCounts[Severity.BLOCKER] ?: 0, report.severityCounts[Severity.WARNING] ?: 0,
    sewoutMinutes(report.time.totalSeconds))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerSewoutSheet(vm: ViewerViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = vm::closeSewout,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            Text(stringResource(R.string.sewout_title), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            val report = vm.sewoutReport
            if (report == null) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (vm.checkingSewout) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.sewout_loading))
                    } else {
                        Text(vm.sewoutError ?: stringResource(R.string.sewout_failed))
                        TextButton(onClick = vm::refreshSewout) { Text(stringResource(R.string.sewout_retry)) }
                    }
                }
            } else {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(report.machine.name, style = MaterialTheme.typography.titleSmall)
                    Text(sewoutSummary(report), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (report.analysisComplete) R.string.sewout_complete else R.string.sewout_incomplete),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (report.analysisComplete) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    if (report.suppressedFindings > 0) Text(stringResource(R.string.sewout_suppressed, report.suppressedFindings),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text(stringResource(report.acceptanceRes()), style = MaterialTheme.typography.labelSmall)
                }
                TabRow(tab) {
                    listOf(R.string.sewout_issues, R.string.sewout_tasks, R.string.sewout_colors, R.string.sewout_machine).forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label), maxLines = 2) })
                    }
                }
                val pattern = vm.render?.pattern
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (tab) {
                        0 -> {
                            if (report.findings.isEmpty()) item { Text(stringResource(R.string.sewout_no_issues)) }
                            items(report.findings.sortedBy { it.severity.ordinal }) { finding ->
                                FindingRow(finding, pattern) { vm.focusSewout(finding.highlight) }
                            }
                        }
                        1 -> {
                            val tasks = sewoutDisplayTasks(report.tasks)
                            if (tasks.isEmpty()) item { Text(stringResource(R.string.sewout_no_tasks)) }
                            itemsIndexed(tasks) { index, task ->
                                val highlight = Highlight(task.commandIndex, task.endCommandIndex, task.from, task.to)
                                CheckRow(onClick = { vm.focusSewout(highlight) }) {
                                    Text(stringResource(R.string.sewout_task_row, index + 1, stringResource(task.kind.labelRes()),
                                        task.stageIndex + 1, sewoutClock(task.atEstimatedSeconds)), style = MaterialTheme.typography.titleSmall)
                                    val stop = stringResource(if (task.machineStops) R.string.sewout_machine_stops else R.string.sewout_no_pause)
                                    Text(stop,
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(stringResource(R.string.sewout_show_location), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        2 -> {
                            item {
                                Text(stringResource(R.string.sewout_estimate_note), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.sewout_time_estimate, sewoutMinutes(report.time.totalSeconds)), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.sewout_thread_usage, report.threadUsage.upperMeters, report.threadUsage.bobbinMeters), style = MaterialTheme.typography.bodySmall)
                            }
                            if (report.stages.isEmpty()) item { Text(stringResource(R.string.sewout_no_colors)) }
                            items(report.stages) { stage ->
                                CheckRow(onClick = { vm.focusSewout(Highlight(stage.startCommandIndex, stage.endCommandIndex)) }) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.size(18.dp).background(Color(stage.thread.color), CircleShape))
                                        Text(stringResource(R.string.sewout_stage, stage.index + 1, stage.thread.displayName), style = MaterialTheme.typography.titleSmall)
                                    }
                                    Text(stringResource(R.string.sewout_stage_time, stage.stitchCount, sewoutMinutes(stage.time.totalSeconds)), style = MaterialTheme.typography.bodySmall)
                                    Text(stringResource(R.string.sewout_thread_usage, stage.threadUsage.upperMeters, stage.threadUsage.bobbinMeters), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        3 -> {
                            item {
                                Text(stringResource(R.string.sewout_effective_speed, report.speed.effectiveSpm, stringResource(when (report.speed.basis) {
                                    SpeedBasis.USER_SETTING -> R.string.sewout_speed_user
                                    SpeedBasis.PROFILE_MAXIMUM_DERIVED -> R.string.sewout_speed_profile
                                    SpeedBasis.ASSUMED -> R.string.sewout_speed_assumed
                                })), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.sewout_estimate_note), style = MaterialTheme.typography.bodySmall)
                            }
                            items(report.compatibility.checks) { check ->
                                CheckRow {
                                    Text(stringResource(check.rule.labelRes()), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(when (check.status) {
                                        CompatibilityStatus.PASS -> R.string.sewout_pass
                                        CompatibilityStatus.FAIL -> R.string.sewout_fail
                                        CompatibilityStatus.UNKNOWN -> R.string.sewout_unknown
                                    }), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            item {
                                val machine = report.machine
                                MachineFact(R.string.sewout_max_speed, machine.maximumEmbroiderySpm.value?.let { stringResource(R.string.sewout_spm, it) })
                                MachineFact(R.string.sewout_max_stitch, machine.maximumStitchMm.value?.let(Dimensions::length))
                                MachineFact(R.string.sewout_jump_trim, capability(machine.automaticJumpTrim.value))
                                MachineFact(R.string.sewout_color_cutter, capability(machine.colorEndCutter.value))
                                MachineFact(R.string.sewout_bobbin_capacity, machine.fullBobbinMeters.value?.let { stringResource(R.string.sewout_meters, it) })
                                MachineFact(R.string.sewout_bobbin_remaining, report.threadUsage.availableBobbinMeters?.let { stringResource(R.string.sewout_meters, it) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FindingRow(finding: Finding, pattern: EmbPattern?, onClick: () -> Unit) {
    val located = pattern != null && finding.highlight?.let { viewerHighlightArea(pattern, it) } != null
    CheckRow(onClick = onClick.takeIf { located }) {
        val severity = when (finding.severity) { Severity.BLOCKER -> R.string.sewout_blocker; Severity.WARNING -> R.string.sewout_warning; Severity.INFO -> R.string.sewout_info }
        Text(stringResource(severity), style = MaterialTheme.typography.labelMedium,
            color = when (finding.severity) { Severity.BLOCKER -> MaterialTheme.colorScheme.error; Severity.WARNING -> MaterialTheme.colorScheme.tertiary; Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant })
        Text(stringResource(finding.code.labelRes()), style = MaterialTheme.typography.bodyMedium)
        if (finding.measured != null && finding.threshold != null) {
            val length = finding.code in setOf(FindingCode.LONG_STITCH, FindingCode.SHORT_STITCH, FindingCode.MACHINE_STITCH_LENGTH_EXCEEDED)
            val measured = if (length) Dimensions.length(finding.measured!!) else String.format(java.util.Locale.getDefault(), "%.2f", finding.measured!!)
            val threshold = if (length) Dimensions.length(finding.threshold!!) else String.format(java.util.Locale.getDefault(), "%.2f", finding.threshold!!)
            Text(stringResource(R.string.sewout_measurement, measured, threshold), style = MaterialTheme.typography.bodySmall)
        }
        if (finding.code == FindingCode.READER_WARNING) Text(finding.message, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (located) R.string.sewout_show_location else R.string.sewout_global), style = MaterialTheme.typography.labelSmall,
            color = if (located) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CheckRow(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
    }
}
@Composable
private fun MachineFact(label: Int, value: String?) {
    Text(stringResource(R.string.sewout_fact, stringResource(label), value ?: stringResource(R.string.sewout_unknown)), Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
}
@Composable
private fun capability(value: Boolean?): String? = value?.let { stringResource(if (it) R.string.sewout_supported else R.string.sewout_unsupported) }
private fun SewoutReport.acceptanceRes() = when (compatibility.acceptance) {
    Acceptance.REJECTED -> R.string.sewout_acceptance_rejected
    Acceptance.UNVERIFIED -> R.string.sewout_acceptance_unverified
    Acceptance.WITHIN_CONFIRMED_LIMITS -> R.string.sewout_acceptance_confirmed
}
private fun TaskKind.labelRes() = when (this) {
    TaskKind.JUMP_THREAD_CUT, TaskKind.TRIM -> error("Thread-cut tasks are not displayed")
    TaskKind.COLOR_CHANGE -> R.string.sewout_task_color_change
    TaskKind.PROGRAM_STOP -> R.string.sewout_task_program_stop
    TaskKind.FINISH -> R.string.sewout_task_finish
}

@Composable
internal fun SewoutTransferConfirmation(report: SewoutReport, onCancel: () -> Unit, onReview: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.sewout_confirm_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sewout_confirm_body, report.machine.name, report.severityCounts[Severity.BLOCKER] ?: 0))
            if (!report.analysisComplete) Text(stringResource(R.string.sewout_confirm_incomplete))
        } },
        confirmButton = { TextButton(onClick = onContinue, enabled = !report.hasBlockingIssues) { Text(stringResource(R.string.sewout_transfer_anyway)) } },
        dismissButton = { Row {
            TextButton(onClick = onReview) { Text(stringResource(R.string.sewout_review)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.sewout_cancel)) }
        } })
}
