from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/pkmoba/textures/gui/skills"
OUT.mkdir(parents=True, exist_ok=True)
S = 32
PAL = {
    "outline": (76, 48, 35, 255), "shadow": (112, 71, 43, 255),
    "cream": (248, 224, 167, 255), "gold": (238, 167, 58, 255),
    "orange": (232, 104, 38, 255), "red": (183, 57, 39, 255),
    "yellow": (255, 218, 94, 255), "blue": (93, 137, 182, 255),
    "sky": (170, 207, 226, 255), "white": (255, 244, 211, 255),
}

def make(name, draw_art):
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((1, 1, 30, 30), radius=3, fill=(112, 76, 47, 255), outline=PAL["outline"], width=1)
    d.rectangle((3, 3, 28, 28), fill=(243, 225, 187, 255))
    draw_art(d)
    im.save(OUT / f"{name}.png", optimize=True)

def flame(d, ox=0, oy=0, color="orange"):
    shape = [(5, 15), (11, 12), (15, 7), (16, 12), (22, 10), (20, 15), (27, 14), (23, 19), (18, 21), (11, 20)]
    d.polygon([(x+ox,y+oy) for x,y in shape], fill=PAL[color], outline=PAL["outline"])
    d.polygon([(11+ox,16+oy),(16+ox,12+oy),(16+ox,16+oy),(22+ox,15+oy),(18+ox,19+oy),(13+ox,19+oy)], fill=PAL["yellow"])
    d.rectangle((5+ox,15+oy,9+ox,17+oy), fill=PAL["red"])

def fire_spin(d):
    d.arc((6, 7, 26, 26), 205, 530, fill=PAL["red"], width=4)
    d.arc((9, 5, 24, 22), 20, 340, fill=PAL["orange"], width=4)
    d.arc((12, 9, 21, 19), 185, 525, fill=PAL["yellow"], width=3)
    d.polygon([(21, 7), (27, 6), (24, 12)], fill=PAL["yellow"], outline=PAL["outline"])
    d.polygon([(7, 22), (5, 27), (12, 25)], fill=PAL["orange"], outline=PAL["outline"])

def wings(d):
    d.rectangle((15, 13, 17, 23), fill=PAL["outline"])
    d.polygon([(15, 18),(10,10),(4,7),(6,15),(3,18),(10,22),(15,20)], fill=PAL["sky"], outline=PAL["outline"])
    d.polygon([(17, 18),(22,10),(28,7),(26,15),(29,18),(22,22),(17,20)], fill=PAL["blue"], outline=PAL["outline"])
    d.line((7,13,14,18), fill=PAL["white"], width=1)
    d.line((25,13,18,18), fill=PAL["white"], width=1)
    d.polygon([(13, 20),(16,24),(19,20)], fill=PAL["gold"], outline=PAL["outline"])

def big_fire(d):
    flame(d, color="red")
    d.line((12,13,20,13), fill=PAL["white"], width=2)
    d.line((15,10,15,23), fill=PAL["white"], width=2)
    d.line((15,16,21,16), fill=PAL["white"], width=2)
    d.line((14,22,21,22), fill=PAL["yellow"], width=1)

make("flamethrower", lambda d: flame(d, color="orange"))
make("fire_spin", fire_spin)
make("fly", wings)
make("fire_blast", big_fire)
