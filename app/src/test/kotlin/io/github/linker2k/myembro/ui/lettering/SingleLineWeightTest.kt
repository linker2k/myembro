// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.lettering.StitchStyle
import kotlin.test.*

class SingleLineWeightTest {
    @Test fun normalIsDefaultAndBoldOnlyChangesSingleLineRunning() {
        val state=AutomaticLetteringState("Script",StitchStyle.RUNNING,emptyList(),singleLineWeightEnabled=true)
        assertEquals(StitchStyle.RUNNING,letteringStyleForWeight(state,false))
        assertEquals(StitchStyle.BEAN,letteringStyleForWeight(state,true))
        assertEquals(StitchStyle.RUNNING,letteringStyleForWeight(state.copy(singleLineWeightEnabled=false),true))
        assertEquals(StitchStyle.SATIN,letteringStyleForWeight(state.copy(style=StitchStyle.SATIN),true))
    }
    @Test fun digitizedAndBeanFontsNeverGetASecondBeanLayer() {
        assertTrue(singleLineWeightEnabled(true,false,"hershey-script-1"))
        assertFalse(singleLineWeightEnabled(true,true,"inkstitch-sacramarif"))
        assertFalse(singleLineWeightEnabled(true,false,"digory_doodles_bean"))
        assertFalse(singleLineWeightEnabled(false,false,"outline"))
    }
    @Test fun thicknessOnlyEditMustAskBeforeDiscard() {
        val draft=LetteringDraftSnapshot("Yeonwoo","font","10",0)
        assertNotEquals(draft,draft.copy(singleLineBold=true))
    }
}
