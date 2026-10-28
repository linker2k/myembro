// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.MyThreads
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.library.Thumbnails
import io.github.linker2k.myembro.ui.RenderData
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlin.test.*

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="en-rUS-w480dp-h1000dp")
class EditorColorsPanelUiTest {
    @get:Rule val compose=createComposeRule()
    private val initial=Project(objects=listOf(StitchObject("s","Synthetic",EmbPattern(listOf(
        Stitch(0,0,Command.STITCH),Stitch(30,20,Command.STITCH),Stitch(30,20,Command.END)),listOf(EmbThread(0xffff0000.toInt()))))))

    @Test fun customFabricPreviewUndoRedoAndApply() {
        var result:Project?=null
        compose.setContent { MyembroTheme { EditorColorsPanel(initial,MyThreads(),SewingOptions(),{result=it;true},{}) } }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("colors-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("fabric-hex").performScrollTo().performTextInput("#234567")
        compose.onNodeWithTag("fabric-custom").performClick()
        compose.onNodeWithTag("colors-undo").assertIsEnabled().performClick()
        compose.onNodeWithTag("colors-redo").assertIsEnabled().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("colors-preview")
            .filter(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"#234567")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("colors-preview").assertIsDisplayed()
        compose.onNodeWithTag("colors-apply").performClick()
        assertEquals(0xff234567.toInt(),result?.fabricColorArgb)
        val bitmap=Thumbnails.render(RenderData(requireNotNull(result).flatten()),128)
        assertEquals(0xff234567.toInt(),bitmap.getPixel(0,0))
        assertNull(initial.fabricColorArgb)
    }

    @Test fun cancelDiscardsChangesAndBrotherSelectionChangesTheLivePreview() {
        var canceled=false
        var result:Project?=null
        compose.setContent { MyembroTheme { EditorColorsPanel(initial,MyThreads(true,setOf(5)),SewingOptions(),{result=it;true},{canceled=true}) } }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("used-thread-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("used-thread-0").performScrollTo().performClick()
        // Owned Brother #5 is first and remains in the same panel beneath the preview.
        val target=io.github.linker2k.myembro.engine.io.PecThreads.forIndex(5)
        compose.onNodeWithTag("brother-thread-${target.catalogNumber}").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("used-thread-0")
            .filter(hasText(target.displayName,substring=true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("colors-undo").assertIsEnabled()
        compose.onNodeWithTag("colors-cancel").performClick()
        assertTrue(canceled);assertNull(result)
        assertEquals(0xffff0000.toInt(),initial.usedThreads().first().color)
    }
}
