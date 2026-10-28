from pathlib import Path
import math
from PIL import Image
root=Path(__file__).resolve().parents[1]
p=root/'src/main/java/com/pkmoba/client/PikachuEffects.java'
s=p.read_text(encoding='utf-8')
a=s.index('            switch (s.kind) {',s.index('private static void render'))
b=s.index('    private static final class Scene',a)
s=s[:a]+'''            switch (s.kind) {
                case "q_bolt" -> ElectricSkillMesh.bolt(ctx,matrix,a,a.add(s.vec),.055,alpha,17);
                case "w_charge", "r_charge" -> {
                    double power=Math.max(0,Math.min(1,s.vec.x));
                    double size=s.kind.equals("r_charge")?.24+power*.42:.14+power*.20;
                    ElectricSkillMesh.orb(ctx,matrix,a.add(0,.45,0),size,alpha*.85F,phase);
                    for(int i=0;i<3;i++) {
                        double angle=phase+i*Math.PI*2/3;
                        Vec3 edge=a.add(Math.cos(angle)*(size+.18),.45+Math.sin(angle*2)*.15,Math.sin(angle)*(size+.18));
                        ElectricSkillMesh.bolt(ctx,matrix,edge,a.add(0,.45,0),.018,alpha,i);
                    }
                }
                case "w_orb_fly" -> {
                    double blend=Math.min(1,(System.nanoTime()-s.updated)/50_000_000.0);
                    Vec3 moving=s.previousPos.lerp(s.pos,blend).subtract(camera);
                    ElectricSkillMesh.orb(ctx,matrix,moving,.28,alpha,phase);
                    Vec3 direction=s.vec.lengthSqr()<.001?s.facing:s.vec.normalize();
                    ElectricSkillMesh.bolt(ctx,matrix,moving.subtract(direction.scale(.8)),moving,.03,alpha*.65F,9);
                }
                case "w_orb_burst" -> {
                    double grow=Math.min(1,(s.age+partial)/5.0);
                    ElectricSkillMesh.net(ctx,matrix,a,Math.max(.3,s.vec.x)*grow,alpha,0);
                }
                case "r_warn" -> ElectricSkillMesh.ring(ctx,matrix,a.add(0,.08,0),Math.max(1,s.vec.x),.025,alpha*.55F,0);
                case "r_field" -> {
                    double radius=Math.max(1,Math.min(8,s.vec.x));
                    ElectricSkillMesh.ring(ctx,matrix,a.add(0,.08,0),radius,.025,alpha*.55F,0);
                    for(int i=0;i<4;i++) {
                        double angle=i*Math.PI*.5;
                        Vec3 edge=a.add(Math.cos(angle)*radius,.1,Math.sin(angle)*radius);
                        ElectricSkillMesh.bolt(ctx,matrix,edge,edge.add(0,.4,0),.025,alpha,i);
                    }
                }
                case "e_start", "e_hit" -> {
                    for(int i=0;i<4;i++) {
                        double angle=i*Math.PI*.5;
                        ElectricSkillMesh.bolt(ctx,matrix,a.add(0,.35,0),a.add(Math.cos(angle)*.45,.55,Math.sin(angle)*.45),.025,alpha,i);
                    }
                }
                case "e_trail" -> {
                    Vec3 dir=s.vec.lengthSqr()<.001?new Vec3(0,0,1):s.vec.normalize();
                    ElectricSkillMesh.bolt(ctx,matrix,a.add(0,.4,0),a.subtract(dir.scale(.9)).add(0,.4,0),.025,alpha*.7F,3);
                }
                case "r_strike" -> {
                    Vec3 end=a.add(s.vec);
                    ElectricSkillMesh.bolt(ctx,matrix,a,end,.12,alpha,21);
                    double radius=com.pkmoba.combat.PikachuCombat.R_RADIUS;
                    double spread=Math.min(1,(s.age+partial)/7.0);
                    for(int i=0;i<6;i++) {
                        double angle=i*Math.PI/3;
                        Vec3 edge=end.add(Math.cos(angle)*radius*spread,.10,Math.sin(angle)*radius*spread);
                        ElectricSkillMesh.bolt(ctx,matrix,end.add(0,.1,0),edge,.025,alpha*.8F,i);
                    }
                }
                default -> { }
            }
        }
    }
''' + s[b:]
p.write_text(s,encoding='utf-8')
# An opaque UV surface with directional enamel bands, not a transparent silhouette.
im=Image.new('RGBA',(64,64))
for y in range(64):
 for x in range(64):
  stripe=(x+int(3*math.sin(y*.18)))%16
  if stripe<3:color=(255,255,234,255)
  elif stripe<7:color=(255,237,121,255)
  elif stripe<13:color=(250,190,35,255)
  else:color=(181,109,19,255)
  im.putpixel((x,y),color)
im.save(root/'src/main/resources/assets/pkmoba/textures/effect/pikachu_mesh.png')
print('Pikachu 3D meshes installed: rods, closed orbs, woven net; opaque UV texture64')
