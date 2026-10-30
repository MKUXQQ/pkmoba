"""Extra Blaziken sheets: the thrown kick, the low fire wall and a flame plume.

Run from the project root: python tools/generate_blaziken_v3.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path("src/main/resources/assets/pkmoba/textures/effect")
SIZE = 64

CORE = (255, 252, 232, 255)
HOT = (255, 226, 128, 255)
GOLD = (255, 190, 52, 255)
ORANGE = (255, 136, 26, 255)
DEEP = (208, 72, 10, 255)
DARK = (86, 32, 8, 255)


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def tongue(d: ImageDraw.ImageDraw, x: int, base: int, w: int, h: int,
           colours=(ORANGE, GOLD, HOT)) -> None:
    """One flame tongue: wide dark base, narrower mid, hot tip."""
    layers = ((w, colours[0]), (max(2, w - 3), colours[1]), (max(1, w // 3), colours[2]))
    for i, (width, colour) in enumerate(layers):
        top = base - h + i * max(1, h // 4)
        left = x - width // 2
        d.polygon([(left, base), (x, top), (left + width, base)], fill=colour)


def foot() -> Image.Image:
    """A burning Blaziken foot flying to the right, flame plume trailing left."""
    img, d = canvas()
    # plume behind the leg
    tongue(d, 12, 46, 18, 30, (DEEP, ORANGE, GOLD))
    tongue(d, 20, 48, 14, 22, (ORANGE, GOLD, HOT))
    tongue(d, 27, 50, 9, 14, (GOLD, HOT, CORE))
    # shin
    d.rectangle([30, 12, 44, 42], fill=DARK)
    d.rectangle([32, 14, 42, 42], fill=(250, 226, 176, 255))
    d.rectangle([33, 16, 37, 40], fill=(255, 244, 214, 255))
    # foot
    d.rectangle([44, 34, 58, 46], fill=DARK)
    d.rectangle([44, 35, 57, 44], fill=(250, 226, 176, 255))
    d.rectangle([45, 36, 57, 39], fill=(255, 244, 214, 255))
    # talons
    for x in (46, 51, 56):
        d.rectangle([x, 44, x + 2, 50], fill=DARK)
        d.rectangle([x, 44, x + 1, 49], fill=GOLD)
    # flame licking off the leg and heel
    tongue(d, 34, 16, 7, 14, (ORANGE, GOLD, CORE))
    tongue(d, 41, 34, 6, 10, (ORANGE, GOLD, HOT))
    d.rectangle([58, 26, 61, 29], fill=HOT)
    d.rectangle([55, 20, 57, 23], fill=CORE)
    return img


def wall() -> Image.Image:
    """Seamless low fire wall: hot base, staggered tongues, cool top edge."""
    img, d = canvas()
    for x in range(0, SIZE, 16):
        tongue(d, x + 4, 60, 18, 26, (DEEP, ORANGE, GOLD))
        tongue(d, x + 12, 60, 14, 34, (ORANGE, GOLD, HOT))
    # seamless horizontal band along the base
    d.rectangle([0, 44, SIZE, 60], fill=ORANGE)
    d.rectangle([0, 50, SIZE, 62], fill=GOLD)
    d.rectangle([0, 56, SIZE, 63], fill=HOT)
    # break the band into ember chunks so it reads as fire and not a bar
    for x in range(2, SIZE, 9):
        d.rectangle([x, 40, x + 3, 46], fill=DEEP)
        d.rectangle([x + 4, 34, x + 6, 40], fill=ORANGE)
    return img


def flame() -> Image.Image:
    img, d = canvas()
    tongue(d, 32, 56, 26, 40, (DEEP, ORANGE, GOLD))
    tongue(d, 32, 56, 16, 30, (ORANGE, GOLD, HOT))
    tongue(d, 32, 56, 7, 18, (GOLD, HOT, CORE))
    d.rectangle([16, 52, 48, 60], fill=ORANGE)
    d.rectangle([22, 56, 42, 62], fill=GOLD)
    return img


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, image in (("blaziken_foot", foot()), ("blaziken_wall", wall()),
                        ("blaziken_flame", flame())):
        image.save(OUT / f"{name}.png")
        print("wrote", OUT / f"{name}.png")


if __name__ == "__main__":
    main()
