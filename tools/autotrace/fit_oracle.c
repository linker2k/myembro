/* SPDX-License-Identifier: GPL-3.0-or-later
 * Test-only caller for unmodified AutoTrace src/{thin-image,pxl-outline,fit,curve,spline,vector}.c@1a232b27818a46a05cd25edec09e2f77c4e9ac01.
 * Upstream fit.c: GPL-2.0-or-later, Copyright (C) 1992 Free Software Foundation, Inc.
 * Upstream thin-image.c: LGPL-2.1-or-later, Copyright (C) 2001, 2002 Martin Weber.
 * No algorithm is implemented or replaced here. Input P2 -> original fitted centerline splines (Cartesian pixels).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "thin-image.h"
#include "pxl-outline.h"
#include "fit.h"

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
    fitting_opts_type options = new_fitting_opts();
    options.centerline = TRUE;
    /* Constant satin width is supplied separately; preserve_width remains its original FALSE. */
    spline_list_array_type fitted = fitted_splines(paths, &options, NULL, width, height,
        &exception, NULL, NULL, NULL, NULL);
    if (at_exception_got_fatal(&exception)) return 7;
    printf("{\"schema\":1,\"width\":%u,\"height\":%u,\"paths\":[", width,height);
    for (unsigned i=0;i<fitted.length;i++) {
        at_spline_list_type list=fitted.data[i];
        printf("%s{\"open\":%s,\"clockwise\":%s,\"segments\":[",i?",":"",list.open?"true":"false",list.clockwise?"true":"false");
        for (unsigned j=0;j<list.length;j++) {
            at_spline_type spline=list.data[j];
            printf("%s{\"degree\":%u,\"v\":[",j?",":"",spline.degree);
            for(unsigned k=0;k<4;k++) printf("%s[%.9g,%.9g,%.9g]",k?",":"",spline.v[k].x,spline.v[k].y,spline.v[k].z);
            printf("]}");
        }
        printf("]}");
    }
    printf("]}\n");
    free_spline_list_array(&fitted);
    free_pixel_outline_list(&paths);
    g_free(bitmap.bitmap);
    return 0;
}
