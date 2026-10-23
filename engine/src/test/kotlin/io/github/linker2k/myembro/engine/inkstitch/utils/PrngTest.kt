// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.utils

import kotlin.test.*

class PrngTest {
    @Test fun blake2sMatchesStandardVectorsAcrossBlockBoundariesAndUtf8() {
        // RFC 7693 / independent Python stdlib hashlib values, not an Ink/Stitch execution.
        val vectors = mapOf(
            "" to "69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9",
            "abc" to "508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982",
            "a".repeat(64) to "651d2f5f20952eacaea2fba2f2af2bcd633e511ea2d2e4c9ae2ac0d9ffb7b252",
            "a".repeat(65) to "045f8ae18932119bd051ac7ba5c73db59892055fad5c32f82d79a6543d92a497",
            "a".repeat(128) to "3ac477e27353f9019b81694afe60c8049403784f91a58288428ea318bfa82809",
            "한글/0" to "9d37bbe4901e8340ceb484980b6ab828ec6a6dcc1ec154f46d0c45a06ace8335",
        )
        for ((input, hex) in vectors) assertEquals(hex, Prng.blake2s(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    @Test fun uniformStreamUsesBigEndianHexWordsAndSuccessiveCounters() {
        val words = listOf(0x70b38da1L, 0xbd706a72L, 0xc32ddcd4L, 0x6909d43aL,
            0x24584f26L, 0x96e10667L, 0x78f6dae7L, 0x8395ed34L, 0x33c10eb5L)
        val expected = words.map { it / 4294967295.0 }
        assertEquals(expected, Prng.iterUniformFloats("fixture").take(9).toList())
        assertEquals(expected, Prng.nUniformFloats(9, "fixture"))
        assertEquals(emptyList(), Prng.nUniformFloats(0, "fixture"))
        assertEquals("a/3", Prng.joinArgs("a", 3))
    }
}
