"""Writes the Flame Mark badge texture (32x32, solid dark tile so it works with an opaque badge
render type). Run: python tools/generate_blaziken_mark.py"""
from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/pkmoba/textures/effect/blaziken_mark.png"

DARK = (58, 22, 12, 255)
FRAME = (255, 168, 68, 255)
ORANGE = (255, 136, 32, 255)
GOLD = (255, 208, 88, 255)
CORE = (255, 246, 200, 255)


def main() -> None:
    img = Image.new("RGBA", (32, 32), DARK)
    d = ImageDraw.Draw(img)
    # rounded tile
    d.rounded_rectangle([0, 0, 31, 31], radius=7, fill=DARK, outline=FRAME, width=2)
    # flame: body, tongue, hot core
    d.polygon([(16, 5), (11, 15), (12, 23), (16, 27), (21, 23), (22, 15)], fill=ORANGE)
    d.polygon([(16, 9), (13, 16), (14, 22), (16, 25), (19, 22), (20, 16)], fill=GOLD)
    d.polygon([(16, 14), (14, 19), (16, 23), (18, 19)], fill=CORE)
    # embers
    for x, y in ((6, 9), (25, 11), (7, 22), (24, 21), (16, 3)):
        d.rectangle([x, y, x + 1, y + 1], fill=GOLD)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print("wrote", OUT.name, img.size)


if __name__ == "__main__":
    main()
