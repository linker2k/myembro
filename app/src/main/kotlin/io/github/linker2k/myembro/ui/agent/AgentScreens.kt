// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.linker2k.myembro.BuildConfig
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.Project
import kotlinx.coroutines.launch
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import io.github.linker2k.myembro.ui.RenderData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest

/** Top-bar entry point of "Ask AI". */
@Composable
internal fun AgentAskButton(controller: AgentAssistController, project: Project, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    IconButton(onClick = { scope.launch { controller.openAsk(project) } }, enabled = enabled) {
        Icon(Icons.Default.AutoAwesome, stringResource(R.string.agent_ask))
    }
}

/** Card under the editor top bar while a prompt waits for its reply. */
@Composable
internal fun AgentPendingCard(controller: AgentAssistController) {
    val pending = controller.pending ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.agent_waiting, pending.promptId), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = controller::cancelPending) { Text(stringResource(R.string.agent_cancel)) }
            FilledTonalButton(onClick = { controller.openReply() }) { Text(stringResource(R.string.agent_paste_reply)) }
        }
    }
}

/** Sheets and dialogs of the flow; drawn by the editor over its content. */
@Composable
internal fun AgentAssistHost(controller: AgentAssistController) {
    // The sample layout covers the result; closing it returns there.
    controller.samplePreview?.let { PreviewScreen(it, applying = false, onDismiss = { controller.samplePreview = null }); return }
    when (controller.stage) {
        AgentAssistController.Stage.ASK -> AskSheet(controller)
        AgentAssistController.Stage.REPLY -> ReplySheet(controller)
        AgentAssistController.Stage.RESULT -> ResultDialog(controller)
        AgentAssistController.Stage.CLOSED -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskSheet(controller: AgentAssistController) {
    val context = LocalContext.current
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.agent_voice_prompt))
    }
    val voiceAvailable = remember { voiceIntent.resolveActivity(context.packageManager) != null }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank())
            controller.request = listOf(controller.request.trim(), heard).filter { it.isNotEmpty() }.joinToString(" ")
    }
    var showPrompt by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The same system photo picker as the photo screen; only the picked item is readable, and only here.
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val size = withContext(Dispatchers.IO) { uprightSize(context, uri) }
            if (size == null) Toast.makeText(context, R.string.agent_photo_unreadable, Toast.LENGTH_SHORT).show()
            else controller.attach(uri.toString(), size.first, size.second)
        }
    }
    val prompt = controller.prompt
    // Fully expanded: a half-open sheet would leave the pinned buttons below a small screen's edge.
    ModalBottomSheet(onDismissRequest = controller::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Only the content scrolls; Copy and Share stay at the bottom however long it gets (photos, prompt).
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.agent_ask), style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.Top) {
                    OutlinedTextField(controller.request, { controller.request = it }, Modifier.weight(1f), minLines = 3,
                        placeholder = { Text(stringResource(R.string.agent_request_hint)) })
                    if (voiceAvailable) IconButton(onClick = { voice.launch(voiceIntent) }) {
                        Icon(Icons.Default.Mic, stringResource(R.string.agent_voice))
                    }
                }
                controller.attachments.forEach { a ->
                    InputChip(selected = false, onClick = { controller.detach(a.id) },
                        label = { Text(stringResource(R.string.agent_photo_chip, a.id, a.widthPx, a.heightPx)) },
                        leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(18.dp)) },
                        trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.agent_photo_remove), Modifier.size(18.dp)) })
                }
                OutlinedButton(onClick = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.agent_attach_photo))
                }
                if (controller.attachments.isNotEmpty()) Text(stringResource(R.string.agent_photo_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val summary = controller.summary
                if (summary == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.agent_preparing), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    TextButton(onClick = { showPrompt = !showPrompt }, contentPadding = PaddingValues(0.dp)) {
                        Icon(if (showPrompt) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        Text(pluralStringResource(R.plurals.agent_what_is_sent, summary.objects.size, summary.objects.size))
                    }
                    if (showPrompt && prompt != null) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        SelectionContainer {
                            Text(prompt.text, Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
                Text(stringResource(R.string.agent_privacy), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val ready = prompt != null && controller.request.isNotBlank()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    copy(context, prompt!!.text)
                    Toast.makeText(context, R.string.agent_copied, Toast.LENGTH_SHORT).show()
                    controller.sent()
                }, enabled = ready) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.agent_copy))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    // Text only: an attached photo is never added to the share (AgentShare).
                    val send = Intent(Intent.ACTION_SEND).setType(AgentShare.MIME_TYPE)
                    AgentShare.extras(prompt!!).forEach { (key, value) -> send.putExtra(key, value) }
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.agent_share_chooser)))
                    controller.sent()
                }, enabled = ready) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.agent_share))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplySheet(controller: AgentAssistController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = controller::close) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.agent_reply_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(controller.reply, { controller.reply = it }, Modifier.fillMaxWidth().heightIn(max = 260.dp), minLines = 5,
                placeholder = { Text(stringResource(R.string.agent_reply_hint)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Read only on this tap, never in the background.
                OutlinedButton(onClick = { paste(context)?.let { controller.reply = it } }) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.agent_paste))
                }
                Spacer(Modifier.weight(1f))
                if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Button(onClick = { checking = true; scope.launch { try { controller.check() } finally { checking = false } } },
                    enabled = controller.reply.isNotBlank() && !checking) {
                    Text(stringResource(R.string.agent_check))
                }
            }
        }
    }
}

@Composable
private fun ResultDialog(controller: AgentAssistController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outcome = controller.outcome ?: return
    val read = outcome as? AgentAssistController.Outcome.Read
    (read?.result as? AgentPreviewResult.Ready)?.let { ready ->
        PreviewScreen(ready.preview, applying = controller.applying, onDismiss = controller::close,
            onApply = { ticked -> scope.launch { controller.apply(ticked) } },
            onAskChanges = { controller.close(); controller.openReply() })
        return
    }
    val problems = when (outcome) {
        is AgentAssistController.Outcome.Unreadable -> problemTexts(outcome.result)
        is AgentAssistController.Outcome.OtherPrompt -> listOf(stringResource(R.string.agent_other_prompt, outcome.promptId))
        is AgentAssistController.Outcome.Read -> (outcome.result as? AgentPreviewResult.Rejected)?.problems.orEmpty().map { problemText(it) }
        is AgentAssistController.Outcome.ApplyFailed -> outcome.problems.map { problemText(it) }
        is AgentAssistController.Outcome.Applied -> emptyList()
    }
    val applied = outcome as? AgentAssistController.Outcome.Applied
    AlertDialog(onDismissRequest = controller::close,
        icon = { Icon(when { applied != null -> Icons.Default.CheckCircle; problems.isEmpty() -> Icons.Default.Info
            else -> Icons.Default.ErrorOutline }, null) },
        title = { Text(stringResource(when {
            applied != null -> R.string.agent_applied_title
            problems.isNotEmpty() && outcome !is AgentAssistController.Outcome.Unreadable &&
                outcome !is AgentAssistController.Outcome.OtherPrompt -> R.string.agent_rejected_title
            problems.isNotEmpty() -> R.string.agent_unreadable_title
            else -> R.string.agent_read_title
        })) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                problems.forEach { Text("✕ $it", style = MaterialTheme.typography.bodyMedium) }
                if (applied != null) {
                    Text(stringResource(R.string.agent_applied_body), style = MaterialTheme.typography.bodyMedium)
                } else if (read != null && problems.isEmpty()) {
                    Text(pluralStringResource(R.plurals.agent_read_count, read.script.commands.size, read.script.commands.size), style = MaterialTheme.typography.bodyMedium)
                    read.script.commands.forEachIndexed { i, c -> Text("${i + 1}. ${c.tool}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    if (read.result == AgentPreviewResult.NotConnected) Text(stringResource(R.string.agent_not_connected),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text(stringResource(R.string.agent_nothing_changed), style = MaterialTheme.typography.bodySmall)
                if (BuildConfig.DEBUG && read != null) TextButton(onClick = { controller.samplePreview = AgentPreviewSample.of(read.script) },
                    contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.agent_sample)) }
            }
        },
        confirmButton = {
            if (outcome is AgentAssistController.Outcome.ApplyFailed)
                TextButton(onClick = controller::backToPreview) { Text(stringResource(R.string.agent_back_to_preview)) }
            else TextButton(onClick = controller::close) { Text(stringResource(R.string.agent_close)) }
        },
        dismissButton = if (problems.isEmpty()) null else ({
            val fix = AgentPromptBuilder.repair(controller.pending?.promptId ?: "-", problems)
            TextButton(onClick = {
                copy(context, fix)
                Toast.makeText(context, R.string.agent_copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.agent_copy_fix)) }
        }))
}

@Composable
private fun problemTexts(result: AgentParseResult): List<String> = when (result) {
    is AgentParseResult.Parsed -> emptyList()
    is AgentParseResult.Broken -> listOf(
        if (result.line != null && result.column != null) stringResource(R.string.agent_err_broken, result.line, result.column)
        else stringResource(R.string.agent_err_broken_nopos)) + listOfNotNull(result.excerpt.takeIf { it.isNotBlank() }?.trim())
    is AgentParseResult.Invalid -> result.problems.map { p ->
        when (p.code) {
            AgentScriptProblem.Code.NO_SCRIPT -> stringResource(R.string.agent_err_no_script)
            AgentScriptProblem.Code.MULTIPLE_SCRIPTS -> stringResource(R.string.agent_err_multiple, p.detail)
            AgentScriptProblem.Code.BROKEN_JSON -> stringResource(R.string.agent_err_broken_nopos)
            AgentScriptProblem.Code.UNSUPPORTED_VERSION -> stringResource(R.string.agent_err_version, p.detail)
            AgentScriptProblem.Code.NOT_AN_OBJECT -> stringResource(R.string.agent_err_not_object)
            AgentScriptProblem.Code.MISSING_FIELD -> stringResource(R.string.agent_err_missing, p.path)
            AgentScriptProblem.Code.UNKNOWN_FIELD -> stringResource(R.string.agent_err_unknown, p.path)
            AgentScriptProblem.Code.WRONG_TYPE -> stringResource(R.string.agent_err_type, p.path)
            AgentScriptProblem.Code.NO_COMMANDS -> stringResource(R.string.agent_err_no_commands)
        }
    }
}

/** Dispatcher codes in plain words; the dispatcher's own message follows for the repair prompt. */
@Composable
private fun problemText(p: AgentProblem): String {
    val reason = when (p.code) {
        "NOT_IMPLEMENTED" -> stringResource(R.string.agent_code_not_implemented)
        "UNKNOWN_TOOL" -> stringResource(R.string.agent_code_unknown_tool)
        "INVALID_ARGUMENTS" -> stringResource(R.string.agent_code_invalid_arguments)
        "UNKNOWN_HANDLE" -> stringResource(R.string.agent_code_unknown_handle, p.message)
        "REVISION_MISMATCH", "STALE_REVISION" -> stringResource(R.string.agent_code_stale)
        "CONFIRMATION_REQUIRED" -> stringResource(R.string.agent_code_confirmation)
        "INVALID_APPROVAL" -> stringResource(R.string.agent_code_invalid_approval)
        "SCRIPT_TOOL_FORBIDDEN" -> stringResource(R.string.agent_code_separate)
        "SEWOUT_BLOCKED" -> stringResource(R.string.agent_code_sewout_blocked)
        "MACHINE_UNKNOWN" -> stringResource(R.string.agent_code_machine_unknown)
        "GESTURE_IN_PROGRESS", "TURN_IN_PROGRESS" -> stringResource(R.string.agent_code_busy)
        else -> stringResource(R.string.agent_code_other)
    }
    val detail = p.message.takeIf { it.isNotBlank() && p.code != "UNKNOWN_HANDLE" && it != p.tool }?.let { " ($it)" }.orEmpty()
    return if (p.index != null) stringResource(R.string.agent_problem_at, p.index + 1, p.tool.orEmpty(), reason) + detail else reason + detail
}

/** Before/after, changes, sewout check and confirmations; Apply is one undo step (disabled for samples and blocked results). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(preview: AgentPreview, applying: Boolean, onDismiss: () -> Unit,
    onApply: (approvedRemovals: Set<Int>) -> Unit = {}, onAskChanges: () -> Unit = {}) {
    // Every removal starts unticked; Apply sends approvals for ticked ones only (the engine refuses the rest).
    var ticked by remember(preview) { mutableStateOf(emptySet<Int>()) }
    // A full-screen layer over the editor (like the font library over lettering), not a Dialog window,
    // so the activity's edge-to-edge insets apply unchanged.
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.agent_preview_title)) }, navigationIcon = {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.agent_close)) }
            })
        }, bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.agent_apply_hint), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onAskChanges) { Text(stringResource(R.string.agent_ask_changes)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) }
                        if (applying) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        // Unticked removals do not disable Apply: the engine refuses the whole script and says why.
                        Button(onClick = { onApply(ticked) }, enabled = !applying && !preview.sample && preview.blockingFindings == null &&
                            preview.changes.isNotEmpty() && preview.separate.isEmpty()) {
                            Text(stringResource(R.string.agent_apply))
                        }
                    }
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (preview.sample) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.agent_sample_banner), Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (preview.summary.isNotBlank()) Text("“${preview.summary}”", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PreviewPane(stringResource(R.string.agent_before), preview.before, Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    PreviewPane(stringResource(R.string.agent_after), preview.after, Modifier.weight(1f))
                }
                Text(stringResource(R.string.agent_changes), style = MaterialTheme.typography.titleSmall)
                preview.changes.forEach { change ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (change.kind) {
                            AgentChange.Kind.ADD -> Icons.Default.Add
                            AgentChange.Kind.CHANGE -> Icons.Default.Edit
                            AgentChange.Kind.REMOVE -> Icons.Default.Delete
                        }, null, Modifier.size(18.dp), tint = if (change.kind == AgentChange.Kind.REMOVE) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(changeText(change), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium)
                        change.detail.lastOrNull()?.takeIf { change.tool == "recolor_object" }?.let(::parseColor)?.let { color ->
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(16.dp).background(color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                        }
                    }
                }
                val blocked = preview.blockingFindings
                Surface(color = if (blocked == null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (blocked == null) Icons.Default.CheckCircle else Icons.Default.Block, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.agent_sewout) + " · " +
                                stringResource(if (blocked == null) R.string.agent_sewout_ready else R.string.agent_sewout_blocked),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        blocked?.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                preview.removals.forEach { removal ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(removal.callIndex in ticked, { on -> ticked = if (on) ticked + removal.callIndex else ticked - removal.callIndex })
                        Text(stringResource(R.string.agent_remove_confirm, removal.names.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (preview.removals.isNotEmpty()) Text(stringResource(R.string.agent_remove_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (preview.separate.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Block, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.agent_separate_title), style = MaterialTheme.typography.bodyMedium)
                        }
                        preview.separate.forEach { Text("• " + followUpLabel(it), style = MaterialTheme.typography.bodySmall) }
                        Text(stringResource(R.string.agent_separate_body), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun changeText(change: AgentChange): String {
    val names = change.objects.joinToString(", ").ifEmpty { "—" }
    val d = change.detail
    fun at(i: Int) = d.getOrElse(i) { "" }
    return when (change.tool) {
        "set_object_transforms" -> stringResource(R.string.agent_change_transform, names, at(0), at(1), at(2), at(3), at(4))
        "transform_objects" -> stringResource(R.string.agent_change_group, names, at(0), at(1), at(2), at(3))
        "align_objects" -> stringResource(R.string.agent_change_align, names, when (at(0)) {
            "LEFT" -> stringResource(R.string.agent_align_left); "CENTER_X" -> stringResource(R.string.agent_align_center_x)
            "RIGHT" -> stringResource(R.string.agent_align_right); "TOP" -> stringResource(R.string.agent_align_top)
            "CENTER_Y" -> stringResource(R.string.agent_align_center_y); "BOTTOM" -> stringResource(R.string.agent_align_bottom)
            else -> at(0) })
        "distribute_objects" -> stringResource(R.string.agent_change_distribute, names,
            stringResource(if (at(0) == "VERTICAL") R.string.agent_axis_vertical else R.string.agent_axis_horizontal))
        "mirror_objects" -> stringResource(R.string.agent_change_mirror, names)
        "center_objects" -> stringResource(R.string.agent_change_center, names)
        "remove_objects" -> stringResource(R.string.agent_change_remove, names)
        "duplicate_object" -> stringResource(R.string.agent_change_duplicate, names, at(0))
        "reorder_object" -> stringResource(R.string.agent_change_reorder, names, at(0))
        "set_object_visibility" -> stringResource(if (at(0) == "true") R.string.agent_change_show else R.string.agent_change_hide, names)
        "recolor_object" -> stringResource(R.string.agent_change_recolor, names, at(0), at(1))
        "set_project_frame" -> stringResource(R.string.agent_change_frame, at(0))
        "add_text" -> stringResource(R.string.agent_change_add_text, at(0), at(1))
        "replace_text" -> stringResource(R.string.agent_change_replace_text, names, at(0), at(1))
        "add_design", "add_generated_art" -> stringResource(R.string.agent_change_add_design, at(0).ifEmpty { names })
        else -> stringResource(R.string.agent_change_other, change.tool, names)
    }
}

private fun parseColor(hex: String): Color? =
    hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

/** The project as sewn inside its hoop, drawn like the editor's layer thumbnails. */
@Composable
private fun PreviewPane(label: String, project: Project?, modifier: Modifier) {
    val renders by produceState<List<RenderData>?>(null, project) {
        value = project?.let { p -> withContext(Dispatchers.Default) {
            p.objects.filter { it.visible }.mapNotNull { runCatching { RenderData(it.placedPattern()) }.getOrNull() }
        } }
    }
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    val hoopColor = MaterialTheme.colorScheme.outline
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedCard(Modifier.fillMaxWidth().aspectRatio(1f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            val data = renders
            if (project == null || data == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (project == null) Icon(Icons.Default.CropFree, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
                else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else Canvas(Modifier.fillMaxSize()) {
                drawRect(background)
                drawIntoCanvas { c ->
                    drawProjectPreview(c.nativeCanvas, size.width, size.height, project.hoop, data, hoopColor.toArgb(), paint)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun followUpLabel(tool: String) = when (tool) {
    "send_usb" -> stringResource(R.string.agent_follow_send_usb)
    "save_pes" -> stringResource(R.string.agent_follow_save_pes)
    "save_project" -> stringResource(R.string.agent_follow_save_project)
    "share_pes" -> stringResource(R.string.agent_follow_share_pes)
    else -> tool
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("myembro", text))
}

private fun paste(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString()

/** Width × height in pixels as the photo is shown (EXIF rotation applied), from the header only. */
private fun uprightSize(context: Context, uri: Uri): Pair<Int, Int>? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val orientation = runCatching { resolver.openInputStream(uri).use { stream ->
        ExifInterface(requireNotNull(stream)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
    } }.getOrDefault(1)
    val turned = orientation in setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
    if (turned) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
}.getOrNull()
