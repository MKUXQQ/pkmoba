"""Blaziken thrown-leg sheet + flame mark badge (0.81).

The old foot sheet read as a generic boot. This one is drawn as Blaziken's actual leg: yellow
feathered thigh, red-orange shin, pale three-toed foot, flame plume trailing behind - so the flying
kick is recognisably *its* leg. The mark is a small flame emblem used as the overhead badge.

Run from the project root: python tools/generate_blaziken_leg.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path("src/main/resources/assets/pkmoba/textures/effect")
SIZE = 64

DARK = (46, 22, 12, 255)
YELLOW = (252, 214, 92, 255)
YELLOW_LIGHT = (255, 240, 178, 255)
ORANGE = (238, 122, 40, 255)
ORANGE_DARK = (186, 74, 24, 255)
FOOT = (226, 222, 206, 255)
CLAW = (128, 120, 108, 255)
FIRE_HOT = (255, 248, 196, 255)


def tongue(d, x, y, width, height, colours):
    """A pixel flame tongue pointing up, drawn light-to-hot from edge to core."""
    outer, mid, core = colours
    for i, colour in enumerate((outer, mid, core)):
        w = max(2, width - i * 2)
        h = max(2, height - i * 3)
        cx = x + width // 2
        d.polygon([(cx - w, y), (cx, y - h), (cx + w, y)], fill=colour)


def leg() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # flame plume behind the knee
    tongue(d, 6, 46, 13, 22, (ORANGE_DARK, ORANGE, YELLOW))
    tongue(d, 15, 49, 10, 15, (ORANGE, YELLOW, YELLOW_LIGHT))
    # thigh, with feather steps
    d.polygon([(22, 8), (40, 6), (44, 26), (26, 30)], fill=DARK)
    d.polygon([(24, 10), (38, 9), (41, 25), (27, 28)], fill=YELLOW)
    d.polygon([(27, 12), (34, 11), (36, 24), (29, 26)], fill=YELLOW_LIGHT)
    for x, y in ((20, 24), (17, 30), (22, 34)):
        d.rectangle([x, y, x + 3, y + 3], fill=YELLOW)
    # shin
    d.polygon([(28, 27), (42, 24), (46, 42), (32, 45)], fill=DARK)
    d.polygon([(30, 28), (41, 26), (44, 41), (33, 43)], fill=ORANGE)
    d.polygon([(32, 30), (38, 29), (40, 40), (34, 41)], fill=ORANGE_DARK)
    # foot with three toes
    d.polygon([(33, 42), (50, 40), (52, 50), (33, 52)], fill=DARK)
    d.polygon([(34, 43), (49, 42), (50, 49), (34, 50)], fill=FOOT)
    for x in (44, 48):
        d.rectangle([x, 43, x + 2, 50], fill=CLAW)
    for x in (33, 37, 41):
        d.rectangle([x, 49, x + 2, 54], fill=DARK)
        d.rectangle([x, 49, x + 1, 53], fill=CLAW)
    # hot streaks flying off the foot
    d.rectangle([52, 26, 56, 29], fill=FIRE_HOT)
    d.rectangle([47, 20, 50, 23], fill=YELLOW_LIGHT)
    d.rectangle([55, 34, 58, 36], fill=ORANGE)
    return img


def mark() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # a bold flame emblem, readable at 12 px on screen
    d.polygon([(32, 4), (44, 24), (50, 34), (44, 48), (32, 58), (20, 48), (14, 34), (22, 22)],
              fill=DARK)
    d.polygon([(32, 8), (42, 26), (47, 35), (42, 46), (32, 54), (22, 46), (17, 35), (24, 24)],
              fill=ORANGE)
    d.polygon([(32, 18), (39, 30), (41, 38), (36, 46), (32, 50), (28, 46), (23, 38), (26, 30)],
              fill=YELLOW)
    d.polygon([(32, 28), (36, 36), (34, 44), (32, 46), (30, 44), (28, 36)], fill=FIRE_HOT)
    return img


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    leg().save(OUT / "blaziken_foot.png")
    mark().save(OUT / "blaziken_mark.png")
    print("wrote blaziken_foot.png and blaziken_mark.png")


if __name__ == "__main__":
    main()
