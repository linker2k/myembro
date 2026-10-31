// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.render.realistic

/** Platform boundary: rasterize a filled Bezier path to coverage bytes 0..255.
 * JVM tests use Java2D; Android can later implement this with Canvas into an alpha mask.
 * Neither backend is imported into the pure-Kotlin filter implementation.
 */
fun interface ThreadAlphaRasterizer {
    fun rasterize(path: List<ThreadPathCommand>, width: Int, height: Int): IntArray
}

/** Experimental CPU reference prototype, NOT connected to app/export or claimed pixel-equivalent.
 * Object-bounded filter buffers and optional exact-key cache; still a numeric prototype.
 * For a fixed physical viewport, pass integer raster width / SVG viewport width as pixelsPerSvgUnit
 * (Inkscape png-write.cpp:429–430); the nominal dpi/96 default defines an implicit viewport only.
 * No automatic changes to embroidery coordinates/threads/locks or gate policies.
 */
object RealisticThreadRenderer {
    fun render(width: Int, height: Int, dpi: Int, segments: List<RenderThreadSegment>,
               rasterizer: ThreadAlphaRasterizer, cache: ThreadShapeCache? = null,
               pixelsPerSvgUnit: Double = dpi / 96.0): IntArray {
        require(width >= 3 && height >= 3)
        require(dpi > 0)
        require(pixelsPerSvgUnit > 0 && pixelsPerSvgUnit.isFinite())
        val scale = pixelsPerSvgUnit // Caller supplies actual integer-raster/SVG-viewport ratio.
        val output = IntArray(Math.multiplyExact(width, height)) { -1 }
        for (segment in segments) {
            if (segment.start == segment.end) continue // A display segment is not a new needle penetration.
            val path = ThreadShape.path(segment, scale)
            val region = ThreadFilterRegion.bounds(path)
            require(region.width >= 2 && region.height >= 2) { "Subpixel filter regions are not supported by this prototype" }
            val local = region.local(path)
            fun bake(): IntArray {
                val mask = rasterizer.rasterize(local, region.width, region.height)
                require(mask.size == region.width*region.height && mask.all { it in 0..255 })
                val blur = InkscapeGaussian.blur(mask, region.width, region.height, ThreadFilterMath.SIGMA * scale)
                val light = ThreadFilterMath.specular(blur, region.width, region.height, scale)
                return ThreadFilterMath.bake(mask, light, segment.displayRgb)
            }
            val tile = if (cache == null) bake() else {
                val dx=segment.end.x-segment.start.x;val dy=segment.end.y-segment.start.y
                val x=segment.start.x*scale;val y=segment.start.y*scale
                cache.getOrBake(ThreadShapeCache.Key(kotlin.math.hypot(dx,dy),kotlin.math.atan2(dy,dx),dpi,scale,
                    x-kotlin.math.floor(x),y-kotlin.math.floor(y),segment.displayRgb,region.width,region.height,local),::bake)
            }
            for (y in 0 until region.height) {
                val targetY=region.top+y
                if(targetY !in 0 until height)continue
                for(x in 0 until region.width) {
                    val targetX=region.left+x
                    if(targetX !in 0 until width)continue
                    val pixel=tile[y*region.width+x];val alpha=pixel ushr 24
                    if(alpha==0)continue
                    val i=targetY*width+targetX
                    var result=-0x1000000
                    for(channel in 0..2) {
                        val shift=16-channel*8
                        val color=((pixel ushr shift) and 255)+ThreadFilterMath.premultiply((output[i] ushr shift) and 255,255-alpha)
                        result=result or (color.coerceAtMost(255) shl shift)
                    }
                    output[i]=result
                }
            }
        }
        return output
    }
}
