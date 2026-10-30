"""Lucario aura kit - 64x64 chunky pixel sheets and 32x32 HUD icons.

Palette is steel-blue aura: navy shadow, cobalt body, cyan glow, near-white core, so it never
collides with the other species' palettes (Pikachu yellow, Gengar purple, Gardevoir pink).
"""
from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path("src/main/resources/assets/pkmoba/textures")
S = 64

NAVY = (14, 26, 58, 255)
COBALT = (46, 100, 226, 255)
BLUE = (86, 160, 246, 255)
CYAN = (120, 216, 255, 255)
PALE = (206, 240, 255, 255)
WHITE = (246, 252, 255, 255)
STEEL = (150, 168, 196, 255)
DARK = (24, 20, 40, 255)


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def orb() -> Image.Image:
    img, d = canvas()
    for r, colour in ((30, NAVY), (26, COBALT), (21, BLUE), (15, CYAN), (9, PALE), (5, WHITE)):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], fill=colour)
    # chunky rim flecks, keep it pixel-art rather than a smooth gradient
    for x, y in ((32, 1), (32, 62), (1, 32), (62, 32), (14, 12), (50, 12), (14, 51), (50, 51)):
        d.rectangle([x - 2, y - 2, x + 1, y + 1], fill=PALE)
    for x, y in ((22, 22), (42, 24), (26, 44), (44, 42)):
        d.rectangle([x, y, x + 3, y + 3], fill=WHITE)
    return img


def band() -> Image.Image:
    """Horizontal energy band: bright core, cyan bleed, dashed sparkle rows (stretchable)."""
    img, d = canvas()
    d.rectangle([0, 20, 63, 43], fill=(COBALT[0], COBALT[1], COBALT[2], 235))
    d.rectangle([0, 24, 63, 39], fill=BLUE)
    d.rectangle([0, 27, 63, 36], fill=CYAN)
    d.rectangle([0, 30, 63, 33], fill=WHITE)
    for x in range(2, 64, 8):
        d.rectangle([x, 21, x + 3, 23], fill=PALE)
        d.rectangle([x + 4, 40, x + 6, 42], fill=PALE)
    return img


def ring() -> Image.Image:
    img, d = canvas()
    d.ellipse([2, 24, 61, 39], outline=NAVY, width=7)
    d.ellipse([4, 26, 59, 37], outline=COBALT, width=4)
    d.ellipse([6, 28, 57, 35], outline=CYAN, width=2)
    return img


def star() -> Image.Image:
    img, d = canvas()
    for length, width, colour in ((30, 9, NAVY), (26, 6, COBALT), (20, 4, CYAN), (12, 2, WHITE)):
        d.rectangle([32 - length, 32 - width // 2, 32 + length, 32 + width // 2], fill=colour)
        d.rectangle([32 - width // 2, 32 - length, 32 + width // 2, 32 + length], fill=colour)
    d.ellipse([27, 27, 36, 36], fill=WHITE)
    for x, y in ((8, 8), (52, 10), (10, 52), (54, 52)):
        d.rectangle([x, y, x + 2, y + 2], fill=PALE)
    return img


def shard() -> Image.Image:
    img, d = canvas()
    d.polygon([(32, 2), (52, 32), (32, 61), (12, 32)], fill=NAVY)
    d.polygon([(32, 6), (47, 32), (32, 56), (17, 32)], fill=COBALT)
    d.polygon([(32, 12), (41, 32), (32, 48), (23, 32)], fill=CYAN)
    d.polygon([(32, 20), (36, 32), (32, 42), (28, 32)], fill=WHITE)
    return img


def glow() -> Image.Image:
    img, d = canvas()
    for r, alpha in ((30, 40), (24, 70), (17, 110), (11, 170), (6, 230)):
        d.ellipse([32 - r, 32 - r, 32 + r, 32 + r], fill=(85, 160, 250, alpha))
    d.ellipse([27, 27, 36, 36], fill=(235, 250, 255, 250))
    return img


def frame(icon: Image.Image) -> Image.Image:
    """Same gold frame language as the rest of the HUD so the bar still looks like one set."""
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 31, 31], fill=(10, 12, 26, 255))
    d.rectangle([1, 1, 30, 30], outline=(214, 176, 96, 255), width=2)
    d.rectangle([3, 3, 28, 28], outline=(122, 88, 40, 255))
    for x, y in ((1, 1), (28, 1), (1, 28), (28, 28)):
        d.rectangle([x, y, x + 2, y + 2], fill=(255, 236, 170, 255))
    img.alpha_composite(icon.resize((24, 24), Image.NEAREST), (4, 4))
    return img


def icon_q() -> Image.Image:
    icon, d = canvas()
    d.ellipse([12, 12, 51, 51], fill=NAVY)
    d.ellipse([16, 16, 47, 47], fill=COBALT)
    d.ellipse([22, 22, 41, 41], fill=CYAN)
    d.ellipse([26, 26, 37, 37], fill=WHITE)
    d.ellipse([6, 26, 57, 37], outline=(150, 220, 255, 220), width=2)
    for x, y in ((32, 2), (2, 32), (56, 58), (60, 12)):
        d.rectangle([x, y, x + 2, y + 3], fill=PALE)
    return frame(icon)


def icon_w() -> Image.Image:
    icon, d = canvas()
    # open palm: heel, four fingers, thumb; a shock ring bursts off the heel
    d.rectangle([14, 26, 34, 46], fill=(228, 220, 202, 255))
    d.rectangle([14, 26, 34, 29], fill=(198, 190, 172, 255))
    for x, h in ((15, 10), (20, 13), (25, 12), (30, 9)):
        d.rectangle([x, 26 - h, x + 3, 27], fill=(232, 226, 208, 255))
    d.rectangle([9, 30, 14, 41], fill=(228, 220, 202, 255))
    d.rectangle([16, 34, 32, 36], fill=(198, 190, 172, 255))
    d.rectangle([16, 40, 32, 41], fill=(198, 190, 172, 255))
    for radius, width, colour in ((11, 3, COBALT), (16, 2, CYAN), (21, 2, PALE)):
        d.arc([38 - radius, 44 - radius, 38 + radius, 44 + radius], 268, 88, fill=colour, width=width)
    for x, y in ((46, 20), (52, 30), (47, 41)):
        d.rectangle([x, y, x + 2, y + 2], fill=WHITE)
    return frame(icon)


def icon_e() -> Image.Image:
    icon, d = canvas()
    for i, y in enumerate((18, 28, 38)):
        d.rectangle([4, y, 30 + i * 4, y + 3], fill=NAVY)
        d.rectangle([5, y + 1, 29 + i * 4, y + 2], fill=CYAN)
    d.polygon([(38, 12), (58, 32), (38, 52), (42, 32)], fill=NAVY)
    d.polygon([(41, 17), (55, 32), (41, 47), (44, 32)], fill=COBALT)
    d.polygon([(44, 24), (50, 32), (44, 40), (46, 32)], fill=WHITE)
    return frame(icon)


def icon_r() -> Image.Image:
    icon, d = canvas()
    d.ellipse([4, 4, 59, 59], fill=(24, 40, 84, 255))
    d.ellipse([10, 10, 53, 53], outline=COBALT, width=4)
    d.ellipse([17, 17, 46, 46], outline=CYAN, width=3)
    d.ellipse([24, 24, 39, 39], fill=PALE)
    d.ellipse([28, 28, 35, 35], fill=WHITE)
    for i in range(8):
        import math
        a = i * math.pi / 4
        x1, y1 = 32 + math.cos(a) * 12, 32 + math.sin(a) * 12
        x2, y2 = 32 + math.cos(a) * 26, 32 + math.sin(a) * 26
        d.line([(x1, y1), (x2, y2)], fill=CYAN, width=2)
    return frame(icon)


def main() -> None:
    effect = OUT / "effect"
    effect.mkdir(parents=True, exist_ok=True)
    for name, build in (("lucario_orb", orb), ("lucario_band", band), ("lucario_ring", ring),
                        ("lucario_star", star), ("lucario_shard", shard), ("lucario_glow", glow)):
        build().save(effect / f"{name}.png")
    skills = OUT / "gui" / "skills"
    skills.mkdir(parents=True, exist_ok=True)
    for slot, build in zip("qwer", (icon_q, icon_w, icon_e, icon_r)):
        build().save(skills / f"lucario_{slot}.png")
    print("wrote 6 sheets and 4 icons")


if __name__ == "__main__":
    main()
