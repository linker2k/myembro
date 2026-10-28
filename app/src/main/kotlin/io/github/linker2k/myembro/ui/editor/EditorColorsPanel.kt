// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.MyThreads
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.RenderData
import io.github.linker2k.myembro.ui.StitchCanvas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// User-selectable common-fabric swatches only, no sewing effect or inferred material properties.
private val fabricPresets = listOf(
    R.string.fabric_white to 0xFFFFFFFF.toInt(), R.string.fabric_ivory to 0xFFFFF8E7.toInt(),
    R.string.fabric_black to 0xFF151515.toInt(), R.string.fabric_gray to 0xFF808080.toInt(),
    R.string.fabric_navy to 0xFF192B4D.toInt(), R.string.fabric_red to 0xFFAA2633.toInt(),
    R.string.fabric_denim to 0xFF426785.toInt(),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorColorsPanel(initial: Project, owned: MyThreads, sewing: SewingOptions,
    onApply: (Project) -> Boolean, onCancel: () -> Unit,
) {
    val history=remember(initial) { EditorColorsDraft(initial) }
    var draft by remember(initial) { mutableStateOf(initial) }
    var selected by remember { mutableStateOf<EmbThread?>(null) }
    var hex by remember { mutableStateOf("") }
    var conflict by remember { mutableStateOf(false) }
    val colors=MaterialTheme.colorScheme
    var previewFailed by remember { mutableStateOf(false) }
    val data by produceState<RenderData?>(null,draft,sewing) {
        previewFailed=false
        try { value=withContext(Dispatchers.Default) { RenderData(io.github.linker2k.myembro.deviceGeneration { draft.flatten(sewing) }) } }
        catch(e: CancellationException) { throw e }
        catch(_: LinkageError) { value=null;previewFailed=true }
        catch(_: Exception) { value=null;previewFailed=true }
    }
    val threads by produceState<List<EmbThread>>(emptyList(),draft) {
        try { value=withContext(Dispatchers.Default) { draft.usedThreads() } }
        catch(e: CancellationException) { throw e }
        catch(_: LinkageError) { value=emptyList() }
        catch(_: Exception) { value=emptyList() }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal=16.dp)) {
        Text(stringResource(R.string.editor_colors),style=MaterialTheme.typography.titleLarge)
        data?.let { render ->
            val shownFabric=render.pattern.fabricColorArgb?.let { "#%06X".format(it and 0xffffff) }
                ?: stringResource(R.string.editor_fabric_default)
            StitchCanvas(render,Int.MAX_VALUE,Hoop.H100,false,0,Modifier.fillMaxWidth().height(180.dp).testTag("colors-preview").semantics { stateDescription=shownFabric },
                colors.background.toArgb(),colors.outline.toArgb(),frame=draft.hoop,preservePlacement=true)
        } ?: Box(Modifier.fillMaxWidth().height(180.dp),contentAlignment=Alignment.Center) {
            if(previewFailed) Text(stringResource(R.string.editor_colors_preview_failed)) else CircularProgressIndicator()
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick={draft=history.undo()},enabled=history.canUndo,modifier=Modifier.testTag("colors-undo")) { Text(stringResource(R.string.editor_undo)) }
            TextButton(onClick={draft=history.redo()},enabled=history.canRedo,modifier=Modifier.testTag("colors-redo")) { Text(stringResource(R.string.editor_redo)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.editor_fabric_color),style=MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                fabricPresets.forEach { (label,color) ->
                    FilterChip(selected=draft.fabricColorArgb==color,onClick={draft=history.update(draft.copy(fabricColorArgb=color))},
                        label={Text(stringResource(label))},leadingIcon={ColorSwatch(color)},modifier=Modifier.testTag("fabric-$label"))
                }
                FilterChip(selected=draft.fabricColorArgb==null,onClick={draft=history.update(draft.copy(fabricColorArgb=null))},
                    label={Text(stringResource(R.string.editor_fabric_default))})
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hex,onValueChange={hex=it},label={Text(stringResource(R.string.editor_fabric_hex))},
                    singleLine=true,modifier=Modifier.weight(1f).testTag("fabric-hex"))
                TextButton(onClick={parseThreadHex(hex)?.let { draft=history.update(draft.copy(fabricColorArgb=it)) }},
                    enabled=parseThreadHex(hex)!=null,modifier=Modifier.testTag("fabric-custom")) { Text(stringResource(R.string.editor_fabric_custom)) }
            }
            Text(stringResource(R.string.editor_threads),style=MaterialTheme.typography.titleMedium)
            threads.forEachIndexed { index,thread ->
                ListItem(headlineContent={Text(thread.displayName)},supportingContent={Text(thread.displayDetails)},
                    leadingContent={ColorSwatch(thread.color)},trailingContent={Text("${index+1}")},
                    modifier=Modifier.clickable {selected=thread}.testTag("used-thread-$index"))
            }
            if(selected!=null) {
                Text(stringResource(R.string.editor_choose_thread),style=MaterialTheme.typography.titleSmall)
                if(owned.active) Text(stringResource(R.string.editor_owned_threads_first),style=MaterialTheme.typography.bodySmall)
                // Kept inline beneath the preview; changing a thread never opens a second screen.
                Column(Modifier.fillMaxWidth().height(200.dp).verticalScroll(rememberScrollState())) {
                    colorPanelThreads(owned).forEach { thread ->
                        ListItem(headlineContent={Text(thread.displayName)},supportingContent={Text(thread.displayDetails)},
                            leadingContent={ColorSwatch(thread.color)},modifier=Modifier.clickable {
                                draft=history.update(draft.recolorThread(requireNotNull(selected),thread));selected=null
                            }.testTag("brother-thread-${thread.catalogNumber}"))
                    }
                }
            }
        }
        if(conflict) Text(stringResource(R.string.editor_colors_conflict),color=colors.error)
        Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.End) {
            TextButton(onClick=onCancel,modifier=Modifier.testTag("colors-cancel")) { Text(stringResource(R.string.editor_colors_cancel)) }
            Button(onClick={conflict=!onApply(draft)},modifier=Modifier.testTag("colors-apply")) { Text(stringResource(R.string.editor_colors_apply)) }
        }
    }
}

@Composable
private fun ColorSwatch(argb: Int) {
    Box(Modifier.size(22.dp).background(Color(argb),MaterialTheme.shapes.small))
}
