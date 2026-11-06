// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepWordsTest {
    @Test fun joinsInsideWordsThatHaveHangul() {
        assertEquals("USB⁠로 전⁠송", keepWords("USB로 전송"))
        assertEquals("수⁠정", keepWords("수정"))
    }

    @Test fun leavesTextWithoutHangulUnchanged() {
        assertEquals("Send to USB", keepWords("Send to USB"))
    }
}
