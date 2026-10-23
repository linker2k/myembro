// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/extensions/output.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.inkstitch.elements.InkElementSewing
import io.github.linker2k.myembro.engine.inkstitch.elements.InkNodes
import io.github.linker2k.myembro.engine.inkstitch.elements.InkStitchElements
import io.github.linker2k.myembro.engine.model.EmbPattern
import java.io.InputStream

/**
 * Opens an Ink/Stitch (or plain) SVG document as embroidery, as Ink/Stitch's "output" does for the
 * whole document: every visible element in document order, by its `inkstitch:*` parameters or
 * Ink/Stitch's defaults when it has none. Refusals carry an [SvgRejection] code.
 */
object InkStitchSvgImport {
    data class Result(val pattern: EmbPattern, val elements: InkStitchElements)

    fun read(input: InputStream): InkStitchElements = InkNodes(SvgDocument.parse(input)).read()

    fun open(input: InputStream, name: String = "SVG"): Result {
        val elements = read(input)
        if (elements.elements.isEmpty()) throw SvgImportException(SvgRejection.INVALID_GEOMETRY, "no embroiderable elements")
        val sewer = InkElementSewing.Sewer(thread = null, metadata = elements.metadata)
        for (e in elements.elements) sewer.add(e, InkElementSewing.Mapping())
        val pattern = sewer.finish(mapOf("name" to name))
        if (pattern.stitchCount == 0) throw SvgImportException(SvgRejection.INVALID_GEOMETRY, "elements produced no stitches")
        return Result(pattern, elements)
    }
}
