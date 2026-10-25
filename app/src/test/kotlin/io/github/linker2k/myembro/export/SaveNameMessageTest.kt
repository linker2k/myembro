// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.export

import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.export.MachineProfile
import io.github.linker2k.myembro.engine.export.Preflight
import io.github.linker2k.myembro.engine.model.EmbPattern
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class SaveNameMessageTest {
    private val pattern = EmbPattern(emptyList(), emptyList())
    private val hangul = Regex("[\\u1100-\\u11ff\\u3130-\\u318f\\uac00-\\ud7af]")

    // Exercise the production resource-ID mapping and engine formatter using the shipped
    // XML strings. No emulator or mocked Android Resources implementation is needed.
    private fun messages(locale: Locale): Preflight.FileNameMessages {
        val folder = if (locale.language == "ko") "values-ko" else "values"
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/strings_export.xml"))
        val strings = document.getElementsByTagName("string")
        val byName = (0 until strings.length).associate { i ->
            val node = strings.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
        return ExportChecks.fileNameMessages { id, argument ->
            val key = when (id) {
                R.string.preflight_filename_characters -> "preflight_filename_characters"
                R.string.preflight_filename_too_long -> "preflight_filename_too_long"
                else -> error("Unexpected filename message resource: $id")
            }
            String.format(locale, byName.getValue(key), argument)
        }
    }

    @Test fun `English filename violation warnings contain zero Hangul characters`() {
        for (name in listOf("myembro logo_2.pes", "정민.pes", "logo?.pes")) {
            val warnings = Preflight.check(pattern, name, fileNameMessages = messages(Locale.ENGLISH))
            assertEquals(1, warnings.size)
            assertContains(warnings.single(), "Use only")
            assertContains(warnings.single(), "Brother SE400")
            assertEquals(0, hangul.findAll(warnings.single()).count())
        }
        val warning = Preflight.check(pattern, "abcd.pes", MachineProfile.SE400.copy(maxFileNameLength = 3),
            fileNameMessages = messages(Locale.ENGLISH)).single()
        assertEquals("The file name exceeds 3 characters.", warning)
        assertEquals(0, hangul.findAll(warning).count())
    }

    @Test fun `Korean uses translated resources and preserves machine and length arguments`() {
        val warnings = Preflight.check(pattern, "a b.pes", MachineProfile.SE400.copy(maxFileNameLength = 2),
            fileNameMessages = messages(Locale.KOREAN))
        assertEquals(2, warnings.size)
        assertContains(warnings[0], "파일명에는 영문")
        assertContains(warnings[0], "Brother SE400")
        assertEquals("파일명이 2자를 초과합니다.", warnings[1])
    }

    @Test fun `JVM default is English and valid names still have no warnings`() {
        val warning = Preflight.check(pattern, "myembro logo_2.pes").single()
        assertEquals(0, hangul.findAll(warning).count())
        for (locale in listOf(Locale.ENGLISH, Locale.KOREAN)) {
            assertTrue(Preflight.check(pattern, "myembro_logo_2.pes", fileNameMessages = messages(locale)).isEmpty())
        }
    }
}
