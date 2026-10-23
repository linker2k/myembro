// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/element.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import kotlin.test.*

class ElementCommandsTest {
    private val groups = listOf(StitchGroup(stitches = listOf(Stitch(0.0, 0.0))), StitchGroup(stitches = listOf(Stitch(1.0, 0.0))))

    @Test fun attributesAndCommandsAffectOnlyFinalGroupAndDoNotClearCloneFlags() {
        for (command in listOf(false, true)) for (attribute in listOf(false, true)) {
            val output = ElementCommands(trim = command, stop = command).applyTo(groups, trim_after = attribute, stop_after = attribute)
            assertEquals(groups.first(), output.first())
            assertEquals(command || attribute, output.last().trimAfter)
            assertEquals(command || attribute, output.last().stopAfter)
        }
        val cloned = groups.dropLast(1) + groups.last().copy(trimAfter = true, stopAfter = true)
        assertEquals(cloned, ElementCommands().applyTo(cloned))
        val trailingEmpty = groups + StitchGroup()
        val applied = ElementCommands(trim = true, stop = true).applyTo(trailingEmpty)
        assertTrue(applied[groups.lastIndex].trimAfter && applied[groups.lastIndex].stopAfter)
        assertEquals(StitchGroup(), applied.last())
        assertEquals(listOf(StitchGroup()), ElementCommands(trim = true).applyTo(listOf(StitchGroup())))
    }

    @Test fun ignoreObjectAndIgnoreLayerDiscardAllGroups() {
        assertTrue(ElementCommands(ignore_object = true).applyTo(groups).isEmpty())
        assertTrue(ElementCommands(ignore_layer = true).applyTo(groups).isEmpty())
        assertTrue(ElementCommands(trim = true).applyTo(emptyList()).isEmpty())
    }

    @Test fun modernStartEndCommandsOverrideLegacyAliasesWithoutMovingStitches() {
        val legacy = Point(5.0, 9.0)
        val modern = Point(10.0, 15.0)
        val commands = ElementCommands(starting_point = modern, ending_point = modern, fill_start = legacy, fill_end = legacy)
        assertEquals(modern, commands.startingPoint)
        assertEquals(modern, commands.endingPoint)
        assertEquals(groups, commands.applyTo(groups))
        assertEquals(legacy, ElementCommands(fill_start = legacy).startingPoint)
        assertEquals(legacy, ElementCommands(fill_end = legacy).endingPoint)
    }
}
