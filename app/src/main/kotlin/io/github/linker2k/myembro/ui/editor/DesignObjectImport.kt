// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.DesignObject
import io.github.linker2k.myembro.engine.edit.ProjectJson
import io.github.linker2k.myembro.engine.edit.ShapeObject
import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.library.DesignFormats
import java.io.File
import java.util.UUID

/** Adding a project treats its visible design as one object; opening it preserves editable layers.
 * A project holding a single block shape is that object already, so it is added as-is to keep its blocks. */
internal fun importDesignObject(file: File): DesignObject {
    val id = UUID.randomUUID().toString()
    if (!file.extension.equals("myembro", true))
        return StitchObject.fromBytes(id, file.nameWithoutExtension, file.readBytes(), file.name)
    val shape = ProjectJson.decode(file.readText().removePrefix("\uFEFF")).objects.singleOrNull() as? ShapeObject
    return shape?.withId(id) ?: StitchObject(id, file.nameWithoutExtension, DesignFormats.read(file))
}
