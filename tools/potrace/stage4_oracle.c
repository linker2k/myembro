/* SPDX-License-Identifier: GPL-3.0-or-later
 * Small test-only caller of the unmodified Potrace 1.16 API; no tracing algorithm here.
 * Copyright (c) 2026 myembro contributors.
 */
#include <stdio.h>
#include <stdlib.h>
#include "potracelib.h"
#include "curve.h"
#include "bitmap.h"

int main(int argc, char **argv) {
  if (argc != 2 && argc != 3) return 2;
  int optimized = argc == 3 ? atoi(argv[2]) : 0;
  if (optimized != 0 && optimized != 1) return 2;
  FILE *input = fopen(argv[1], "r");
  int w, h, value;
  if (!input || fscanf(input, "P1 %d %d", &w, &h) != 2 || w <= 0 || h <= 0) return 3;
  potrace_bitmap_t bm = {w, h, (w + BM_WORDBITS - 1) / BM_WORDBITS, NULL};
  bm.map = calloc((size_t)bm.dy * h, sizeof(potrace_word));
  if (!bm.map) return 4;
  for (int y = h - 1; y >= 0; y--) for (int x = 0; x < w; x++) {
    if (fscanf(input, "%d", &value) != 1 || (value != 0 && value != 1)) return 5;
    BM_UPUT(&bm, x, y, value);
  }
  fclose(input);
  potrace_param_t *param = potrace_param_default();
  if (!param) return 6;
  /* Stage 4 disables opticurve; stage 5 retains the upstream default tolerance 0.2. */
  param->opticurve = optimized;
  potrace_state_t *state = potrace_trace(param, &bm);
  if (!state || state->status != POTRACE_STATUS_OK) return 7;
  printf("{\"upstream\":\"886392d351417d6753aff61de08e294d1506e502\",\"opticurve\":%s,\"paths\":[", optimized ? "true" : "false");
  int first = 1;
  for (potrace_path_t *path = state->plist; path; path = path->next) {
    potrace_privpath_t *p = path->priv;
    privcurve_t *curve = p->fcurve;
    printf("%s{\"positive\":%s,\"area\":%d,\"boundary\":[", first ? "" : ",", path->sign == '+' ? "true" : "false", path->area);
    first = 0;
    for (int i = 0; i < p->len; i++) printf("%s[%ld,%ld]", i ? "," : "", p->pt[i].x, p->pt[i].y);
    printf("],\"polygon\":[");
    for (int i = 0; i < p->m; i++) printf("%s%d", i ? "," : "", p->po[i]);
    printf("],\"vertices\":[");
    for (int i = 0; i < curve->n; i++) printf("%s[%.17g,%.17g]", i ? "," : "", curve->vertex[i].x, curve->vertex[i].y);
    printf("],\"segments\":[");
    for (int i = 0; i < curve->n; i++) {
      printf("%s{\"tag\":%d,\"controls\":[", i ? "," : "", curve->tag[i]);
      for (int j = 0; j < 3; j++) printf("%s[%.17g,%.17g]", j ? "," : "", curve->c[i][j].x, curve->c[i][j].y);
      printf("],\"alpha\":%.17g,\"alpha0\":%.17g,\"beta\":%.17g}", curve->alpha[i], curve->alpha0[i], curve->beta[i]);
    }
    printf("]}");
  }
  printf("]}\n");
  potrace_state_free(state);
  potrace_param_free(param);
  free(bm.map);
  return 0;
}
