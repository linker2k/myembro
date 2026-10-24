// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.model.EmbThread

/** ITU-R BT.709-6 §3.5: coded luma Y'=0.2126 R'+0.7152 G'+0.0722 B'.
 * Full-range RGB bytes, nearest integer; no limited-range video encoding or extra gamma transform.
 * https://www.itu.int/rec/R-REC-BT.709
 */
object Bt709Grayscale {
    /** Canonical Brother neutral families; fixed chart IDs, no invented chroma threshold. */
    val threads:List<EmbThread> = listOf(16,19,20,23,29,39).map { PecThreads.forIndex(it) }
    fun argb(pixel:Int):Int {
        val r=(pixel ushr 16) and 255;val g=(pixel ushr 8) and 255;val b=pixel and 255
        val y=(2126*r+7152*g+722*b+5000)/10000
        return (pixel and 0xff000000.toInt()) or (y shl 16) or (y shl 8) or y
    }
    fun nearestThread(gray:Int):EmbThread=threads.minBy { PecThreads.distance(gray,it.color) }
}
