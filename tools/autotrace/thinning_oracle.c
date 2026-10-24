/* SPDX-License-Identifier: GPL-3.0-or-later
 * Test-only caller for unmodified AutoTrace src/thin-image.c@1a232b27818a46a05cd25edec09e2f77c4e9ac01.
 * Upstream thin-image.c: LGPL-2.1-or-later, Copyright (C) 2001, 2002 Martin Weber.
 * No thinning algorithm is implemented or replaced here. Input P2 -> output P1, top-down rows.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "thin-image.h"

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
    printf("P1\n%u %u\n",width,height);
    for (unsigned y = 0; y < height; ++y) {
        for (unsigned x = 0; x < width; ++x) printf("%u%c",bitmap.bitmap[(y*width+x)*planes] == 0 ? 1 : 0,
            x + 1 == width ? '\n' : ' ');
    }
    g_free(bitmap.bitmap);
    return 0;
}
