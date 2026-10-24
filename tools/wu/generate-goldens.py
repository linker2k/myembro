#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fill only Wu's original main() input/output placeholders; no algorithm edits or downloads."""
import hashlib
import pathlib
import subprocess
import sys

source = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else '/tmp/myembro-port-out/editor/wu')
root = pathlib.Path(__file__).resolve().parents[2]
raw = source.read_bytes()
assert hashlib.sha256(raw).hexdigest() == '38b0963722bc3346b18ba58695c87bb7beeb375278f1abd0af05e46933d2a8f6'
text = raw.decode()
input_stub = '''/* input R,G,B components into Ir, Ig, Ib;
	   set size to width*height */'''
output_stub = '''/* output lut_r, lut_g, lut_b as color look-up table contents,
	   Qadd as the quantized image (array of table addresses). */'''
assert text.count(input_stub) == 1 and text.count(output_stub) == 1
text = text.replace(input_stub, '''/* Test-only input, original main algorithm unchanged. */
        if (scanf("%d", &size) != 1 || size < 1) return 2;
        Ir = malloc(size); Ig = malloc(size); Ib = malloc(size);
        if (!Ir || !Ig || !Ib) return 3;
        for (i=0; i<size; ++i) {
            unsigned int rgb;
            if (scanf("%x", &rgb) != 1) return 4;
            Ir[i] = (rgb >> 16) & 255;
            Ig[i] = (rgb >> 8) & 255;
            Ib[i] = rgb & 255;
        }''')
text = text.replace(output_stub, r'''/* Test-only output of the original LUT and Qadd, no reconstructed palette. */
        printf("{\"upstream\":\"ca4898517a93b6b45444a9cda5fd50132694b864\",\"palette\":[");
        for (k=0; k<K; ++k) printf("%s%u", k ? "," : "", ((unsigned)lut_r[k]<<16)|((unsigned)lut_g[k]<<8)|lut_b[k]);
        printf("],\"indices\":[");
        for (i=0; i<size; ++i) printf("%s%u", i ? "," : "", Qadd[i]);
        printf("]}\n");
        free(tag); free(Qadd);
        return 0;''')
out.mkdir(parents=True, exist_ok=True)
instrumented = out / 'wu-io.c'
instrumented.write_text(text)
exe = out / 'wu-oracle'
subprocess.run(['cc', '-std=gnu89', '-O0', '-ffp-contract=off', str(instrumented), '-lm', '-o', str(exe)], check=True)
for name in ['primaries', 'gradient', 'bucket-boundaries']:
    fixture = root / 'engine/src/test/resources/wu' / (name + '.rgb')
    result = subprocess.run([str(exe)], input=fixture.read_text(), text=True, capture_output=True, check=True)
    golden = next(line for line in result.stdout.splitlines() if line.startswith('{'))
    (out / (name + '.json')).write_text(golden + '\n')
    print(name, 'original C output:', out / (name + '.json'))
