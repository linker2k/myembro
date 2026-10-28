# myembro image assets

Warm linen, wooden embroidery hoops, and three terracotta satin-stitch curves form the shared motif.
The four original artworks were created with the built-in `image_gen` tool. Full prompts and reference-image relationships are
recorded in [generation-prompts.json](generation-prompts.json). Generated results were resampled to final sizes,
preserving alpha channels in transparent images.

| File | Size / format | Description and purpose |
| --- | --- | --- |
| [launcher-icon-artwork.png](launcher-icon-artwork.png) | 1024×1024, RGB PNG | Wooden hoop and bold satin stitches on linen. Source artwork for the launcher vector, not a bitmap placed directly in the adaptive icon. |
| [readme-banner.png](readme-banner.png) | 1024×500, RGB PNG | App name/introduction on the left, hoop on the right. Used at the top of the repository README. The image still says "Open-source embroidery viewer for Android"; this will change at the next image refresh. Until then, README alt text describes current features. |
| [play-feature-graphic.png](play-feature-graphic.png) | 1024×500, RGB PNG | Hoop on the left, app name/PES viewer introduction on the right. Opaque feature graphic for Play Store listing. |
| [empty-state.png](empty-state.png) | 1024×1024, RGBA PNG | Empty linen hoop without a design and a spool of thread. Original empty-state illustration with no lettering and a transparent outer background. The app uses the WebP derivative below. |
| [launcher-icon-preview.png](launcher-icon-preview.png) | 960×360, RGB PNG | Circular, rounded-square, monochrome, and 48px previews rendered from the XML vector. A resource-review image, not a generated image. |
| [example-logo-flat.png](example-logo-flat.png) | 1024×1024, RGB PNG | The launcher icon (`ic_launcher_foreground.xml` on `ic_launcher_background`) rendered flat with Rsvg: the visible 72dp area (18–90dp), no mask. Input picture for the user guide examples and the intro video. |

## SE400 direct USB guide

`usb-direct-en/ko.gif` and `.mp4` (1280×720, about 1 minute, no audio) and `manual/{en,ko}/usb-direct-01…09.jpg` were made from photos, phone screenshots and a short SE400 screen video taken by the user on 2026-10-28 (Galaxy Fold7, English UI). Phone screenshots are cropped to the dialogs and buttons; the home screen and other designs are cut out. Red marks and captions were added with Pillow and ffmpeg.

## Launcher resources

- `app/src/main/res/drawable/ic_launcher_foreground.xml`: 108×108dp vector simplifying the original hoop and stitch curves.
- `app/src/main/res/values/colors.xml`: sets `ic_launcher_background` to linen `#F5EDDF`.
- Foreground fits inside a circle centered at (54, 54) with diameter 66dp. Transferring XML paths to SVG and rendering at 10times resolution yielded a maximum radius of approximately **30.54dp**, including antialiased pixels(allowed radius 33dp).
- Hoop center is transparent and shapes are opaque. The existing adaptive icon's `<monochrome>` references the same foreground, preserving the hoop opening and stitch silhouette under system tinting.
- Previews are static review material rendered from XML paths/colors/strokes using Rsvg. They are not actual device screenshots.

Safe area and monochrome layers reference the [official Android adaptive icon documentation](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

## Validation

- `./gradlew :app:assembleDebug`: successful.
- Checked PNG decoding, dimensions, and color modes. Play feature graphic is 1024×500 RGB without an alpha channel.
- Confirmed actual transparency in the empty-state illustration: alpha minimum 0, maximum 255.
- Visually checked circular/rounded-square masks and the 48px monochrome preview.

Assets in this directory follow the repository's GPL-3.0-or-later license.

## App empty-state derivative

`app/src/main/res/drawable-nodpi/empty_state.webp` was made by resampling original `empty-state.png`
to 512×512 with Lanczos and encoding as lossless WebP(178,466bytes,
300KB or less). Confirmed byte-for-byte equality between alpha channels of the resampled image and decoded WebP.
Displayed at 160dp in `EmptyState`, with vertical scrolling on small screens.
Converted existing artwork without generating a new image.
