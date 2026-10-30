"""Restore the classic v6 Moonblast moon skin (bluish-white planet with craters).

The v7 skin was repainted almost pure white and washed the volume out; the version the user
likes is this older equirect skin, so it is generated as its own texture and used only by the
moon sphere while the rest of the kit keeps the current sheets.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/pkmoba'
TEX = ROOT / 'textures/effect'

WHITE = (255, 250, 255, 255)
PALE = (255, 238, 250, 255)


def moon_classic():
    img = Image.new('RGBA', (64, 64), PALE)
    d = ImageDraw.Draw(img)
    for y in range(0, 64, 8):
        shade = (250, 234, 250, 255) if (y // 8) % 2 == 0 else (242, 222, 246, 255)
        d.rectangle([0, y, 63, y + 7], fill=shade)
    craters = ((8, 8, 4), (22, 16, 3), (36, 6, 5), (50, 18, 3), (12, 30, 5), (28, 26, 3),
               (44, 32, 4), (56, 40, 3), (6, 46, 4), (20, 52, 5), (34, 44, 3), (48, 56, 4))
    for x, y, r in craters:
        d.ellipse([x - r, y - r, x + r, y + r], fill=(228, 204, 240, 255))
        d.ellipse([x - r + 1, y - r + 1, x + r - 1, y + r - 1], fill=(212, 184, 232, 255))
        d.arc([x - r, y - r, x + r, y + r], 200, 340, fill=(255, 250, 255, 255), width=1)
    for x, y in ((15, 20), (31, 34), (52, 8), (40, 50), (4, 58)):
        d.rectangle([x, y, x + 1, y + 1], fill=WHITE)
    # a soft bloom layer so the sphere itself still glows a little in the dark
    glow = img.filter(ImageFilter.GaussianBlur(3))
    out = Image.new('RGBA', (64, 64))
    out.alpha_composite(glow)
    out.alpha_composite(img)
    return out


def main():
    TEX.mkdir(parents=True, exist_ok=True)
    moon_classic().save(TEX / 'gardevoir_moon_classic.png')
    print('wrote gardevoir_moon_classic.png')


if __name__ == '__main__':
    main()
