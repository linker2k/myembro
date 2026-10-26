// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import java.io.File
import kotlin.test.*

/**
 * Home button: one tap clears every screen above Home, and each screen leaves through its own back
 * path (unsaved-change dialog, draft) first. The screens are checked on the source, the stack directly;
 * the taps themselves are checked on the emulator.
 */
class HomeNavigationTest {
    private fun source(path: String) = File("src/main/kotlin/io/github/linker2k/myembro/$path").readText()

    @Test fun homeFromThreeLevelsDeepClearsTheStack() {
        val stack = mutableListOf("LIBRARY", "VIEWER", "EDITOR")
        assertEquals(listOf("LIBRARY", "VIEWER", "EDITOR"), stack.popToHome())
        assertEquals(emptyList(), stack)
        assertEquals(emptyList(), stack.popToHome())
    }

    @Test fun theActivityOffersHomeOnlyBelowTheFirstLevelAndRefreshesTheLibraryAfterAnEditor() {
        val main = source("MainActivity.kt")
        assertTrue("LocalGoHome provides goHome.takeIf { stack.size >= 2 }" in main)
        assertTrue("if (Screen.EDITOR in stack.popToHome()) library.refresh()" in main)
    }

    @Test fun everyHomeButtonLeavesThroughTheScreensOwnBackPath() {
        val editor = source("ui/editor/EditorScreen.kt")
        // The same vm.leave as the back arrow and the system back: unsaved dialog, save, discard, cancel.
        assertTrue("HomeButton(enabled, home) { goHome -> vm.leave(goHome) }" in editor)
        assertTrue("BackHandler(onBack = { vm.leave(onBack) })" in editor)
        // Lettering and fonts drawn inside the editor get no Home that could skip the editor's check.
        assertTrue("LocalGoHome provides null" in editor)
        val lettering = source("ui/lettering/LetteringScreen.kt")
        assertTrue("HomeButton { home -> requestExit(home) }" in lettering)
        assertTrue("val requestBack = { requestExit(onBack) }" in lettering)
        assertTrue("if (draftChanged) { discardTo = exit; confirmDiscard = true } else exit()" in lettering)
        assertTrue("HomeButton { home -> vm.cancel(); home() }" in source("ui/photo/PhotoScreen.kt"))
        val buttons = listOf("ui/ViewerScreen.kt", "ui/LibraryScreen.kt", "ui/emoji/EmojiScreen.kt",
            "ui/sources/FreeDesignSourcesScreen.kt", "ui/home/DesignPickerScreen.kt").filter { "HomeButton()" in source(it) }
        assertEquals(5, buttons.size, buttons.toString())
    }

    @Test fun aiPictureBelongsToCreationMenus() {
        val home = source("ui/home/HomeScreen.kt")
        val topBar = home.substringAfter("topBar = {").substringBefore("bottomBar =")
        assertFalse("R.string.ai_picture_title" in topBar)
        assertTrue("onAiPicture = onAiPicture" in home.substringAfter("if (showNewProject) NewProjectSheet("))
        val sheet = source("ui/home/NewProjectSheet.kt")
        assertTrue(sheet.indexOf("if (onFromPhoto != null)") < sheet.indexOf("if (onAiPicture != null)"))
        assertTrue(sheet.indexOf("if (onAiPicture != null)") < sheet.indexOf("if (onFromEmoji != null)"))
        val editor = source("ui/editor/EditorScreen.kt")
        assertFalse("R.string.ai_picture_title" in editor.substringAfter("DropdownMenu(projectMenu").substringBefore("if (showDesigns)"))
        assertTrue("AiPictureCreationChoice" in editor.substringAfter("if (showDesigns) ModalBottomSheet"))
    }

    @Test fun askSheetKeepsCopyAndShareOutsideTheScroll() {
        val sheet = source("ui/agent/AgentScreens.kt").substringAfter("private fun AskSheet").substringBefore("\n}\n")
        val scroll = sheet.indexOf("Column(Modifier.weight(1f, fill = false).verticalScroll(")
        val end = sheet.indexOf("R.string.agent_privacy")
        val copy = sheet.indexOf("R.string.agent_copy)"); val share = sheet.indexOf("R.string.agent_share)")
        assertTrue(scroll in 0 until end && end < copy && copy < share, sheet)
        assertTrue("rememberModalBottomSheetState(skipPartiallyExpanded = true)" in sheet)
        // The sheet's outer column (which holds the buttons) does not scroll.
        assertFalse(sheet.lines().first { "Column(Modifier.fillMaxWidth()" in it }.contains("verticalScroll"))
    }
}
