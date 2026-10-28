# myembro FAQ

**English** | [한국어](faq.ko.md)

1. [Why do shadows or light reflections in a photo become separate color blocks?](#1-why-do-shadows-or-light-reflections-in-a-photo-become-separate-color-blocks)
2. [Will there be gaps between colors?](#2-will-there-be-gaps-between-colors)
3. [Why is each area outlined again, and why do some areas have no outline?](#3-why-is-each-area-outlined-again-and-why-do-some-areas-have-no-outline)
4. [Why is the largest size 99.9mm and not 100mm?](#4-why-is-the-largest-size-999mm-and-not-100mm)
5. [What if photo embroidery does not finish within 1 minute?](#5-what-if-photo-embroidery-does-not-finish-within-1-minute)
6. [Why do I have to reinstall to move to the beta?](#6-why-do-i-have-to-reinstall-to-move-to-the-beta)
7. [Why do I cut the thread by hand on 4×4-inch machines?](#7-why-do-i-cut-the-thread-by-hand-on-44-inch-machines)
8. [Why can't I freely change the stitch type and density?](#8-why-cant-i-freely-change-the-stitch-type-and-density)
9. [Why are PES files from other programs sometimes blocked and sometimes not?](#9-why-are-pes-files-from-other-programs-sometimes-blocked-and-sometimes-not)

## 1. Why do shadows or light reflections in a photo become separate color blocks?

Color drawing splits the picture into a few colors. A shadow or a shiny spot is darker or lighter than the area around it, so it is often counted as a different color and sewn as its own block. For the cleanest result, use a flat drawing without shadows, or lower the **Colors** count.

## 2. Will there be gaps between colors?

Where two colors meet, they overlap by 0.2mm to reduce gaps between them. The outer size of the design does not change.

## 3. Why is each area outlined again, and why do some areas have no outline?

In color drawings and silhouettes, after each area is filled, the app sews one running outline in the same color along its edge. This makes the edges look clean. If an outline would fail the sewing check, the app leaves that outline out automatically.

## 4. Why is the largest size 99.9mm and not 100mm?

4×4-inch machines such as the SE400 have a 100×100mm hoop. Stitch positions are counted in 0.1mm steps starting at 0, so a 100mm hoop has positions 0 to 999. The app keeps every stitch within these positions, so the largest size it makes is 99.9mm. The same rule applies to every hoop: the largest size is always 0.1mm less than the hoop.

## 5. What if photo embroidery does not finish within 1 minute?

Making the stitches stops after 1 minute, and the app says it took too long. Tap **Lower detail**, or go back and crop a smaller area. In color drawing you can also lower the **Colors** count.

## 6. Why do I have to reinstall to move to the beta?

From the beta on, the app is signed with a new key. Android does not allow installing it over the old version, so you reinstall once:

1. In the old app, open **Settings → Backup** and tap **Save backup**. Keep the ZIP outside the app.
2. Uninstall the old myembro.
3. Install the beta.
4. Open **Settings → Backup**, tap **Restore ZIP** and pick your backup.

After that, allow USB folder access again.

## 7. Why do I cut the thread by hand on 4×4-inch machines?

Machines with a 100×100mm (4×4-inch) hoop do not cut jump threads automatically. This includes the SE400, SE600, SE700, SE425, SE625, SE630, SE725, PE535, PE545, PE550D and NS1750D, and in Korea the M370, M380D, LB6950, LB7950, LB8950 and Innovis 180, 900, 950 and 980. Threads that jump between positions stay connected, so trim them by hand after sewing. For these machines the app shows guidance for this.

## 8. Why can't I freely change the stitch type and density?

To make designs that sew without failures, the app chooses the stitch type and settings automatically. In **Edit blocks** you can change only what is safe: **Stitch type**, **Texture**, **Direction** and **Sewing order**. **Density** is Normal for now; dense and sparse are coming in a later version.

## 9. Why are PES files from other programs sometimes blocked and sometimes not?

The app does not change PES files you import; they are kept exactly as they are. It only blocks a severe case: more than 30 needle stitches packed into one 0.5mm spot. Designs the app makes itself are checked more strictly.
