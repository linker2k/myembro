// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** A tap on a step must commit the tapped step, as a drag does (field bug 2026-11-01: taps kept the old value). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="en-rUS-w480dp-h1000dp")
class StepSliderTapTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val steps=listOf(128,181,256,362,512,724,1024)
    private val committed=mutableListOf<Int>()

    private fun show() = compose.setContent { MyembroTheme { StepSlider(steps,1024,true,{ "res $it" }) { committed+=it } } }

    @Test fun tapCommitsTheTappedStep() {
        show()
        compose.onNodeWithContentDescription("res 1024").performTouchInput { click(Offset(width*0.06f,centerY)) }
        compose.waitForIdle()
        assertEquals(listOf(128),committed)
    }

    @Test fun dragCommitsTheReleasedStep() {
        show()
        compose.onNodeWithContentDescription("res 1024").performTouchInput { swipe(Offset(width*0.94f,centerY),Offset(width*0.06f,centerY),300) }
        compose.waitForIdle()
        assertEquals(128,committed.last())
    }

    /** Accessibility "set progress" also changes and finishes in one go, like a tap. */
    @Test fun setProgressCommitsTheNewStep() {
        show()
        compose.onNodeWithContentDescription("res 1024").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(2f) }
        compose.waitForIdle()
        assertEquals(listOf(256),committed)
    }
}
