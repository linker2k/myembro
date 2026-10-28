// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec

enum class MachineRegion { US, KOREA, OTHER }
enum class MachineTransfer { PC_USB_CABLE, USB_FLASH_DRIVE, BROTHER_WIRELESS }

/** Confirmed manufacturer facts only. Null means unconfirmed, never another model's value.
 * Width/height are native 0.1mm units; manual H×W tables are transposed explicitly.
 * Individual links and verification scope: docs/machines.md (2026-10-28).
 */
data class BrotherMachineFacts(
    val id: String, val displayName: String, val region: MachineRegion,
    val maximumArea: HoopSpec, val hoops: List<HoopSpec>,
    val transferMethods: Set<MachineTransfer>?, val automaticJumpTrim: Boolean?,
    val sourceUrls: List<String>, val aliases: Set<String>,
) {
    fun sewoutMachine() = SewoutMachine(id, displayName,
        Specification(maximumArea.width / 10.0, sourceUrls.joinToString()),
        Specification(maximumArea.height / 10.0, sourceUrls.joinToString()),
        automaticJumpTrim = Specification(automaticJumpTrim, sourceUrls.joinToString().takeIf { automaticJumpTrim != null }),
        manualJumpCleanup = if (automaticJumpTrim == false) Specification(true, sourceUrls.joinToString()) else Specification(),
        knownHoopsMm = hoops.map { it.width / 10.0 to it.height / 10.0 }.toSet())
}

object BrotherMachineCatalog {
    private fun area(widthMm: Int, heightMm: Int) = HoopSpec("${widthMm}×${heightMm}", widthMm*10, heightMm*10)
    val all: List<BrotherMachineFacts> = listOf(
        // Research model-specific platform manual; wireless on NS1750D left unconfirmed due to conflicting product/manual claims.
        BrotherMachineFacts("se425", "Brother SE425", MachineRegion.US,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.PC_USB_CABLE), false,
            listOf("https://download.brother.com/welcome/doch000184/885v31_v32_v33_om04en.pdf", "https://www.brother-usa.com/p/sewing-embroidery/SE425"), setOf("SE425")),
        // Research model-specific platform manual; wireless on NS1750D left unconfirmed due to conflicting product/manual claims.
        BrotherMachineFacts("se625", "Brother SE625", MachineRegion.US,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), false,
            listOf("https://download.brother.com/welcome/doch101011/888m20_om02en.pdf", "https://www.brother-usa.com/p/sewing-embroidery/SE625"), setOf("SE625")),
        // Research model-specific platform manual; wireless on NS1750D left unconfirmed due to conflicting product/manual claims.
        BrotherMachineFacts("se630", "Brother SE630", MachineRegion.US,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), false,
            listOf("https://download.brother.com/welcome/doch101011/888m20_om02en.pdf", "https://www.brother-usa.com/p/sewing-embroidery-combo/SE630"), setOf("SE630")),
        // Research model-specific platform manual; wireless on NS1750D left unconfirmed due to conflicting product/manual claims.
        BrotherMachineFacts("se725", "Brother SE725", MachineRegion.US,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), false,
            listOf("https://download.brother.com/welcome/doch102582/888p10_om01en.pdf", "https://www.brother-usa.com/p/sewing-embroidery-combo/SE725"), setOf("SE725")),
        // Research model-specific platform manual; wireless on NS1750D left unconfirmed due to conflicting product/manual claims.
        BrotherMachineFacts("pe535", "Brother PE535", MachineRegion.US,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), false,
            listOf("https://download.brother.com/welcome/doch101288/888m36_m37_om01en.pdf", "https://www.brother-usa.com/p/sewing-embroidery/PE535"), setOf("PE535")),
        // Research model-specific platform manual; wireless on NS1750D left unconfirmed due to conflicting product/manual claims.
        BrotherMachineFacts("pe550d", "Brother PE550D", MachineRegion.US,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), false,
            listOf("https://download.brother.com/welcome/doch101288/888m36_m37_om01en.pdf", "https://www.brother-usa.com/p/sewing-embroidery/PE550D"), setOf("PE550D")),
        // Research model-specific platform manual; wireless on NS1750D left unconfirmed due to conflicting product/manual claims.
        BrotherMachineFacts("pe545", "Brother PE545", MachineRegion.US,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), false,
            listOf("https://download.brother.com/welcome/doch102611/888p30_om01en.pdf", "https://www.brother-usa.com/p/sewing-embroidery/PE545"), setOf("PE545")),
        // Research model-specific platform manual; wireless on NS1750D left unconfirmed due to conflicting product/manual claims.
        BrotherMachineFacts("ns1750d", "Brother NS1750D", MachineRegion.US,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), false,
            listOf("https://download.brother.com/welcome/doch101020/888_m00_m02_m03_m04_om02en.pdf", "https://www.brother-usa.com/p/sewing-embroidery-combo/NS1750D"), setOf("NS1750D")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-m370", "M370", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100), area(60, 20)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), false,
            listOf("https://download.brother.com/welcome/doch102712/888_p00_p02_p03_om01ko.pdf"), setOf("M370")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-m380d", "M380D", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100), area(60, 20)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), false,
            listOf("https://download.brother.com/welcome/doch102712/888_p00_p02_p03_om01ko.pdf"), setOf("M380D")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-lb7950", "LB7950", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), false,
            listOf("https://download.brother.com/welcome/doch102717/888p10_om01ko.pdf"), setOf("LB7950")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-lb8950", "LB8950", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), false,
            listOf("https://download.brother.com/welcome/doch102717/888p10_om01ko.pdf"), setOf("LB8950")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-lb6950", "LB6950", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), false,
            listOf("https://download.brother.com/welcome/doch101678/888m20_om01ko.pdf"), setOf("LB6950")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-nv180", "이노비스 180 (NV180)", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), false,
            listOf("https://download.brother.com/welcome/doch101131/888_m00_m02_m03_m04_om02ko.pdf"), setOf("NV180")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-nv180d", "이노비스 180D (NV180D)", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), false,
            listOf("https://download.brother.com/welcome/doch101131/888_m00_m02_m03_m04_om02ko.pdf"), setOf("NV180D")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-nv180k", "이노비스 180K (NV180K)", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), false,
            listOf("https://download.brother.com/welcome/doch101131/888_m00_m02_m03_m04_om02ko.pdf"), setOf("NV180K")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-nv900", "이노비스 900 (NV900)", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.PC_USB_CABLE), false,
            listOf("https://download.brother.com/welcome/doch000555/inov900dug03en.pdf"), setOf("NV900")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-nv900d", "이노비스 900D (NV900D)", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.PC_USB_CABLE), false,
            listOf("https://download.brother.com/welcome/doch000555/inov900dug03en.pdf"), setOf("NV900D")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-nv950", "이노비스 950 (NV950)", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), false,
            listOf("https://download.brother.com/welcome/doch100602/nv950_980om02ko.pdf"), setOf("NV950")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-nv980d", "이노비스 980D (NV980D)", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), false,
            listOf("https://download.brother.com/welcome/doch100602/nv950_980om02ko.pdf"), setOf("NV980D")),
        // Korean model listing + operation manual. 170×100 optional coverage is not a continuous sewing field.
        BrotherMachineFacts("kr-nv980k", "이노비스 980K (NV980K)", MachineRegion.KOREA,
            area(100, 100), listOf(area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), false,
            listOf("https://download.brother.com/welcome/doch100602/nv950_980om02ko.pdf"), setOf("NV980K")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-f580", "F580", MachineRegion.KOREA,
            area(130, 180), listOf(area(130, 180), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://download.brother.com/welcome/doch102702/888_k10_l11_l12_om01ko.pdf"), setOf("F580")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-ns2750d", "이노비스 NS2750D", MachineRegion.KOREA,
            area(130, 180), listOf(area(130, 180), area(100, 100), area(60, 20)), setOf(MachineTransfer.USB_FLASH_DRIVE), null,
            listOf("https://download.brother.com/welcome/doch100730/888l01_l03_k00_02_om03en.pdf"), setOf("NS2750D")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-nv1250", "이노비스 1250 (NV1250)", MachineRegion.KOREA,
            area(130, 180), listOf(area(130, 180), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), null,
            listOf("https://download.brother.com/welcome/doch000589/nv1250ug02en.pdf"), setOf("NV1250")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-nv2600", "이노비스 2600 (NV2600)", MachineRegion.KOREA,
            area(160, 260), listOf(area(160, 260), area(130, 180), area(100, 100), area(60, 20)), setOf(MachineTransfer.USB_FLASH_DRIVE), null,
            listOf("https://download.brother.com/welcome/doch100373/NV2600_om01ko.pdf"), setOf("NV2600")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-nv2700", "이노비스 2700 (NV2700)", MachineRegion.KOREA,
            area(160, 260), listOf(area(160, 260), area(130, 180), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://download.brother.com/welcome/doch102458/888g30_g32_g34_om01ko.pdf"), setOf("NV2700")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-nv4000d", "이노비스 4000D (NV4000D)", MachineRegion.KOREA,
            area(180, 300), listOf(area(180, 300), area(130, 180), area(100, 100), area(60, 20)), setOf(MachineTransfer.USB_FLASH_DRIVE), true,
            listOf("https://download.brother.com/welcome/doch000013/nv4000ug04en.pdf"), setOf("NV4000D")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-v3", "이노비스 V3", MachineRegion.KOREA,
            area(180, 300), listOf(area(180, 300), area(130, 180), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), true,
            listOf("https://download.brother.com/welcome/doch100272/v3om01ko.pdf"), setOf("V3")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-v3le", "이노비스 V3LE", MachineRegion.KOREA,
            area(200, 300), listOf(area(200, 300), area(200, 200), area(130, 180), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), true,
            listOf("https://download.brother.com/welcome/doch101849/882d82_om01ko.pdf"), setOf("V3LE")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-v5", "이노비스 V5", MachineRegion.KOREA,
            area(180, 300), listOf(area(180, 300), area(130, 180), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), true,
            listOf("https://download.brother.com/welcome/doch100271/v5om01ko.pdf"), setOf("V5")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-v5le", "이노비스 V5LE", MachineRegion.KOREA,
            area(200, 300), listOf(area(200, 300), area(200, 200), area(130, 180), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), true,
            listOf("https://download.brother.com/welcome/doch101707/882c53_om01en.pdf"), setOf("V5LE")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-xj1", "이노비스 XJ1", MachineRegion.KOREA,
            area(240, 360), listOf(area(240, 360), area(130, 180), area(200, 300), area(240, 240), area(200, 200), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://download.brother.com/welcome/doch102121/882w70_om02ko.pdf"), setOf("XJ1")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-pr680w", "PR680W (한국)", MachineRegion.KOREA,
            area(300, 200), listOf(area(300, 200), area(180, 130), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://download.brother.com/welcome/doch102285/884t23_om01en.pdf"), setOf("PR680W")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-pr1050x", "PR1050X (한국)", MachineRegion.KOREA,
            area(360, 200), listOf(area(360, 200), area(180, 130), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), true,
            listOf("https://download.brother.com/welcome/doch100893/pr1050x_om01ko.pdf"), setOf("PR1050X")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-pr1060w", "PR1060W (한국)", MachineRegion.KOREA,
            area(360, 200), listOf(area(360, 200), area(180, 130), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://download.brother.com/welcome/doch101931/884t15-16_om03_en.pdf"), setOf("PR1060W")),
        // Manual accessory/embroidery-field tables: H×W converted to W×H; PR tables explicitly W×H. No nominal-inch-to-mm conversion.
        BrotherMachineFacts("kr-pr1x", "PR1X (한국)", MachineRegion.KOREA,
            area(300, 200), listOf(area(300, 200), area(180, 130), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), true,
            listOf("https://download.brother.com/welcome/doch103024/882t52_om01ko.pdf"), setOf("PR1X")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("pe770", "Brother PE770", MachineRegion.US,
            area(130, 180), listOf(area(130, 180)), setOf(MachineTransfer.USB_FLASH_DRIVE), null,
            listOf("https://www.brother-usa.com/p/sewing-embroidery-combo/PE770"), setOf("PE770")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("pe800", "Brother PE800", MachineRegion.US,
            area(130, 180), listOf(area(130, 180), area(100, 100), area(60, 20)), setOf(MachineTransfer.USB_FLASH_DRIVE), null,
            listOf("https://download.brother.com/welcome/doch100741/888l90_l91_l93_k90_k92_om03en.pdf", "https://www.brother-usa.com/p/embroidery/PE800"), setOf("PE800")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("se1900", "Brother SE1900", MachineRegion.US,
            area(130, 180), listOf(area(130, 180)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), null,
            listOf("https://www.brother-usa.com/p/sewing-embroidery/SE1900"), setOf("SE1900")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("se2000", "Brother SE2000", MachineRegion.US,
            area(130, 180), listOf(area(130, 180)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://www.brother-usa.com/p/sewing-embroidery/SE2000"), setOf("SE2000")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("pe900", "Brother PE900", MachineRegion.US,
            area(130, 180), listOf(area(130, 180)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://www.brother-usa.com/p/sewing-embroidery/PE900"), setOf("PE900")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("ns2750d", "Brother NS2750D", MachineRegion.US,
            area(130, 180), listOf(area(130, 180), area(100, 100), area(60, 20)), null, null,
            listOf("https://download.brother.com/welcome/doch100730/888l01_l03_k00_02_om03en.pdf", "https://www.brother-usa.com/p/sewing-embroidery-combo/NS2750D"), setOf("NS2750D")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("nq1400e", "Brother NQ1400E", MachineRegion.US,
            area(160, 260), listOf(area(160, 260)), setOf(MachineTransfer.USB_FLASH_DRIVE), null,
            listOf("https://www.brother-usa.com/p/sewing-embroidery/NQ1400E"), setOf("NQ1400E")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("nq1600e", "Brother NQ1600E", MachineRegion.US,
            area(160, 260), listOf(area(160, 260)), setOf(MachineTransfer.USB_FLASH_DRIVE), true,
            listOf("https://www.brother-usa.com/p/sewing-embroidery/NQ1600E"), setOf("NQ1600E")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("nq1700e", "Brother NQ1700E", MachineRegion.US,
            area(160, 260), listOf(area(160, 260)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://www.brother-usa.com/p/sewing-embroidery/NQ1700E"), setOf("NQ1700E")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("nq3550w", "Brother NQ3550W", MachineRegion.US,
            area(160, 260), listOf(area(160, 260)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://www.brother-usa.com/p/sewing-embroidery-combo/NQ3550W"), setOf("NQ3550W")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("xe1", "Brother XE1", MachineRegion.US,
            area(240, 360), listOf(area(240, 360)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://www.brother-usa.com/p/sewing-embroidery-combo/XE1"), setOf("XE1")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("xj1", "Brother XJ1", MachineRegion.US,
            area(240, 360), listOf(area(240, 360), area(130, 180), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), null,
            listOf("https://download.brother.com/welcome/doch102121/882w70_om02ko.pdf", "https://www.brother-usa.com/p/sewing-embroidery-combo/XJ1"), setOf("XJ1")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("xp1", "Brother XP1", MachineRegion.US,
            area(272, 408), listOf(area(272, 408), area(240, 240), area(130, 180), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), null,
            listOf("https://www.brother-usa.com/p/sewing-embroidery-combo/XP1", "https://support.brother.com/g/b/faqend.aspx?c=us&lang=en&prod=hf_inovxp3eus&faqid=faqh00101388_000"), setOf("XP1")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("xp2", "Brother XP2", MachineRegion.US,
            area(272, 408), listOf(area(272, 408), area(240, 240), area(130, 180), area(100, 100)), setOf(MachineTransfer.BROTHER_WIRELESS), null,
            listOf("https://www.brother-usa.com/p/sewing-embroidery-combo/XP2", "https://support.brother.com/g/b/faqend.aspx?c=us&lang=en&prod=hf_inovxp3eus&faqid=faqh00101388_000"), setOf("XP2")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("xp3", "Brother XP3", MachineRegion.US,
            area(272, 408), listOf(area(272, 408), area(240, 240), area(130, 180), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), null,
            listOf("https://www.brother-usa.com/p/sewing-embroidery-combo/XP3", "https://support.brother.com/g/b/faqend.aspx?c=us&lang=en&prod=hf_inovxp3eus&faqid=faqh00101388_000"), setOf("XP3")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("ev1", "Brother EV1", MachineRegion.US,
            area(297, 465), listOf(area(297, 465), area(272, 272), area(130, 180), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://www.brother-usa.com/p/sewing-embroidery-combo/EV1"), setOf("EV1")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("pr680w", "Brother PR680W", MachineRegion.US,
            area(300, 200), listOf(area(300, 200), area(180, 130), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://download.brother.com/welcome/doch102285/884t23_om01en.pdf", "https://www.brother-usa.com/p/sewing-embroidery/PR680W"), setOf("PR680W")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("pr1050x", "Brother PR1050X", MachineRegion.US,
            area(360, 200), listOf(area(360, 200), area(180, 130), area(100, 100)), setOf(MachineTransfer.USB_FLASH_DRIVE), null,
            listOf("https://download.brother.com/welcome/doch100893/pr1050x_om01ko.pdf", "https://www.brother-usa.com/p/sewing-embroidery/PR1050X"), setOf("PR1050X")),
        // Individual US product page; operation-manual H×W orientation (PR W×H). XP official FAQ names XP1/XP2/XP3; EV1 carton lists H465×W297. Unknown cutters/limits are not inferred from automatic thread cutter.
        BrotherMachineFacts("prs100", "Brother PRS100", MachineRegion.US,
            area(200, 200), listOf(area(200, 200)), setOf(MachineTransfer.USB_FLASH_DRIVE, MachineTransfer.BROTHER_WIRELESS), true,
            listOf("https://www.brother-usa.com/p/sewing-embroidery/PRS100"), setOf("PRS100")),
    )
}
