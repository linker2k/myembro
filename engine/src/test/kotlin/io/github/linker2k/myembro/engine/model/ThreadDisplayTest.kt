// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.model

import kotlin.test.*

class ThreadDisplayTest {
    @Test fun invalidDescriptionUsesReadableCatalogOrRgbAndDoesNotChangeIdentity() {
        val thread = EmbThread(0xFFF993BC.toInt(), "���085���", "085", "EMBROIDERY���")
        assertEquals("085", thread.displayName)
        assertEquals("085", thread.displayDetails)
        assertEquals("���085���", thread.description)
        assertEquals("Brother 085", thread.copy(brand = "Brother").displayName)
        assertEquals("#f993bc", thread.copy(catalogNumber = "���").displayName)
        assertEquals("#f993bc", thread.copy(catalogNumber = "���").displayDetails)
    }

    @Test fun controlCharactersAreRejectedEvenAtStringEnds() {
        for (control in listOf('\u0000', '\n', '\t', '\u001B', '\u007F', '\u0085')) {
            val thread = EmbThread(0xFF123456.toInt(), "Pink$control", "085$control", "brand$control")
            assertEquals("#123456", thread.displayName)
            assertEquals("#123456", thread.displayDetails)
        }
        val brandOnly = EmbThread(0xFF123456.toInt(), "�", null, "Brother")
        assertEquals("#123456", brandOnly.displayName)
        assertEquals("Brother · #123456", brandOnly.displayDetails)
    }

    @Test fun validUnicodeLabelsAreKeptAndBlankDescriptionsFallBack() {
        val thread = EmbThread(0xFF123456.toInt(), "  분홍 🌷  ", "085", "Brother")
        assertEquals("분홍 🌷", thread.displayName)
        assertEquals("Brother · 085 · 분홍 🌷", thread.displayDetails)
        assertEquals("Brother 085", thread.copy(description = " ").displayName)
        assertEquals("#123456", EmbThread(0xFF123456.toInt()).displayName)
    }
}
