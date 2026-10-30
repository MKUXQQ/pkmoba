"""Gengar effect textures, Minecraft pixel-art style.

Every tile is a hard-edged 16x16 pixel grid (no gradients, no soft halo), so the closed meshes read
as chunky pixel surfaces instead of blurry blobs. Run from the pkmoba module root:

    python tools/gen_gengar_pixel_textures.py
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
EFFECT = ROOT / "src/main/resources/assets/pkmoba/textures/effect"
EXPANSION = EFFECT / "expansion"

# Four-step palettes: shadow, mid, light, hot core. Ordered dark -> bright.
PALETTES = {
    "q": [(24, 8, 40), (58, 20, 96), (110, 48, 168), (188, 130, 240)],
    "w": [(26, 16, 18), (66, 38, 72), (120, 74, 130), (198, 156, 206)],
    "e": [(40, 6, 44), (104, 18, 108), (170, 52, 168), (246, 168, 244)],
    "r": [(12, 10, 34), (38, 32, 92), (84, 74, 156), (176, 168, 232)],
}

# Deterministic 4x4 dither offsets, repeated over the tile: keeps the surface readable and even.
DITHER = [
    [0, 2, 1, 2],
    [3, 1, 2, 1],
    [1, 2, 3, 2],
    [2, 1, 2, 1],
]


def surface(skill: str) -> Image.Image:
    palette = PALETTES[skill]
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            # Bright core in the middle, two darker steps towards the border, dithered a level.
            edge = min(x, y, 15 - x, 15 - y)
            level = 3 if edge >= 5 else 2 if edge >= 3 else 1
            level = max(0, min(3, level + (DITHER[y % 4][x % 4] - 2) // 2))
            # Sparse near-black pixels give the shadow a pixel-art "hole" texture.
            if (x * 7 + y * 11) % 23 == 0:
                level = 0
            r, g, b = palette[level]
            a = 255 if level else 190
            px[x, y] = (r, g, b, a)
    return img


def sheet(skill: str) -> Image.Image:
    """A 16x16 ghost orb / ring motif used only as a small accent behind the meshes."""
    palette = PALETTES[skill]
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            d = (dx * dx + dy * dy) ** 0.5
            if skill == "e":
                # Concentric hypnosis rings: crisp 1px bands with gaps.
                band = int(d) % 3
                if band != 0 and d < 7.6:
                    px[x, y] = (*palette[3 if d < 4 else 2], 235)
            else:
                if d < 6.2:
                    level = 3 if d < 2.4 else 2 if d < 4.0 else 1
                    px[x, y] = (*palette[level], 250 if level > 2 else 220)
    return img


def main() -> None:
    EXPANSION.mkdir(parents=True, exist_ok=True)
    for skill in PALETTES:
        surface(skill).save(EXPANSION / f"gengar_{skill}_surface.png")
        sheet(skill).save(EFFECT / f"gengar_{skill}_orb.png")
        print(f"gengar_{skill}_surface.png / gengar_{skill}_orb.png")


if __name__ == "__main__":
    main()
