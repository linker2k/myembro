// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.library.DesignFormats
import java.io.File
import java.util.UUID

/** Adding a project treats its visible design as one object; opening it preserves editable layers. */
internal fun importDesignObject(file: File): StitchObject = if (file.extension.equals("myembro", true)) {
    StitchObject(UUID.randomUUID().toString(), file.nameWithoutExtension, DesignFormats.read(file))
} else StitchObject.fromBytes(UUID.randomUUID().toString(), file.nameWithoutExtension, file.readBytes(), file.name)
