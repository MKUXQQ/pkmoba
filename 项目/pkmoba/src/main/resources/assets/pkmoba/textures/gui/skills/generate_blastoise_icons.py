from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent
PALETTE = {
    "outline": (18, 51, 74, 255), "frame": (36, 81, 111, 255),
    "water": (58, 158, 198, 255), "light": (190, 241, 247, 255),
    "foam": (230, 246, 242, 255), "deep": (31, 105, 151, 255),
}

def icon(index, name):
    image = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((1, 1, 30, 30), radius=3, fill=PALETTE["frame"], outline=PALETTE["outline"])
    draw.rectangle((3, 3, 28, 28), fill=(207, 233, 236, 255))
    if index == 0:
        draw.polygon([(4,17),(10,12),(15,16),(21,10),(28,15),(24,18),(18,18),(13,23),(8,20)], fill=PALETTE["water"], outline=PALETTE["outline"])
        draw.polygon([(9,17),(14,18),(18,14),(23,15),(19,17),(14,21)], fill=PALETTE["light"])
    elif index == 1:
        draw.arc((5,6,27,27), 200, 520, fill=PALETTE["water"], width=5)
        draw.arc((10,10,22,22), 20, 340, fill=(103,205,224,255), width=3)
        draw.polygon([(22,6),(28,6),(25,12)], fill=(157,225,235,255), outline=PALETTE["outline"])
    elif index == 2:
        draw.ellipse((7,7,25,25), outline=PALETTE["outline"], width=2)
        draw.arc((8,8,24,24), 205, 520, fill=PALETTE["water"], width=5)
        draw.polygon([(21,7),(27,8),(23,13)], fill=(181,235,241,255), outline=PALETTE["outline"])
        draw.ellipse((13,13,19,19), fill=PALETTE["foam"], outline=PALETTE["outline"])
    else:
        draw.polygon([(4,12),(10,12),(13,9),(18,9),(21,12),(28,12),(27,18),(22,18),(19,22),(13,22),(10,18),(5,18)], fill=PALETTE["deep"], outline=PALETTE["outline"])
        draw.polygon([(9,15),(14,13),(21,15),(16,17)], fill=PALETTE["light"])
        draw.rectangle((23,13,28,17), fill=(162,222,235,255))
    image.save(OUT / f"blastoise_{index}.png", optimize=True)

for i, name in enumerate(("水之波动", "潮旋", "高速旋转", "加农水炮")):
    icon(i, name)
