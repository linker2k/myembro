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
internal fun AgentAssistHost(controller: AgentAssistController, project: Project) {
    // The sample layout covers the result; closing it returns there.
    controller.samplePreview?.let { PreviewScreen(it, onDismiss = { controller.samplePreview = null }); return }
    when (controller.stage) {
        AgentAssistController.Stage.ASK -> AskSheet(controller)
        AgentAssistController.Stage.REPLY -> ReplySheet(controller, project)
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
    val prompt = controller.prompt
    ModalBottomSheet(onDismissRequest = controller::close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.agent_ask), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.Top) {
                OutlinedTextField(controller.request, { controller.request = it }, Modifier.weight(1f), minLines = 3,
                    placeholder = { Text(stringResource(R.string.agent_request_hint)) })
                if (voiceAvailable) IconButton(onClick = { voice.launch(voiceIntent) }) {
                    Icon(Icons.Default.Mic, stringResource(R.string.agent_voice))
                }
            }
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
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, prompt!!.text)
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
private fun ReplySheet(controller: AgentAssistController, project: Project) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = controller::close) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.agent_reply_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(controller.reply, { controller.reply = it }, Modifier.fillMaxWidth().heightIn(max = 260.dp), minLines = 5,
                placeholder = { Text(stringResource(R.string.agent_reply_hint)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Read only on this tap, never in the background.
                OutlinedButton(onClick = { paste(context)?.let { controller.reply = it } }) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.agent_paste))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { scope.launch { controller.check(project) } }, enabled = controller.reply.isNotBlank()) {
                    Text(stringResource(R.string.agent_check))
                }
            }
        }
    }
}

@Composable
private fun ResultDialog(controller: AgentAssistController) {
    val context = LocalContext.current
    val outcome = controller.outcome ?: return
    val problems = when (outcome) {
        is AgentAssistController.Outcome.Unreadable -> problemTexts(outcome.result)
        is AgentAssistController.Outcome.OtherPrompt -> listOf(stringResource(R.string.agent_other_prompt, outcome.promptId))
        is AgentAssistController.Outcome.Read -> (outcome.result as? AgentPreviewResult.Rejected)?.problems.orEmpty()
    }
    val read = outcome as? AgentAssistController.Outcome.Read
    (read?.result as? AgentPreviewResult.Ready)?.let { ready ->
        PreviewScreen(ready.preview, onDismiss = controller::close)
        return
    }
    AlertDialog(onDismissRequest = controller::close,
        icon = { Icon(if (problems.isEmpty()) Icons.Default.Info else Icons.Default.ErrorOutline, null) },
        title = { Text(stringResource(when {
            problems.isNotEmpty() && read != null -> R.string.agent_rejected_title
            problems.isNotEmpty() -> R.string.agent_unreadable_title
            else -> R.string.agent_read_title
        })) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                problems.forEach { Text("✕ $it", style = MaterialTheme.typography.bodyMedium) }
                if (read != null && problems.isEmpty()) {
                    Text(pluralStringResource(R.plurals.agent_read_count, read.script.commands.size, read.script.commands.size), style = MaterialTheme.typography.bodyMedium)
                    if (read.script.summary.isNotBlank()) Text("“${read.script.summary}”", style = MaterialTheme.typography.bodySmall)
                    read.script.commands.forEachIndexed { i, c ->
                        Text("${i + 1}. ${c.tool}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            color = if (c.tool in AgentTools.NAMES || c.tool in AgentTools.FOLLOW_UPS) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    }
                    if (read.result == AgentPreviewResult.NotConnected) Text(stringResource(R.string.agent_not_connected),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (BuildConfig.DEBUG) TextButton(onClick = { controller.samplePreview = AgentPreviewSample.of(read.script) },
                        contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.agent_sample)) }
                } else Text(stringResource(R.string.agent_nothing_changed), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = controller::close) { Text(stringResource(R.string.agent_close)) } },
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

/** Before/after, changes, sewout check and confirmations; Apply is one undo step (disabled for samples). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(preview: AgentPreview, onDismiss: () -> Unit, onApply: () -> Unit = {}, onAskChanges: () -> Unit = {}) {
    var removeTicked by remember(preview) { mutableStateOf(false) }
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
                        Button(onClick = onApply, enabled = !preview.sample && (preview.removals.isEmpty() || removeTicked)) {
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
                Text("“${preview.summary}”", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PreviewPane(stringResource(R.string.agent_before), Modifier.weight(1f))
                    Icon(Icons.Default.ArrowForward, null)
                    PreviewPane(stringResource(R.string.agent_after), Modifier.weight(1f))
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
                        Text(change.text, style = MaterialTheme.typography.bodyMedium)
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
                if (preview.removals.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(removeTicked, { removeTicked = it })
                    Text(stringResource(R.string.agent_remove_confirm, preview.removals.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
                }
                if (preview.followUps.isNotEmpty()) Text(stringResource(R.string.agent_followups,
                    preview.followUps.map { followUpLabel(it) }.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Placeholder pane; the dispatcher supplies before/after projects drawn by the editor renderer. */
@Composable
private fun PreviewPane(label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedCard(Modifier.fillMaxWidth().aspectRatio(1f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CropFree, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
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
