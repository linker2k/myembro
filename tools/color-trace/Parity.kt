// SPDX-License-Identifier: GPL-3.0-or-later
// Compares SampleColorRegions with reference_labels.py output given the same NumPy sample.
import io.github.linker2k.myembro.engine.inkstitch.svg.image.SampleColorRegions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

fun ints(f: File): IntArray { val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer(); return IntArray(b.remaining()) { b.get(it) } }
fun main(args: Array<String>) {
    for (i in args.indices step 2) {
        val png = File(args[i]); val prefix = args[i + 1]
        val meta = File("$prefix.json").readText()
        fun num(k: String) = Regex("\"$k\": ([0-9.e-]+)").find(meta)!!.groupValues[1]
        val pal = Regex("\"palette\": \\[([^]]*)]").find(meta)!!.groupValues[1].split(",").map { it.trim().toInt() }
        val img = ImageIO.read(png); val w = img.width; val h = img.height
        val argb = img.getRGB(0, 0, w, h, null, 0, w)
        val t0 = System.nanoTime()
        val r = SampleColorRegions.labels(w, h, argb, num("mm_per_px").toDouble() * w, num("maxcolors").toInt(), ints(File("$prefix-sample.bin")), num("first").toInt())
        val ms = (System.nanoTime() - t0) / 1e6
        val ref = ints(File("$prefix-labels.bin"))
        val diff = ref.indices.count { ref[it] != r.labels[it] }
        val t1 = System.nanoTime(); val own = SampleColorRegions.labels(w, h, argb, num("mm_per_px").toDouble() * w, num("maxcolors").toInt()); val ms2 = (System.nanoTime() - t1) / 1e6; println("  own sample: palette=${own.palette.map { "%06x".format(it) }} mismatch=${ref.indices.count { ref[it] != own.labels[it] }}")
        println("${png.name}\tpalette_equal=${pal == r.palette}\tpy=${pal.map { "%06x".format(it) }}\tkt=${r.palette.map { "%06x".format(it) }}\tlabel_mismatch=$diff/${ref.size}\tms_numpy_sample=%.0f\tms_own_sample=%.0f".format(ms, ms2))
    }
}
