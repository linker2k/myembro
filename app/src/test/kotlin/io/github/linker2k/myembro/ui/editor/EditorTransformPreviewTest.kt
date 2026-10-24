// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import kotlin.test.*

class EditorTransformPreviewTest {
    @Test fun gestureTransformsPreserveTextPatternAndOneHistoryStep() {
        val obj=TextObject("text",text="AB",heightMm=10.0)
        val pattern=obj.localPattern()
        val session=EditorSession(Project(objects=listOf(obj)))
        session.beginGesture()
        repeat(60) {
            val next=session.project.transformGroup(setOf("text"),.3,.2,1.001,.1,fastPreview=true)
            session.updateGesture(next)
            assertNotNull(next.find("text")!!.previewBounds())
            assertSame(pattern,next.find("text")!!.localPattern())
            assertEquals(0,session.history.undoCount)
        }
        session.endGesture()
        assertEquals(1,session.history.undoCount)
        session.undo()
        assertEquals(obj,session.project.objects.single())
        assertSame(pattern,session.project.objects.single().localPattern())
    }
}
