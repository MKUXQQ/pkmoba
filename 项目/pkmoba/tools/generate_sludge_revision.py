from pathlib import Path
from PIL import Image, ImageDraw
import random, math, json, uuid
root=Path(__file__).resolve().parents[1]/'src/main/resources/assets/pkmoba'
effect=root/'textures/effect'; gui=root/'textures/gui/skills'
rng=random.Random(94)
for name,base in [('toxic_glob',(101,29,147)),('sludge_pool',(75,16,106)),('sludge_highlight',(184,79,218))]:
    im=Image.new('RGBA',(64,64),base+(255,)); d=ImageDraw.Draw(im)
    for i in range(100):
        x,y=rng.randrange(64),rng.randrange(64); r=rng.randint(1,6)
        col=tuple(max(0,min(255,c+rng.randint(-22,22))) for c in base)+(255,)
        d.ellipse((x-r,y-r,x+r,y+r),fill=col)
    for x,y,r in [(15,13,5),(35,32,4),(49,51,3)]:
        d.ellipse((x-r,y-r,x+r,y+r),outline=(214,124,232,255),width=2)
        d.arc((x-r,y-r,x+r,y+r),180,290,fill=(242,183,249,255),width=1)
    im.save(effect/('venusaur_'+name+'.png'))
im=Image.new('RGBA',(32,32),(31,16,44,255)); d=ImageDraw.Draw(im)
d.rectangle((1,1,30,30),outline=(198,128,221),width=1)
for x,y,r in [(15,17,10),(8,18,5),(23,18,5),(15,24,5),(20,9,4)]:
    d.ellipse((x-r,y-r,x+r,y+r),fill=(109,35,147),outline=(44,9,67))
d.ellipse((8,9,16,14),fill=(219,133,244));d.ellipse((19,15,24,19),fill=(166,75,195))
for x,y in [(5,5),(25,7),(6,26),(26,25)]:d.rectangle((x,y,x+1,y+2),fill=(225,153,240))
im.save(gui/'venusaur_sludge.png')
# Editable Blockbench source with textured clustered cube volume; runtime uses matching live mesh.
model={'meta':{'format_version':'4.10','model_format':'free','box_uv':False},'name':'Sludge Bomb','resolution':{'width':64,'height':64},'elements':[],'outliner':[],'textures':[]}
import base64
texture=effect/'venusaur_toxic_glob.png'
model['textures']=[{'name':texture.name,'id':'0','uuid':str(uuid.uuid4()),'source':'data:image/png;base64,'+base64.b64encode(texture.read_bytes()).decode(),'uv_width':64,'uv_height':64}]
for i,(x,y,z,s) in enumerate([(0,0,0,8),(-4,1,0,5),(4,2,1,5),(0,4,-2,5),(1,-4,2,4)]):
    uid=str(uuid.uuid4());model['outliner'].append(uid)
    model['elements'].append({'name':'sludge_lobe_'+str(i),'uuid':uid,'from':[x-s/2,y+8-s/2,z-s/2],'to':[x+s/2,y+8+s/2,z+s/2],'rotation':[0,i*19,0],'origin':[x,y+8,z],'faces':{f:{'uv':[0,0,64,64],'texture':0} for f in ['north','east','south','west','up','down']}})
models=Path(__file__).resolve().parents[1]/'art/models';models.mkdir(parents=True,exist_ok=True)
(models/'sludge_bomb.bbmodel').write_text(json.dumps(model,ensure_ascii=False,indent=2),encoding='utf-8')
print('Generated purple sludge icon, three textures, editable sludge_bomb.bbmodel')
