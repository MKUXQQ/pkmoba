"""Generate only Venusaur's heavy Power Whip / charged Sludge Bomb artwork."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter
import math
ROOT=Path(__file__).resolve().parents[1]/'src/main/resources/assets/pkmoba/textures/effect'
ROOT.mkdir(parents=True,exist_ok=True)
# UV-aligned thick braided vine: transparent margins, bright ridges and dark crevices.
im=Image.new('RGBA',(256,64));d=ImageDraw.Draw(im)
for x in range(256):
 center=32+math.sin(x*.09)*3
 d.line((x,center-18,x,center+18),fill=(29,86,32,255),width=1)
 d.line((x,center-10,x,center+10),fill=(76,172,37,255),width=1)
for off in [0,math.pi]:
 pts=[(x,32+math.sin(x*.09+off)*12) for x in range(256)]
 d.line(pts,fill=(184,237,81,255),width=5)
for x in range(12,256,28):d.polygon([(x,16),(x+12,3),(x+17,21)],fill=(167,225,65,255))
im.save(ROOT/'venusaur_power_lash.png')
# Jagged impact medallion, greens with almost-white snap core.
im=Image.new('RGBA',(256,256));d=ImageDraw.Draw(im)
pts=[]
for i in range(48):
 a=i*math.pi/24;r=113 if i%2==0 else 48
 pts.append((128+math.cos(a)*r,128+math.sin(a)*r))
d.polygon(pts,fill=(113,242,58,190));d.ellipse((87,87,169,169),fill=(242,255,187,255))
d.ellipse((108,108,148,148),fill=(255,255,245,255))
glow=im.filter(ImageFilter.GaussianBlur(5));glow.alpha_composite(im);glow.save(ROOT/'venusaur_power_impact.png')
# Radial poison aura. Not a green field: saturated violet, magenta filigree and white highlights.
im=Image.new('RGBA',(256,256));d=ImageDraw.Draw(im)
for r in [48,83,111]:d.ellipse((128-r,128-r,128+r,128+r),outline=(204,81,255,230),width=5)
for i in range(12):
 a=i*math.pi/6
 x,y=128+math.cos(a)*95,128+math.sin(a)*95
 d.ellipse((x-8,y-8,x+8,y+8),fill=(255,171,255,255))
 d.line((128+math.cos(a)*48,128+math.sin(a)*48,x,y),fill=(173,57,238,190),width=4)
glow=im.filter(ImageFilter.GaussianBlur(4));glow.alpha_composite(im);glow.save(ROOT/'venusaur_sludge_aura.png')
for name in ['power_lash','power_impact','sludge_aura']:
 p=ROOT/f'venusaur_{name}.png'; image=Image.open(p)
 assert image.mode=='RGBA' and image.getextrema()[3][0]==0
 print('PASS',p.name,image.size)
