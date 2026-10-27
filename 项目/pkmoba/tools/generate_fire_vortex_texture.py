from PIL import Image, ImageFilter
import math
from pathlib import Path

# Periodic in U so the connected funnel has no vertical seam.
size = 256
im = Image.new('RGBA', (size, size))
pix = im.load()
for y in range(size):
    v = y / (size - 1)
    for x in range(size):
        u = x / (size - 1)
        flow = u * math.tau * 7 - v * 18
        warp = math.sin(u * math.tau * 3 + v * 11) * 0.75
        crest = max(0.0, math.sin(flow + warp)) ** 5
        fine = max(0.0, math.sin(u * math.tau * 17 - v * 35)) ** 14
        turbulence = (math.sin(u * math.tau * 11 + v * 21) + 1) * 0.5
        heat = min(1.0, 0.18 + crest * 0.7 + fine * 0.22)
        rim = min(1.0, v * 12, (1-v) * 9)
        # Soft persistent base links the bright curved streaks into a coherent wall.
        opacity = rim * (0.26 + 0.52 * crest + 0.13 * turbulence)
        pix[x, y] = (255, int(64 + heat * 180), int(12 + heat * 98), int(255 * opacity))
im = im.filter(ImageFilter.GaussianBlur(0.65))
out = Path('src/main/resources/assets/pkmoba/textures/effect/fire_vortex_veil.png')
out.parent.mkdir(parents=True, exist_ok=True)
im.save(out, optimize=True)
print(f'{out}: {im.size}, {out.stat().st_size} bytes')
