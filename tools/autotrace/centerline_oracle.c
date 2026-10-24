/* SPDX-License-Identifier: GPL-3.0-or-later
 * Test-only caller for unmodified AutoTrace src/{thin-image,pxl-outline}.c@1a232b27818a46a05cd25edec09e2f77c4e9ac01.
 * Upstream thin-image.c: LGPL-2.1-or-later, Copyright (C) 2001, 2002 Martin Weber.
 * No thinning algorithm is implemented or replaced here. Input P2 -> ordered centerline paths (Cartesian pixel coordinates).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "thin-image.h"
#include "pxl-outline.h"

gboolean logging = FALSE;
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    unsigned planes = (unsigned)atoi(argv[1]);
    if (planes != 1 && planes != 3) return 3;
    char magic[3]; unsigned width, height, maximum;
    if (scanf("%2s %u %u %u", magic, &width, &height, &maximum) != 4 ||
        strcmp(magic,"P2") || width < 3 || height < 3 || width > 512 || height > 512 || maximum != 255) return 4;
    at_bitmap bitmap;
    bitmap.width = (unsigned short)width; bitmap.height = (unsigned short)height;
    bitmap.np = planes; bitmap.bitmap = g_malloc((gsize)width * height * planes);
    for (unsigned i = 0; i < width * height; ++i) {
        unsigned value;
        if (scanf("%u", &value) != 1 || (value != 0 && value != 255)) { g_free(bitmap.bitmap); return 5; }
        for (unsigned c = 0; c < planes; ++c) bitmap.bitmap[i * planes + c] = (unsigned char)value;
    }
    at_color white = {255,255,255};
    at_exception_type exception = at_exception_new(NULL,NULL);
    thin_image(&bitmap,&white,&exception);
    if (at_exception_got_fatal(&exception)) { g_free(bitmap.bitmap); return 6; }
    pixel_outline_list_type paths = find_centerline_pixels(&bitmap, white, NULL, NULL, NULL, NULL, &exception);
    printf("%u\n", paths.length);
    for (unsigned i = 0; i < paths.length; ++i) {
        pixel_outline_type path = paths.data[i];
        printf("%u %u %u %u %u %u\n", path.open, path.clockwise, path.color.r, path.color.g, path.color.b, path.length);
        for (unsigned j = 0; j < path.length; ++j) printf("%d %d\n", path.data[j].x, path.data[j].y);
    }
    free_pixel_outline_list(&paths);
    g_free(bitmap.bitmap);
    return 0;
}
