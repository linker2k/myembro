# SPDX-License-Identifier: GPL-3.0-or-later
import math
from PIL import Image, ImageDraw

def drawing(name, stroke=10):
    image = Image.new('RGB', (192, 192), 'white')
    d = ImageDraw.Draw(image)
    if name == 'FLOWER':
        # Five-petal closed flower with an isolated center ring. Original parametric drawing.
        points = []
        for i in range(361):
            a = i * 2 * math.pi / 360
            r = 62 + 15 * math.cos(5*a)
            points.append((96+r*math.cos(a), 96+r*math.sin(a)))
        d.line(points, fill='black', width=stroke, joint='curve')
        d.ellipse((72,72,120,120), outline='black', width=stroke)
    else:
        # Closed house silhouette, a door and one window; no downloaded art/fonts.
        d.line([(26,90),(96,25),(166,90),(154,90),(154,166),(38,166),(38,90),(26,90)],
               fill='black', width=stroke, joint='curve')
        d.rectangle((58,112,82,138), outline='black', width=max(1, round(stroke * 0.8)))
        d.line([(106,162),(106,116),(132,116),(132,162)], fill='black', width=max(1, round(stroke * 0.8)))
    return image
