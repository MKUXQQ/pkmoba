"""Gardevoir v5 art: five clean sheets + four icons.

The v4 kit used nine different sheets (clocks, stars, runes, shards, rays), which in game read as
a pile of unrelated stickers. v5 keeps one visual language - glowing pixel RINGS, one MOON, one
SOUND ARC, one GHOST silhouette and one soft GLOW - the same way Pikachu uses bands and bolts.
"""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/pkmoba'
TEX = ROOT / 'textures/effect'
ICON = ROOT / 'textures/gui/skills'

WHITE = (255, 250, 255, 255)
PALE = (255, 222, 246, 255)
PINK = (255, 140, 208, 255)
MAGENTA = (226, 88, 176, 255)
DEEP = (120, 40, 108, 255)
FRAME = (206, 160, 238, 255)
FRAME_DARK = (78, 52, 108, 255)
BG = (30, 22, 44, 255)


def canvas():
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def finish(img):
    """Clip to the 64x64 cell and keep edges crisp - no blur, no bloom, pure pixel art."""
    out = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    out.alpha_composite(img)
    return out


def ring_sheet():
    img, d = canvas()
    cx = cy = 32
    for radius, width, colour in ((26, 7, MAGENTA), (26, 5, PINK), (26, 2, WHITE), (17, 2, (255, 190, 236, 190))):
        d.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], outline=colour, width=width)
    # stepped pixel notches so the ring keeps the blocky MC rhythm instead of reading as vector
    for i in range(12):
        from math import cos, sin, tau
        a = tau * i / 12
        x = cx + int(round(cos(a) * 26))
        y = cy + int(round(sin(a) * 26))
        d.rectangle([x - 2, y - 2, x + 2, y + 2], fill=WHITE)
    return finish(img)


def moon_sheet():
    img, d = canvas()
    d.ellipse([6, 6, 57, 57], fill=DEEP)
    d.ellipse([9, 9, 54, 54], fill=PALE)
    d.ellipse([14, 12, 50, 46], fill=WHITE)
    for x, y, r in ((22, 22, 5), (38, 30, 4), (30, 42, 3), (42, 17, 3)):
        d.ellipse([x - r, y - r, x + r, y + r], fill=(226, 214, 244, 255))
    for x, y in ((16, 40), (46, 42), (34, 14)):
        d.rectangle([x, y, x + 1, y + 1], fill=WHITE)
    return finish(img)


def sound_sheet():
    """A thick crescent arc - stacked copies read as a spreading sound wave."""
    img, d = canvas()
    for radius, width, colour in ((27, 8, MAGENTA), (27, 6, PINK), (27, 3, WHITE)):
        d.arc([32 - radius, 32 - radius, 32 + radius, 32 + radius], 200, 340, fill=colour, width=width)
    for i in range(5):
        x = 12 + i * 10
        d.rectangle([x, 40 + (i % 2), x + 2, 42 + (i % 2)], fill=PALE)
    return finish(img)


def ghost_sheet():
    """Gardevoir silhouette: side crests, slim body, flared dress - readable at 1.5 blocks tall."""
    img, d = canvas()
    body = [(32, 5), (40, 9), (42, 17), (38, 22), (44, 30), (52, 52), (40, 57), (32, 50),
            (24, 57), (12, 52), (20, 30), (26, 22), (22, 17), (24, 9)]
    d.polygon(body, fill=(255, 236, 250, 235))
    d.polygon([(32, 9), (37, 12), (36, 19), (32, 22), (28, 19), (27, 12)], fill=PINK)
    d.polygon([(21, 8), (26, 12), (25, 20), (20, 17)], fill=MAGENTA)
    d.polygon([(43, 8), (38, 12), (39, 20), (44, 17)], fill=MAGENTA)
    d.rectangle([29, 33, 35, 43], fill=(226, 88, 176, 255))
    d.polygon([(32, 43), (39, 55), (25, 55)], fill=(255, 200, 236, 220))
    return finish(img)


def glow_sheet():
    img, d = canvas()
    for radius, colour in ((30, (226, 88, 176, 90)), (24, (255, 150, 214, 140)),
                           (17, (255, 210, 240, 190)), (10, (255, 245, 255, 230))):
        d.ellipse([32 - radius, 32 - radius, 32 + radius, 32 + radius], fill=colour)
    return finish(img)


def icon_frame():
    img = Image.new('RGBA', (32, 32), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], outline=FRAME, width=2)
    for x, y in ((0, 0), (29, 0), (0, 29), (29, 29)):
        d.rectangle([x, y, x + 2, y + 2], fill=FRAME)
    d.rectangle([2, 2, 29, 29], outline=FRAME_DARK, width=1)
    return img, d


def icon_psychic():
    img, d = icon_frame()
    for radius, width, colour in ((11, 3, MAGENTA), (11, 2, PINK), (7, 2, (255, 190, 236, 255)), (3, 3, WHITE)):
        d.ellipse([16 - radius, 16 - radius, 16 + radius, 16 + radius], outline=colour, width=width)
    d.rectangle([15, 15, 17, 17], fill=WHITE)
    return img


def icon_moon():
    img, d = icon_frame()
    d.ellipse([3, 4, 28, 29], fill=(214, 170, 240, 90))
    d.ellipse([6, 6, 25, 25], fill=PALE)
    d.ellipse([9, 8, 24, 22], fill=WHITE)
    for x, y, r in ((12, 12, 2), (19, 16, 2), (14, 20, 1)):
        d.ellipse([x - r, y - r, x + r, y + r], fill=(212, 202, 238, 255))
    for x, y in ((22, 8), (25, 12), (20, 24)):
        d.rectangle([x, y, x + 1, y + 1], fill=MAGENTA)
    return img


def icon_teleport():
    img, d = icon_frame()
    for i, x in enumerate((5, 9, 13)):
        d.polygon([(x, 12), (x + 4, 16), (x, 20)], fill=(255, 170, 224, 220 - i * 50))
    d.polygon([(17, 4), (22, 7), (23, 13), (20, 15), (25, 21), (29, 27), (22, 29), (17, 25),
               (13, 29), (7, 27), (12, 21), (15, 15), (12, 12), (13, 7)], fill=(255, 240, 252, 245))
    d.rectangle([18, 12, 20, 14], fill=MAGENTA)
    d.rectangle([22, 12, 24, 14], fill=MAGENTA)
    d.rectangle([19, 16, 23, 21], fill=(226, 88, 176, 255))
    d.polygon([(17, 21), (23, 21), (27, 28), (13, 28)], fill=(255, 214, 244, 235))
    return img


def icon_hyper_voice():
    img, d = icon_frame()
    d.rectangle([13, 22, 19, 27], fill=WHITE)
    d.rectangle([13, 21, 19, 22], fill=MAGENTA)
    for radius, width, colour in ((14, 3, MAGENTA), (14, 2, PINK), (10, 2, WHITE)):
        d.arc([16 - radius, 26 - radius, 16 + radius, 26 + radius], 205, 335, fill=colour, width=width)
    for x in (5, 27):
        d.rectangle([x, 10, x + 1, 15], fill=PALE)
    return img


def main():
    TEX.mkdir(parents=True, exist_ok=True)
    ICON.mkdir(parents=True, exist_ok=True)
    for name, image in (('gardevoir_ring', ring_sheet()), ('gardevoir_moon', moon_sheet()),
                        ('gardevoir_sound', sound_sheet()), ('gardevoir_ghost', ghost_sheet()),
                        ('gardevoir_glow', glow_sheet())):
        image.save(TEX / f'{name}.png')
    for name, image in (('q', icon_psychic()), ('w', icon_moon()), ('e', icon_teleport()),
                        ('r', icon_hyper_voice())):
        image.save(ICON / f'gardevoir_{name}.png')
    print('wrote gardevoir v5: 5 sheets, 4 icons')


if __name__ == '__main__':
    main()
