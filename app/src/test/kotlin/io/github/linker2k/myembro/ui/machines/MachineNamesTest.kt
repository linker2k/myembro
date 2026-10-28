// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.machines

import io.github.linker2k.myembro.engine.sewout.MachineRegion
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class MachineNamesTest {
    private fun names(locale: String): Map<String, String> {
        val relative = "src/main/res/$locale/strings_machine_names.xml"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test fun `every Korean model has English and unchanged Korean labels and both resolve`() {
        val en = names("values")
        val ko = names("values-ko")
        val korean = MachineProfiles.all.filter { it.region == MachineRegion.KOREA }
        assertEquals(korean.size, en.size)
        assertEquals(en.keys, ko.keys)
        for (profile in korean) {
            val key = "machine_name_${profile.id.replace('-', '_')}"
            val english = en.getValue(key)
            val koreanName = ko.getValue(key)
            assertNotNull(machineNameResource(profile.id))
            assertEquals(profile.displayName, koreanName)
            assertFalse(english.any { it in '\uAC00'..'\uD7A3' }, english)
            assertEquals(profile.id, MachineProfiles.find(english)?.id)
            assertEquals(profile.id, MachineProfiles.find(koreanName)?.id)
            assertEquals(profile.id, EmbroiderySettings(machineModel = koreanName).machineProfile?.id)
            assertEquals(profile.id, EmbroiderySettings(machineModel = english).machineProfile?.id)
        }
        assertEquals("Innovis 180 (NV180)", en["machine_name_kr_nv180"])
        assertEquals("PR680W (Korea)", en["machine_name_kr_pr680w"])
    }

    @Test fun `existing IDs defaults and custom names remain stable`() {
        assertSame(MachineProfiles.SE400, MachineProfiles.default)
        assertNull(machineNameResource("se400"))
        assertNull(machineNameResource("My custom machine"))
        for (profile in MachineProfiles.all) assertEquals(profile.id, MachineProfiles.find(profile.id)?.id)
        assertEquals("kr-nv180", MachineProfiles.find("NV180")?.id)
        assertEquals("kr-nv180", MachineProfiles.find("innovIS 180 (nv180)")?.id)
    }
}
