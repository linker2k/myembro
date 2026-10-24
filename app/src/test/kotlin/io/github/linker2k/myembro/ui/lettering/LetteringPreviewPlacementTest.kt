// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering
import io.github.linker2k.myembro.engine.edit.*
import kotlin.test.*
class LetteringPreviewPlacementTest {
    @Test fun editingUsesEditorPositionRotationAndScale() {
        val original=TextObject("existing",text="A",transform=Transform(230.0,-180.0,15.0,1.3,0.8))
        val generated=TextObject("draft",text="AB",heightMm=12.0)
        val preview=letteringPreviewObject(generated,original)
        assertEquals(original.transform,preview.transform)
        assertEquals(generated.text,preview.text)
        assertEquals(generated.copy(transform=original.transform).placedPattern().stitches,preview.placedPattern().stitches)
    }
    @Test fun newTextStartsAtHoopCenter() {
        val generated=TextObject("new",text="A",transform=Transform(x=200.0))
        assertEquals(Transform(),letteringPreviewObject(generated,null).transform)
    }
    @Test fun draftTracksEveryEditableFieldAndRestoringOriginalIsClean() {
        val original=LetteringDraftSnapshot("A","font","12.0",0)
        assertNotEquals(original,original.copy(text="B"))
        assertNotEquals(original,original.copy(fontId="other"))
        assertNotEquals(original,original.copy(height="14.0"))
        assertNotEquals(original,original.copy(color=1))
        assertEquals(original,original.copy(text="B").copy(text="A"))
    }
}
