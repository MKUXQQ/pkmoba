package com.pkmoba.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Closed, UV-mapped electric meshes. No camera-facing or crossed-sheet substitutes. */
public final class ElectricSkillMesh {
    private static final ResourceLocation SURFACE = ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/pikachu_mesh.png");
    private ElectricSkillMesh() { }
    private static VertexConsumer buffer(WorldRenderContext c) {
        // 0.65：回到普通半透明叠加。加色混合（RenderType.eyes）虽然不会变灰，但会把一层层细线
        // 叠成饱和的实心光斑——用户 m06085 反馈"用出来像个圆盘"。亮度的真正原因是逐面法线被
        // minecraft_mix_light 压暗，那个问题已经在 vertex() 里用固定 (0,1,0) 法线解决了。
        return c.consumers().getBuffer(RenderType.entityTranslucentEmissive(SURFACE));
    }
    public static void rod(WorldRenderContext c, Matrix4f m, Vec3 a, Vec3 b, double radius, float alpha) {
        Vec3 delta=b.subtract(a);
        if (delta.lengthSqr()<1.0E-8 || radius<=0) return;
        Vec3 axis=delta.normalize();
        Vec3 u=axis.cross(Math.abs(axis.y)>.9?new Vec3(1,0,0):new Vec3(0,1,0)).normalize();
        Vec3 v=axis.cross(u).normalize();
        VertexConsumer out=buffer(c);
        for(int i=0;i<6;i++) {
            double t=i*Math.PI/3, n=(i+1)*Math.PI/3;
            Vec3 off=u.scale(Math.cos(t)*radius).add(v.scale(Math.sin(t)*radius));
            Vec3 next=u.scale(Math.cos(n)*radius).add(v.scale(Math.sin(n)*radius));
            face(out,m,a.add(off),a.add(next),b.add(next),b.add(off),alpha);
            face(out,m,a,a.add(off),a.add(next),a,alpha);
            face(out,m,b,b.add(next),b.add(off),b,alpha);
        }
    }
    public static void bolt(WorldRenderContext c, Matrix4f m, Vec3 a, Vec3 b, double radius, float alpha, double seed) {
        Vec3 axis=b.subtract(a);
        if(axis.lengthSqr()<1.0E-8)return;
        Vec3 side=axis.normalize().cross(new Vec3(0,1,0));
        if(side.lengthSqr()<.01)side=new Vec3(1,0,0);
        side=side.normalize();
        Vec3 up=axis.normalize().cross(side).normalize(),prev=a;
        int segments=Math.max(3, Math.min(14,(int)(axis.length()*2)));
        double jitter=Math.min(.27,axis.length()*.035);
        for(int i=1;i<=segments;i++) {
            double t=i/(double)segments;
            Vec3 p=a.add(axis.scale(t));
            if(i<segments)p=p.add(side.scale(Math.sin(i*7.13+seed)*jitter)).add(up.scale(Math.cos(i*4.31+seed)*jitter));
            rod(c,m,prev,p,radius,alpha);prev=p;
        }
    }
    public static void orb(WorldRenderContext c, Matrix4f m, Vec3 center, double radius, float alpha, double spin) {
        VertexConsumer out=buffer(c);
        for(int y=0;y<6;y++)for(int x=0;x<12;x++) {
            double a=-Math.PI/2+y*Math.PI/6,b=a+Math.PI/6;
            double u=x*Math.PI/6+spin,v=u+Math.PI/6;
            face(out,m,sphere(center,radius,a,u),sphere(center,radius,b,u),sphere(center,radius,b,v),sphere(center,radius,a,v),alpha);
        }
    }
    private static Vec3 sphere(Vec3 c,double r,double a,double b) {
        return c.add(Math.cos(a)*Math.cos(b)*r,Math.sin(a)*r,Math.cos(a)*Math.sin(b)*r);
    }
    public static void ring(WorldRenderContext c,Matrix4f m,Vec3 center,double radius,double thickness,float alpha,double angle) {
        int count=32;Vec3 previous=center.add(Math.cos(angle)*radius,0,Math.sin(angle)*radius);
        for(int i=1;i<=count;i++) {
            double a=angle+i*Math.PI*2/count;
            Vec3 next=center.add(Math.cos(a)*radius,0,Math.sin(a)*radius);
            rod(c,m,previous,next,thickness,alpha); previous=next;
        }
    }
    /** A curved woven mesh, rather than a printed plane. */
    public static void net(WorldRenderContext c,Matrix4f m,Vec3 center,double radius,float alpha,double phase) {
        for(int line=-3;line<=3;line++) {
            double offset=line*radius/4, extent=Math.sqrt(radius*radius-offset*offset);
            for(int axis=0;axis<2;axis++) {
                Vec3 last=null;
                for(int j=0;j<=10;j++) {
                    double t=-extent+2*extent*j/10;
                    double lift=.12+Math.max(0,1-(t*t+offset*offset)/(radius*radius))*.45;
                    Vec3 p=center.add(axis==0?t:offset,lift,axis==0?offset:t);
                    if(last!=null)rod(c,m,last,p,.025,alpha);last=p;
                }
            }
        }
        ring(c,m,center.add(0,.12,0),radius,.04,alpha,phase);
    }
    private static void face(VertexConsumer out,Matrix4f m,Vec3 a,Vec3 b,Vec3 c,Vec3 d,float alpha) {
        Vec3 n=b.subtract(a).cross(c.subtract(a));
        n=n.lengthSqr()<1.0E-10?new Vec3(0,1,0):n.normalize();
        vertex(out,m,a,0,0,n,alpha);vertex(out,m,b,1,0,n,alpha);
        vertex(out,m,c,1,1,n,alpha);vertex(out,m,d,0,1,n,alpha);
    }
    private static void vertex(VertexConsumer out,Matrix4f m,Vec3 p,float u,float v,Vec3 n,float alpha) {
        // Uniform up normal: the emissive vertex shader multiplies colour by
        // minecraft_mix_light(...), so per-face normals dim the quads that face away
        // from the light and make every effect look muddy grey. (0,1,0) keeps lightAccum at 1.0.
        out.addVertex(m,(float)p.x,(float)p.y,(float)p.z).setColor(255,255,255,(int)(255*Math.clamp(alpha,0,1)))
                .setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 1.0F, 0.0F);
    }
}
