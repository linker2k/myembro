// Ported from Ink/Stitch lib/utils/prng.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

/** Counter-mode BLAKE2s stream matching Python hashlib.blake2s(...).hexdigest() word order. */
internal class MeanderPrng(seed: String, shapeIndex: Int) {
    private val prefix = "$seed/meander-fill/$shapeIndex/"
    private var block = 0L
    private var values = emptyList<Double>()
    private var position = 0
    fun next(): Double {
        if (position == values.size) {
            val digest = blake2s((prefix + block++).toByteArray(Charsets.UTF_8))
            values = (0 until 8).map { i ->
                var value = 0L
                for (j in 0..3) value = (value shl 8) or (digest[i * 4 + j].toLong() and 255)
                value.toDouble() / 4294967295.0
            }
            position = 0
        }
        return values[position++]
    }

    companion object {
        private val iv = intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19)
        private val sigma = arrayOf(
            intArrayOf(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15),
            intArrayOf(14,10,4,8,9,15,13,6,1,12,0,2,11,7,5,3),
            intArrayOf(11,8,12,0,5,2,15,13,10,14,3,6,7,1,9,4),
            intArrayOf(7,9,3,1,13,12,11,14,2,6,5,10,4,0,15,8),
            intArrayOf(9,0,5,7,2,4,10,15,14,1,11,12,6,8,3,13),
            intArrayOf(2,12,6,10,0,11,8,3,4,13,7,5,15,14,1,9),
            intArrayOf(12,5,1,15,14,13,4,10,0,7,6,3,9,2,8,11),
            intArrayOf(13,11,7,14,12,1,3,9,5,0,15,4,8,6,2,10),
            intArrayOf(6,15,14,9,11,3,0,8,12,2,13,7,1,4,10,5),
            intArrayOf(10,2,8,4,7,6,1,5,15,11,9,14,3,12,13,0))

        /** Unkeyed 32-byte BLAKE2s, implementing the standard RFC 7693 compression equations. */
        internal fun blake2s(input: ByteArray): ByteArray {
            val h = iv.copyOf()
            h[0] = h[0] xor 0x01010020
            var offset = 0
            var count = 0L
            fun compress(bytes: ByteArray, amount: Int, last: Boolean) {
                val m = IntArray(16) { i ->
                    var word = 0
                    for (j in 0..3) word = word or ((bytes[i * 4 + j].toInt() and 255) shl (j * 8))
                    word
                }
                count += amount
                val v = h + iv
                v[12] = v[12] xor count.toInt()
                v[13] = v[13] xor (count ushr 32).toInt()
                if (last) v[14] = v[14].inv()
                fun g(a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
                    v[a] += v[b] + x
                    v[d] = Integer.rotateRight(v[d] xor v[a], 16)
                    v[c] += v[d]
                    v[b] = Integer.rotateRight(v[b] xor v[c], 12)
                    v[a] += v[b] + y
                    v[d] = Integer.rotateRight(v[d] xor v[a], 8)
                    v[c] += v[d]
                    v[b] = Integer.rotateRight(v[b] xor v[c], 7)
                }
                for (s in sigma) {
                    g(0,4,8,12,m[s[0]],m[s[1]]); g(1,5,9,13,m[s[2]],m[s[3]])
                    g(2,6,10,14,m[s[4]],m[s[5]]); g(3,7,11,15,m[s[6]],m[s[7]])
                    g(0,5,10,15,m[s[8]],m[s[9]]); g(1,6,11,12,m[s[10]],m[s[11]])
                    g(2,7,8,13,m[s[12]],m[s[13]]); g(3,4,9,14,m[s[14]],m[s[15]])
                }
                for (i in h.indices) h[i] = h[i] xor v[i] xor v[i + 8]
            }
            while (offset + 64 < input.size) {
                compress(input.copyOfRange(offset, offset + 64), 64, false)
                offset += 64
            }
            val last = ByteArray(64)
            input.copyInto(last, 0, offset)
            compress(last, input.size - offset, true)
            return ByteArray(32) { i -> (h[i / 4] ushr ((i % 4) * 8)).toByte() }
        }
    }
}
