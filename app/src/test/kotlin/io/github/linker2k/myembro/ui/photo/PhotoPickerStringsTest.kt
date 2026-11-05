// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class PhotoPickerStringsTest {
    private fun strings(locale: String): Map<String, String> {
        val relative = "src/main/res/$locale/strings_photo.xml"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    @Test fun `photo screen strings come in English and Korean pairs with short picker labels`() {
        val en = strings("values")
        val ko = strings("values-ko")
        assertEquals(en.keys, ko.keys)
        assertEquals("Photos", en["photo_pick_photos"])
        assertEquals("Files", en["photo_pick_file"])
        assertEquals("사진", ko["photo_pick_photos"])
        assertEquals("파일", ko["photo_pick_file"])
        assertFalse("photo_pick_again" in en)
    }
}
