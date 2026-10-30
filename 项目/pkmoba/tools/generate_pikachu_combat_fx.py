"""Author only Pikachu world FX; no portraits or other species assets are touched."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFilter
import math,random
import numpy as np


def unpremultiply(im):
 """高斯模糊会把透明像素的黑色 RGB 混进半透明区（等于把 RGB 乘了 alpha）。
 直接保存就会得到发灰的暗边，所以在落盘前把 alpha 除回去。"""
 a=np.asarray(im).astype(np.float32)
 al=a[...,3]
 partial=(al>0)&(al<255)
 safe=np.where(partial,np.maximum(al/255.0,1/255.0),1.0)
 a[...,:3]=np.clip(np.where(partial[...,None],a[...,:3]/safe[...,None],a[...,:3]),0,255)
 return Image.fromarray(np.clip(a,0,255).astype('uint8'),'RGBA')

OUT=Path(__file__).resolve().parents[1]/'src/main/resources/assets/pkmoba/textures/effect'
OUT.mkdir(parents=True,exist_ok=True)
S=512

def make(name,draw):
 im=Image.new('RGBA',(S,S));d=ImageDraw.Draw(im)
 def arc(points,width=5):
  d.line(points,fill=(255,140,0,75),width=width+22,joint='curve')
  d.line(points,fill=(255,222,12,215),width=width+10,joint='curve')
  d.line(points,fill=(255,255,207,255),width=width,joint='curve')
 draw(d,arc)
 halo=im.filter(ImageFilter.GaussianBlur(7));halo.alpha_composite(im)
 halo=unpremultiply(halo)
 halo.save(OUT/f'pikachu_{name}.png')
 assert halo.getpixel((0,0))[3]==0

def bolt(d,arc):
 # A single connected discharge with no surrounding circle or decorative branches.
 arc([(0,256),(65,248),(104,284),(151,211),(196,281),(247,229),(298,277),(356,233),(414,267),(468,249),(512,256)],7)

def thunder(d,arc):
 bolt(d,arc)
 for points in [[(105,284),(125,356),(168,333),(200,438)],[(247,229),(270,132),(319,156),(351,51)],[(356,233),(383,332),(419,319),(455,412)]]:arc(points,6)

def web(d,arc):
 c=256;r=214
 # Woven polygon net with bright edge knots, no opaque disk.
 for j in range(-3,4):
  y=c+j*55; span=math.sqrt(max(0,r*r-(y-c)**2))
  arc([(c-span,y),(c+span,y)],3)
  x=c+j*55;span=math.sqrt(max(0,r*r-(x-c)**2))
  arc([(x,c-span),(x,c+span)],3)
 pts=[(c+math.cos(i*math.pi/8)*r,c+math.sin(i*math.pi/8)*r) for i in range(17)]
 arc(pts,5)
 for x,y in pts[:-1]:d.ellipse((x-6,y-6,x+6,y+6),fill=(255,255,229,255))

def charge(d,arc):
 for r in [64,135,212]:
  pts=[]
  for i in range(97):
   a=i*math.pi/48;rr=r+math.sin(i*1.7)*5
   pts.append((256+math.cos(a)*rr,256+math.sin(a)*rr))
  arc(pts,3)
 for i in range(8):
  a=i*math.pi/4
  arc([(256+math.cos(a)*216,256+math.sin(a)*216),(256+math.cos(a+.14)*118,256+math.sin(a+.14)*118),(256+math.cos(a-.12)*76,256+math.sin(a-.12)*76)],4)
 d.ellipse((231,231,281,281),fill=(255,255,236,255))

make('bolt',bolt);make('thunder',thunder);make('web',web);make('charge',charge)
print('Authored Pikachu bolt, branching thunder, woven net, charge sigil (512px RGBA)')
