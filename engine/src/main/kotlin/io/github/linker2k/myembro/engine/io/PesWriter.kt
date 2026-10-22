// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.EmbPattern

/**
 * Minimal PES v1 port of pyembroidery 1.5.1 (MIT) PesWriter.py,
 * write_truncated_version_1:
 * Source: https://github.com/EmbroidePy/pyembroidery (pyembroidery/PesWriter.py).
 * Pinned source distribution: https://pypi.org/project/pyembroidery/1.5.1/
 *
 * A 22-byte v1 container points directly to the embedded PEC payload. This deliberately
 * omits CEmbOne/CSewSeg editor objects, custom RGB threads and PES v6 metadata. Machine
 * recognition still needs hardware validation; reading successfully is not proof of sewing.
 */
object PesWriter {
    /** Only PES v1 is supported. See [PecWriter.normalize] for normalization and limits. */
    fun write(pattern: EmbPattern): ByteArray {
        val header = ByteArray(22)
        "#PES0001".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[8] = 22 // little-endian PEC offset; remaining header bytes are zero.
        return header + PecWriter.writePayload(pattern)
    }
}
