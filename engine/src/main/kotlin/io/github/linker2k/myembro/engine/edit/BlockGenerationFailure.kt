// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

/** Stable machine-readable reasons. Never classify a failure by its human-readable message. */
enum class BlockGenerationKind {
    MEANDER_GRAPH, MEANDER_GENERATION, SATIN_GEOMETRY_UNSUPPORTED, SATIN_STITCH_TOO_LONG,
    SATIN_GENERATION_FAILED, EMPTY_REGION, UNSUPPORTED_FILL_BACKEND, REGION_SET_CHANGED,
    REGION_GENERATION, REGION_ORDER, OUTPUT_ENCODING,
}

class BlockGenerationException(val kind: BlockGenerationKind, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
