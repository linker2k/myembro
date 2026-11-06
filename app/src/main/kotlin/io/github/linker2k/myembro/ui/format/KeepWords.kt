// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.format

/**
 * Android breaks Hangul between any two syllables ("USB로 전/송"). A WORD JOINER (U+2060) between
 * non-space neighbours where either side is Hangul keeps each word whole, so lines break only at
 * spaces. Works on every API level (the Phrase word-break style needs API 33).
 */
fun keepWords(text: String): String {
    if (text.none(::isHangul)) return text
    val out = StringBuilder(text.length * 2)
    text.forEachIndexed { i, c ->
        if (i > 0) {
            val prev = text[i - 1]
            if (!prev.isWhitespace() && !c.isWhitespace() && (isHangul(prev) || isHangul(c))) out.append(WORD_JOINER)
        }
        out.append(c)
    }
    return out.toString()
}

private const val WORD_JOINER = '⁠'
private fun isHangul(c: Char) = c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'
