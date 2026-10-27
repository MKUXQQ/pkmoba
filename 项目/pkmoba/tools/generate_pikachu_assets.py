"""Hand-painted Pikachu skill/portrait assets in the shared dark-gold framed style."""
from pathlib import Path
from PIL import Image, ImageDraw
import math
ROOT=Path(__file__).resolve().parents[1]/'src/main/resources/assets/pkmoba/textures/gui'
S=128; INK=(20,25,31,255); WHITE=(255,255,231,255)
def frame(d):
 d.rounded_rectangle((4,4,124,124),radius=24,fill=(30,24,19,255),outline=(8,12,18,255),width=5)
 d.rounded_rectangle((9,9,119,119),radius=19,fill=(92,62,18,255),outline=(255,214,66,255),width=3)
 d.rounded_rectangle((14,14,114,114),radius=15,fill=(52,42,22,255))
 d.ellipse((18,17,110,109),fill=(75,53,20,255))
 d.arc((15,14,113,112),202,333,fill=(255,244,135,255),width=4)
def line(d,pts,color,w=8): d.line(pts,fill=INK,width=w+7,joint='curve'); d.line(pts,fill=color,width=w,joint='curve')
def icon(name,art):
 im=Image.new('RGBA',(S,S));d=ImageDraw.Draw(im);frame(d);art(d);im.resize((32,32),Image.Resampling.LANCZOS).save(ROOT/'skills'/f'pikachu_{name}.png',optimize=True)
def bolt(d):
 line(d,[(74,20),(43,63),(62,63),(45,105),(94,49),(72,49),(91,20)],(255,219,48,255),10)
 d.line((64,27,51,58),fill=WHITE,width=3); d.ellipse((27,85,33,91),fill=(255,244,142,255));d.ellipse((96,26,102,32),fill=(255,244,142,255))
def web(d):
 # Rounded electric pulse orb with a bright center bolt.
 d.ellipse((38,36,90,92),fill=(255,221,49,255),outline=INK,width=6)
 d.ellipse((47,45,81,81),fill=(255,247,151,255),outline=(255,184,35,255),width=4)
 bolt(d)
 d.arc((19,25,109,103),195,345,fill=(255,241,111,255),width=6)
 d.arc((24,18,104,110),15,165,fill=(255,193,53,255),width=5)
def dash(d):
 line(d,[(26,86),(48,75),(76,48),(104,35)],(255,228,65,255),8)
 line(d,[(24,101),(45,91),(63,73)],(255,190,40,255),5)
 d.polygon([(78,28),(104,34),(87,49)],fill=(255,247,139,255),outline=INK)
def thunder(d):
 d.rectangle((56,18,72,100),fill=(255,224,58,255),outline=INK,width=4)
 d.polygon([(43,35),(64,55),(54,61),(78,81),(65,62),(76,59)],fill=(255,245,143,255),outline=INK)
 for x in (26,101): line(d,[(x,27),(x-7,45),(x+2,53)],(255,221,56,255),5)
for n,a in [('thundershock',bolt),('electroweb',web),('quick_attack',dash),('thunder',thunder)]: icon(n,a)
# portrait: same rounded frame and layered illustrated silhouette, not a raw square sprite
im=Image.new('RGBA',(128,128));d=ImageDraw.Draw(im)
d.rounded_rectangle((2,2,126,126),radius=13,fill=(30,24,19,255),outline=(74,58,40,255),width=6);d.rounded_rectangle((8,8,120,120),radius=10,fill=(243,226,190,255),outline=(91,62,18,255),width=3)
d.polygon([(38,48),(19,11),(25,7),(50,36),(78,35),(103,8),(109,12),(91,51)],fill=(249,207,45,255),outline=INK)
d.polygon([(23,9),(31,12),(35,27),(28,29)],fill=(46,31,23,255));d.polygon([(101,11),(108,14),(101,29),(94,27)],fill=(46,31,23,255))
d.ellipse((28,32,101,108),fill=(249,207,45,255),outline=INK,width=5)
d.ellipse((43,55,51,67),fill=(24,22,23,255));d.ellipse((78,55,86,67),fill=(24,22,23,255));d.ellipse((31,73,46,88),fill=(220,58,45,255));d.ellipse((83,73,98,88),fill=(220,58,45,255));d.arc((54,67,75,91),10,160,fill=INK,width=3)
im.resize((64,64),Image.Resampling.LANCZOS).save(ROOT/'pokemon/pikachu.png',optimize=True)
print('Generated hand-painted Pikachu assets')
