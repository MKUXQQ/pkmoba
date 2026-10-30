import json, math, zipfile
from pathlib import Path
import numpy as np
root=Path(__file__).resolve().parent
geo=root.parent/'宝可梦工程文件/cobblemon-1.8.2/common/src/main/resources/assets/cobblemon/bedrock/pokemon/models/0009_blastoise/blastoise.geo.json'
bones={b['name']:b for b in json.loads(geo.read_text(encoding='utf-8'))['minecraft:geometry'][0]['bones']}
# TexturedModel.createWithUvOverride converts Bedrock global coordinates to Java Y-down.
# With torso/shell rotations reset, a cube vertex relative to body2 is (x,24-y,z-2).
# Rx(+pi/2) then parent Ry(spin); body y=-24+12.5. World height=-JavaY.
points=[]
for name in ('torso','shell'):
    for c in bones[name].get('cubes',[]):
        if c.get('rotation'): continue
        for x in (c['origin'][0],c['origin'][0]+c['size'][0]):
            for y in (c['origin'][1],c['origin'][1]+c['size'][1]):
                for z in (c['origin'][2],c['origin'][2]+c['size'][2]):
                    points.append([x,24-y,z-2])
rx=np.array([[1,0,0],[0,0,-1],[0,1,0]],float)
heights=[]
for angle in np.linspace(0,2*math.pi,73):
    ry=np.array([[math.cos(angle),0,math.sin(angle)],[0,1,0],[-math.sin(angle),0,math.cos(angle)]])
    transformed=(ry@rx@np.array(points).T).T+np.array([0,-11.5,2])
    heights.append((-transformed[:,1].max(),-transformed[:,1].min()))
assert max(h[0] for h in heights)-min(h[0] for h in heights)<1e-6
assert min(h[0] for h in heights)>0,heights[0]
assert all(name in bones for name in ('head','arm_rotate_left','arm_rotate_right','leg_left','leg_right','tail','shell_cannon_left','shell_cannon_right'))
jar=root/'build/libs/pkmoba-0.27.1.jar'
with zipfile.ZipFile(jar) as z:
    assert json.loads(z.read('fabric.mod.json'))['version']=='0.27.1'
    assert 'BlastoiseSpinPoseMixin' in json.loads(z.read('pkmoba.mixins.json'))['client']
    assert 'com/pkmoba/mixin/BlastoiseSpinPoseMixin.class' in z.namelist()
    assert 'com/pkmoba/combat/BlastoiseCombat$Blade.class' in z.namelist()
    assert 'assets/pkmoba/textures/effect/water_smooth.png' in z.namelist()
result={'version':'0.27.1','archiveChecks':'PASS','geometryCheck':'PASS','geometryScope':'Unrotated torso/shell cube vertices only; not a client-render or terrain test','bodyHeightModelUnits':heights[0],'spinSamples':73,'clientRuntime':'NOT_RUN','tests':'NO_SOURCE'}
(root/'verification-0.27.1.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2))
