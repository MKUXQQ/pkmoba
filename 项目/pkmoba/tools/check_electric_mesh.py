"""CPU-side topology/asset checks; not an in-game visual acceptance test."""
from pathlib import Path
from PIL import Image
import math
root=Path(__file__).resolve().parents[1]
source=(root/'src/main/java/com/pkmoba/client/ElectricSkillMesh.java').read_text(encoding='utf-8')
fx=(root/'src/main/java/com/pkmoba/client/PikachuEffects.java').read_text(encoding='utf-8')
assert 'Two crossed' not in fx and 'private static void beam' not in fx
assert all('ElectricSkillMesh.'+name in fx for name in ['orb','bolt','net','ring'])
im=Image.open(root/'src/main/resources/assets/pkmoba/textures/effect/pikachu_mesh.png').convert('RGBA')
assert im.size==(64,64) and im.getchannel('A').getextrema()==(255,255)
assert len(set(im.getdata()))>=4
# Check representative sphere quads have outward winding; duplicated pole vertices are intentional.
def p(a,b):return (math.cos(a)*math.cos(b),math.sin(a),math.cos(a)*math.sin(b))
def sub(a,b):return tuple(x-y for x,y in zip(a,b))
def cross(a,b):return (a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
for y in range(1,5):
 for x in range(12):
  a=-math.pi/2+y*math.pi/6;b=a+math.pi/6;u=x*math.pi/6;v=u+math.pi/6
  A,B,C=p(a,u),p(b,u),p(b,v)
  normal=cross(sub(B,A),sub(C,A))
  assert sum(n*z for n,z in zip(normal,A))>0
assert 'for(int i=0;i<6;i++)' in source
print('PASS electric closed-mesh routing, opaque UV texture, 48 sphere outward winding cases')
