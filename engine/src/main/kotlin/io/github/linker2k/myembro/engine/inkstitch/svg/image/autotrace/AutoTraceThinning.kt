// SPDX-License-Identifier: LGPL-2.1-or-later
// Ported from AutoTrace src/thin-image.c@1a232b27818a46a05cd25edec09e2f77c4e9ac01 (LGPL-2.1-or-later), Copyright (C) 2001, 2002 Martin Weber.
// Kotlin storage/API adaptation (c) 2026 myembro contributors; GPL v3 combination permitted.
// Original thin-image.h credits Joseph M. Cychosz, Graphics Gems IV (1994),
// "Efficient Binary Image Thinning using Neighborhood Maps".
package io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace

/** Original Rosenfeld thinning only: top-down packed one-channel or RGB bytes, no paths or stitches. */
object AutoTraceThinning {
    /** Explicit white background replaces the C process-global background; each call is independent. */
    fun thin(width: Int, height: Int, pixels: ByteArray, planes: Int = 1,
        backgroundRgb: Int = 0xFFFFFF): ByteArray {
        require(width in 1..4096 && height in 1..4096 && width.toLong()*height <= 1_048_576)
        require(planes == 1 || planes == 3)
        require(pixels.size.toLong() == width.toLong()*height*planes)
        require(backgroundRgb in 0..0xFFFFFF)
        val r=backgroundRgb ushr 16
        val g=backgroundRgb ushr 8 and 255
        val b=backgroundRgb and 255
        val background=if (planes==3) backgroundRgb else if (r==g && g==b) r
            else (r*0.30+g*0.59+b*0.11+0.5).toInt()
        // Packed Int storage substitutes Pixel[3]/unsigned char, preserving exact color equality.
        val image=IntArray(width*height) { i ->
            if (planes==1) pixels[i].toInt() and 255
            else ((pixels[i*3].toInt() and 255) shl 16) or
                ((pixels[i*3+1].toInt() and 255) shl 8) or (pixels[i*3+2].toInt() and 255)
        }
        val remaining=image.copyOf()
        // Original thin_image: discover colors in reverse pixel order, once per distinct color.
        for (n in remaining.lastIndex downTo 0) {
            val colour=remaining[n]
            if (colour==background) continue
            for (m in n-1 downTo 0) if (remaining[m]==colour) remaining[m]=background
            thinColor(image,width,height,colour,background,planes==3)
        }
        return ByteArray(pixels.size) { i ->
            if (planes==1) image[i].toByte()
            else (image[i/3] ushr (16-8*(i%3))).toByte()
        }
    }

    /** thin1/thin3 share the exact scan; the RGB-only directional edge guards remain distinct. */
    private fun thinColor(image: IntArray, width: Int, height: Int, colour: Int,
        background: Int, rgb: Boolean) {
        fun present(index: Int)=if (image[index]==colour) 1 else 0
        val qb=IntArray(width)
        qb[width-1]=0 // Original unsigned-char previous-scan buffer's lower-right sentinel.
        var count=1
        while (count!=0) {
            count=0
            for (i in masks.indices) {
                val mask=masks[i]
                var p=present(0)
                for (x in 0 until width-1) {
                    p=((p shl 1) and 6) or present(x+1) // C octal 0006
                    qb[x]=p and 255
                }
                for (y in 0 until height-1) {
                    val row=y*width
                    val next=row+width
                    var q=qb[0]
                    p=((q shl 2) and 216) or present(next) // C octal 0330
                    for (x in 0 until width-1) {
                        q=qb[x]
                        p=((p shl 1) and 438) or ((q shl 3) and 72) or present(next+x+1) // 0666, 0110
                        qb[x]=p and 255 // C casts p to unsigned char: do NOT retain its ninth bit.
                        if ((!rgb || i!=2 || x!=0) && p and mask==0 && toDelete[p]!=0) {
                            count++
                            image[row+x]=background
                        }
                    }
                    p=(p shl 1) and 438
                    if ((!rgb || i!=3) && p and mask==0 && toDelete[p]!=0) {
                        count++
                        image[row+width-1]=background
                    }
                }
                if (!rgb || i!=1) {
                    var q=qb[0]
                    p=(q shl 2) and 216
                    val row=width*(height-1)
                    for (x in 0 until width) {
                        q=qb[x]
                        p=((p shl 1) and 438) or ((q shl 3) and 72)
                        if ((!rgb || i!=2 || x!=0) && p and mask==0 && toDelete[p]!=0) {
                            count++
                            image[row+x]=background
                        }
                    }
                }
            }
        }
    }

    // Original octal masks: 0200, 0002, 0040, 0010; direction/pass order is significant.
    private val masks=intArrayOf(128,2,32,8)
    private val toDelete=intArrayOf(
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,1,0,0,1,1,0,1,1,1,0,0,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,1,1,1,0,1,1,0,0,1,1,0,0,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,1,1,1,1,0,0,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,1,1,0,0,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,1,1,1,1,0,0,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        1,0,1,1,1,0,1,1,1,1,1,1,1,1,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        1,0,0,0,0,0,0,0,1,1,1,1,0,0,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        1,0,1,1,1,0,1,1,1,1,1,1,1,1,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        1,0,1,1,1,0,1,1,0,0,1,1,0,0,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,1,1,0,0,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        1,0,0,0,0,0,0,0,1,1,1,1,0,0,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        1,0,1,1,1,0,1,1,1,1,1,1,1,1,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        1,0,0,0,0,0,0,0,1,1,1,1,0,0,1,1,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        1,0,1,1,1,0,1,1,1,1,1,1,1,1,1,1,
    )
}
