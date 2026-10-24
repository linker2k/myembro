#!/usr/bin/env python3
"""Original synthetic emoji only. No external or personal images."""
import os, sys
from PIL import Image, ImageDraw
out = sys.argv[1] if len(sys.argv) > 1 else '/tmp/myembro-port-out/editor/image-to-design/inputs'
os.makedirs(out, exist_ok=True)
for name in ['face', 'robot']:
    im = Image.new('RGBA', (128, 128), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if name == 'face':
        d.ellipse((12, 12, 115, 115), fill='#F6C541')
        d.rectangle((37, 38, 43, 53), fill='#252525')
        d.rectangle((84, 38, 90, 53), fill='#252525')
        d.rectangle((48, 83, 79, 85), fill='#B34D43')
    else:
        d.rectangle((24, 40, 103, 111), fill='#2589AD')
        d.rectangle((60, 13, 67, 37), fill='#D64E71')
        d.rectangle((40, 61, 47, 76), fill='#252525')
        d.rectangle((80, 61, 87, 76), fill='#252525')
        d.rectangle((48, 92, 79, 94), fill='#D64E71')
    im.save(os.path.join(out, name + '.png'))
print(out)
