// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.machines

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.Bounds
import io.github.linker2k.myembro.ui.Hoop

enum class TransferMethod { PC_USB_CABLE, USB_FLASH_DRIVE, BROTHER_WIRELESS }
enum class StorageScope { TEMPORARY_USB_DISK, INTERNAL_MEMORY }

/** Limits are scoped: internal memory counts must never be used as USB-drive limits. */
data class StorageLimit(val scope: StorageScope, val maxPatterns: Int, val maxStitchesPerPattern: Int? = null)

/** [coverage] is fabric coverage, not necessarily one continuously sewable design. */
data class MachineHoop(
    val sewingArea: HoopSpec,
    val coverage: HoopSpec = sewingArea,
    val requiresRepositioning: Boolean = false,
)

data class MachineProfile(
    val id: String,
    val displayName: String,
    val maximumArea: HoopSpec,
    val hoops: List<MachineHoop>,
    val transferMethods: Set<TransferMethod>,
    val storageLimits: List<StorageLimit> = emptyList(),
    val sourceUrls: List<String>,
    val exportFormats: Set<String> = setOf("PES"),
    // No numeric PES filename limit or Android USB cable compatibility was verified.
    val maxFilenameCharacters: Int? = null,
    val androidOtgCableVerified: Boolean? = null,
) {
    /** The app currently implements single-placement projects only. No automatic multi-hooping. */
    val projectHoops: List<HoopSpec> = HoopSpec.ALL.filter { candidate ->
        candidate.width <= maximumArea.width && candidate.height <= maximumArea.height &&
            hoops.any { it.sewingArea.width == candidate.width && it.sewingArea.height == candidate.height }
    }
    val defaultHoop: HoopSpec get() = projectHoops.first()

    fun supports(hoop: HoopSpec): Boolean = projectHoops.any { it.width == hoop.width && it.height == hoop.height }
    fun supports(hoop: Hoop): Boolean = supports(HoopSpec(hoop.label, hoop.widthMm * 10, hoop.heightMm * 10))

    /** Dimension check for a centered imported design. Editor placement uses engine Bounds.outside. */
    fun fits(bounds: Bounds): Boolean =
        bounds.maxX.toLong() - bounds.minX in 0L..maximumArea.width.toLong() &&
            bounds.maxY.toLong() - bounds.minY in 0L..maximumArea.height.toLong()
}

/** Official US manuals, verified 2026-10-22. Unverified specifications remain null. */
object MachineProfiles {
    const val SE400_MANUAL = "https://download.brother.com/welcome/doch000184/885v31_v32_v33_om04en.pdf"
    const val SE400_USB = "https://download.brother.com/welcome/doch000186/885v31_v32_usb02_usenes.pdf"
    const val SE600_MANUAL = "https://download.brother.com/welcome/doch101011/888m20_om02en.pdf"
    const val SE700_MANUAL = "https://download.brother.com/welcome/doch102582/888p10_om01en.pdf"
    const val SE700_WIRELESS = "https://support.brother.com/g/b/faqend.aspx?c=us&faqid=faqh00101118_008&lang=en&prod=hf_se700eus"
    const val SE700_PRODUCT = "https://www.brother-usa.com/p/sewing-embroidery/SE700"
    private val medium = MachineHoop(HoopSpec.H100)
    private val small = MachineHoop(HoopSpec("60×20", 600, 200))
    private val repositionable = MachineHoop(HoopSpec.H100, HoopSpec("100×170", 1000, 1700), true)

    // Operation Manual p.127: medium 100×100, small W60×H20, large W100×H170 (multiple placements).
    // Source: https://download.brother.com/welcome/doch000184/885v31_v32_v33_om04en.pdf
    // USB Manual pp.2–4: PES; Windows Removable Disk, root only, <=12 temporary patterns.
    // Source: https://download.brother.com/welcome/doch000186/885v31_v32_usb02_usenes.pdf
    val SE400 = MachineProfile(
        "se400", "Brother SE400", HoopSpec.H100, listOf(medium, small, repositionable),
        setOf(TransferMethod.PC_USB_CABLE), listOf(StorageLimit(StorageScope.TEMPORARY_USB_DISK, 12)),
        listOf(SE400_MANUAL, SE400_USB),
    )
    // Operation Manual pp.8,60,74–75: maximum 100×100; small 60×20; 100×170 requires repositioning;
    // PES and USB flash-drive retrieval. No numeric PES stitch/file-count limit verified here.
    // Source: https://download.brother.com/welcome/doch101011/888m20_om02en.pdf
    val SE600 = MachineProfile(
        "se600", "Brother SE600", HoopSpec.H100, listOf(medium, small, repositionable),
        setOf(TransferMethod.USB_FLASH_DRIVE), sourceUrls = listOf(SE600_MANUAL),
    )
    // Operation Manual pp.76,80–81,96: 100×100, small 60×20, USB flash drive, PES;
    // INTERNAL MEMORY: <=20 embroidery patterns / 1024 KB; <=100,000 stitches per pattern.
    // Source: https://download.brother.com/welcome/doch102582/888p10_om01en.pdf
    // Official wireless FAQ (Design Database Transfer / PE-DESIGN 11):
    // https://support.brother.com/g/b/faqend.aspx?c=us&faqid=faqh00101118_008&lang=en&prod=hf_se700eus
    // Artspira mobile wireless support: https://www.brother-usa.com/p/sewing-embroidery/SE700
    val SE700 = MachineProfile(
        "se700", "Brother SE700", HoopSpec.H100, listOf(medium, small),
        setOf(TransferMethod.USB_FLASH_DRIVE, TransferMethod.BROTHER_WIRELESS),
        listOf(StorageLimit(StorageScope.INTERNAL_MEMORY, 20, 100_000)),
        listOf(SE700_MANUAL, SE700_WIRELESS, SE700_PRODUCT),
    )
    val all = listOf(SE400, SE600, SE700)
    val default: MachineProfile get() = SE400

    /** Exact model aliases only; an arbitrary custom machine never inherits SE400 capabilities. */
    fun find(model: String): MachineProfile? {
        val key = model.trim().replace(Regex("(?i)^Brother\\s+"), "").replace("-", "")
        return all.firstOrNull { it.id.equals(key, ignoreCase = true) }
    }
}
