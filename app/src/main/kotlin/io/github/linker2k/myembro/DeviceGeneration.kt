// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro

/**
 * A stitch generation library class this device's runtime cannot run (a LinkageError, e.g. ART rejecting
 * JTS RelateNG's RelatePointLocator with a VerifyError). Rethrown as an Exception so the caller's existing
 * failure path refuses the save, send or preview with its message instead of the app crashing. Nothing is
 * produced, so no sewing check is skipped. OutOfMemoryError and other Errors are not converted.
 */
class DeviceGenerationException(cause: LinkageError) :
    IllegalStateException("DEVICE_GENERATION_FAILED: ${cause.javaClass.name}: ${cause.message}", cause)

inline fun <T> deviceGeneration(block: () -> T): T =
    try { block() } catch (e: LinkageError) { throw DeviceGenerationException(e) }
