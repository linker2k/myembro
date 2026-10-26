// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.io.MyThreads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One request field + optional picture, shared by Home/editor/photo. Every send asks again. */
@Composable
fun AiPictureDialog(hoop: HoopSpec, targetWidthMm: Double, threads: MyThreads,
    initialPhoto: Bitmap? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf("") }
    var colors by remember { mutableIntStateOf(AiPicturePrompt.DEFAULT_COLORS) }
    var photo by remember { mutableStateOf(initialPhoto) }
    var ownedPhoto by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(Unit) { onDispose { ownedPhoto?.recycle() } }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var disclose by remember { mutableStateOf(false) }
    val voiceIntent = remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.ai_picture_request)) }
    val voiceAvailable = remember { voiceIntent.resolveActivity(context.packageManager) != null }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) request = listOf(request, heard).filter { it.isNotBlank() }.joinToString(" ")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = false
            try {
                val decoded = withContext(Dispatchers.IO) { decodeUprightPhoto(context.contentResolver, uri) }
                ownedPhoto?.recycle(); ownedPhoto = decoded; photo = decoded
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    fun share(includePhoto: Boolean) {
        disclose = false; busy = true; error = false
        scope.launch {
            try {
                val prompt = AiPicturePrompt.build(request, includePhoto, hoop, targetWidthMm, colors,
                    if (threads.active) threads.owned.map { it.color } else emptyList())
                val send = withContext(Dispatchers.IO) { aiPictureShareIntent(context, prompt, photo, includePhoto) }
                context.startActivity(Intent.createChooser(send, context.getString(R.string.ai_picture_chooser)))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.ai_picture_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_picture_hint))
            OutlinedTextField(request, { request = it }, label = { Text(stringResource(R.string.ai_picture_request)) },
                placeholder = { Text(stringResource(R.string.ai_picture_example)) }, trailingIcon = {
                    IconButton(onClick = { voice.launch(voiceIntent) }, enabled = voiceAvailable && !busy) {
                        Icon(Icons.Default.Mic, stringResource(R.string.ai_picture_voice))
                    }
                })
            Text(stringResource(R.string.ai_picture_colors, colors))
            Slider(colors.toFloat(), { colors = it.toInt() }, valueRange = 2f..6f, steps = 3, enabled = !busy)
            Text(stringResource(if (photo == null) R.string.ai_picture_no_photo else R.string.ai_picture_has_photo))
            TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) {
                Text(stringResource(R.string.ai_picture_pick))
            }
            if (photo != null) TextButton(onClick = { photo = null }, enabled = !busy) { Text(stringResource(R.string.ai_picture_remove)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error) Text(stringResource(R.string.ai_picture_error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = { disclose = true }, enabled = request.isNotBlank() && !busy) { Text(stringResource(R.string.ai_picture_share)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) } })
    if (disclose) AlertDialog(onDismissRequest = { disclose = false },
        title = { Text(stringResource(R.string.ai_picture_disclosure_title)) },
        text = { Text(stringResource(if (photo != null) R.string.ai_picture_disclosure_photo else R.string.ai_picture_disclosure_text)) },
        confirmButton = { if (photo != null) TextButton(onClick = { share(true) }) { Text(stringResource(R.string.ai_picture_send)) } },
        dismissButton = { TextButton(onClick = { share(false) }) { Text(stringResource(R.string.ai_picture_text_only)) } })
}
