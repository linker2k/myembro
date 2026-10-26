package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlin.test.*

class PillowPhotoFiltersTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/photo-adjust/$name.rgb")!!.use { it.readBytes() }
    private fun rgb(bytes: ByteArray) = IntArray(bytes.size / 3) { i ->
        ((bytes[i*3].toInt() and 255) shl 16) or ((bytes[i*3+1].toInt() and 255) shl 8) or (bytes[i*3+2].toInt() and 255)
    }
    private fun bytes(rgb: IntArray) = ByteArray(rgb.size * 3) { (rgb[it/3] ushr (16-it%3*8)).toByte() }

    @Test fun exactPillowGoldensIncludingBordersClippingAndCombinedPipeline() {
        var comparisons = 0
        for ((w,h) in listOf(1 to 1, 2 to 3, 17 to 13, 128 to 128)) {
            val stem = "${w}x${h}"
            val original = bytes("$stem-input")
            val input = rgb(original)
            for (size in listOf(3,5,7)) {
                assertContentEquals(bytes("$stem-median$size"), bytes(PillowPhotoFilters.median(w,h,input,size)), "$stem median$size")
                comparisons++
            }
            assertContentEquals(PhotoRasterFilters.median3(w,h,input),PillowPhotoFilters.median(w,h,input,3))
            for (factor in listOf(0.5,0.73,1.0,1.3,2.0)) {
                assertContentEquals(bytes("$stem-contrast$factor"),bytes(PillowPhotoFilters.contrast(input,factor.toFloat())),"$stem contrast$factor")
                comparisons++
            }
            val argb = IntArray(input.size) { input[it] or 0xff000000.toInt() }
            for (mono in listOf(false,true)) {
                assertContentEquals(bytes("$stem-combined"),bytes(PhotoCorrections.adjust(w,h,argb,mono,5,1.3f).pixels))
                comparisons++
            }
            assertContentEquals(input,PillowPhotoFilters.median(w,h,input,0))
            assertContentEquals(original,bytes(input),"input is immutable")
        }
        println("PILLOW_EXACT_COMPARISONS $comparisons; MEDIAN3_LEGACY_IDENTICAL 4/4")
    }

    @Test fun adjustableDefaultsMatchLegacyAndAlphaBypassAndOtsu() {
        val input=rgb(bytes("17x13-input")).map { it or 0xff000000.toInt() }.toIntArray()
        for (mono in listOf(false,true)) assertContentEquals(
            PhotoCorrections.apply(17,13,input,mono).pixels,
            PhotoCorrections.adjust(17,13,input,mono,if(mono) 3 else 0,1f).pixels)
        val alpha=input.copyOf().also { it[0]=0x80112233.toInt() }
        val result=PhotoCorrections.adjust(17,13,alpha,false,7,2f)
        assertTrue(result.alphaBypassed);assertContentEquals(alpha,result.pixels)
        assertContentEquals(PhotoCorrections.apply(17,13,input,true,PhotoCorrection.OTSU).mask,
            PhotoCorrections.adjust(17,13,input,true,3,1f,true).mask)
        assertFailsWith<IllegalArgumentException> { PillowPhotoFilters.median(17,13,input,4) }
        assertFailsWith<IllegalArgumentException> { PillowPhotoFilters.contrast(input,Float.NaN) }
    }
}
