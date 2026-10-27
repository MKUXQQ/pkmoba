package com.pkmoba.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.LinkedHashMap;
import java.util.Map;

/** Server-owned QWER cues, rendered as persistent, full-bright electric texture meshes. */
public final class PikachuEffects {
    private static final Map<String, Scene> SCENES = new LinkedHashMap<>();
    private static final ResourceLocation BOLT = texture("bolt"), WEB = texture("web"),
            DASH = texture("dash"), RING = texture("ring"), CHARGE = texture("charge"), THUNDER = texture("thunder");
    private PikachuEffects() {}
    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/pikachu_" + name + ".png");
    }
    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(PikachuEffects::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> SCENES.clear());
        ClientTickEvents.END_CLIENT_TICK.register(mc ->
                SCENES.values().removeIf(s -> mc.level != s.level || ++s.age - s.refreshedAge >= s.life));
    }
    public static boolean accept(EffectPayload p) {
        if (p == null || p.effectId() == null || !p.effectId().startsWith("pkmoba:pikachu/")) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return true;
        // Wire format is pkmoba:pikachu/<kind>/<entityId>; the final segment is NOT the kind.
        String[] parts = p.effectId().split("/");
        if (parts.length != 3) return true;
        String kind = parts[1], owner = parts[2];
        Vec3 pos = new Vec3(p.x(), p.y(), p.z()), vec = new Vec3(p.dx(), p.dy(), p.dz());
        if (!finite(pos) || !finite(vec)) return true;
        if (kind.equals("clear")) { SCENES.entrySet().removeIf(e -> e.getKey().startsWith(owner + ":")); return true; }
        int life = switch (kind) {
            case "r_warn", "r_charge", "w_charge", "w_orb_fly" -> 6;
            case "q_bolt" -> 8;
            case "w_orb_burst" -> 14;
            case "r_field" -> 16;
            case "e_start", "e_trail", "e_hit" -> 12;
            case "r_strike" -> 22;
            default -> 0;
        };
        if (kind.equals("w_end")) {
            SCENES.remove(owner + ":w_orb_fly");
            SCENES.remove(owner + ":w_charge");
            return true;
        }
        if (life == 0) return true;
        if (kind.equals("r_strike")) { SCENES.remove(owner + ":r_warn"); SCENES.remove(owner + ":r_charge"); }
        if (kind.equals("w_orb_fly")) SCENES.remove(owner + ":w_charge");
        if (kind.equals("w_orb_burst")) SCENES.remove(owner + ":w_orb_fly");
        if (kind.equals("r_strike")) {
            try {
                HitFeedback.onPikachuThunderStrike(Integer.parseInt(owner));
            } catch (NumberFormatException ignored) {
                return true;
            }
        }
        String key = owner + ":" + kind;
        if (kind.equals("e_trail")) key += ":" + mc.level.getGameTime();
        Scene previous = SCENES.get(key);
        boolean continuous = kind.equals("r_charge") || kind.equals("r_warn")
                || kind.equals("r_field") || kind.equals("w_orb_fly") || kind.equals("w_charge");
        // Keep a cast's birth time. Snapshot refreshes extend TTL, never restart its animation.
        if (continuous && previous != null && previous.level == mc.level) {
            previous.previousPos = previous.pos;
            previous.previousVec = previous.vec;
            previous.pos = pos;
            previous.vec = vec;
            previous.refreshedAge = previous.age;
            previous.updated = System.nanoTime();
            return true;
        }
        Scene scene = new Scene(mc.level, kind, pos, vec, life);
        if (kind.equals("w_orb_fly")) {
            Scene previousFlight = SCENES.get(key);
            scene.facing = previousFlight != null ? previousFlight.facing : vec;
        }
        SCENES.put(key, scene);
        while (SCENES.size() > 160) SCENES.remove(SCENES.keySet().iterator().next());
        return true;
    }
    private static boolean finite(Vec3 v) { return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    private static void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ctx.matrixStack() == null || ctx.consumers() == null) return;
        Vec3 camera = ctx.camera().getPosition();
        Matrix4f matrix = ctx.matrixStack().last().pose();
        double partial = ctx.tickCounter().getGameTimeDeltaPartialTick(false);
        for (Scene s : SCENES.values()) {
            if (s.level != mc.level || s.pos.distanceToSqr(camera) > 160 * 160) continue;
            float alpha = (float)Math.min(1, (s.life - (s.age - s.refreshedAge) - partial) / 3.0);
            double phase = (mc.level.getGameTime() + partial) * .16;
            Vec3 a = s.pos.subtract(camera);
            switch (s.kind) {
                // Q intentionally consists of exactly one textured lightning discharge.
                case "q_bolt" -> beam(ctx, matrix, a, a.add(s.vec), .7, BOLT, alpha);
                case "w_charge" -> {
                    double power = Math.max(0, Math.min(1, s.vec.x));
                    Vec3 center = a.add(0, .35, 0);
                    double pulse = .28 + power * .42;
                    for (int i = 0; i < 3; i++) {
                        double angle = phase * (1.2 + i * .15) + i * Math.PI * 2 / 3;
                        Vec3 mote = center.add(Math.cos(angle) * pulse, Math.sin(phase + i) * .16, Math.sin(angle) * pulse);
                        beam(ctx, matrix, mote, center, .09 + power * .04, BOLT, .85F);
                    }
                }
                case "w_orb_fly" -> {
                    double blend = Math.min(1, (System.nanoTime() - s.updated) / 50_000_000.0);
                    Vec3 moving = s.previousPos.lerp(s.pos, blend).subtract(camera);
                    Vec3 travel = s.vec.lengthSqr() < .001 ? s.facing : s.vec.normalize();
                    Vec3 tail = moving.subtract(travel.scale(.65));
                    beam(ctx, matrix, tail, moving, .65, BOLT, alpha);
                    Vec3 core = moving.add(0, .05, 0);
                    beam(ctx,matrix,moving, moving.add(travel.scale(-.22)), .26, THUNDER, alpha);
                    beam(ctx,matrix,moving, moving.add(travel.scale(.22)), .26, BOLT, alpha);
                    for (int i = 0; i < 4; i++) {
                        double angle = phase + i * Math.PI * .5;
                        Vec3 arc = moving.add(Math.cos(angle) * .38, Math.sin(angle) * .38, Math.sin(angle) * .38);
                        beam(ctx, matrix, core, arc, .07, THUNDER, alpha);
                    }
                }
                case "w_orb_burst" -> {
                    double radius = Math.max(.4, s.vec.x);
                    double grow = Math.min(1.0, (s.age + partial) / 8.0);
                    Vec3 center = a.add(0, .12, 0);
                    plane(ctx, matrix, center, radius * grow, RING, phase, alpha);
                    plane(ctx, matrix, center.add(0, .04, 0), radius * grow * .76, CHARGE, -phase, alpha * .8F);
                    for (int i = 0; i < 12; i++) {
                        double angle = i * Math.PI / 6 + phase * .1;
                        Vec3 edge = center.add(Math.cos(angle) * radius * grow, .08,
                                Math.sin(angle) * radius * grow);
                        beam(ctx, matrix, center.add(0, .15, 0), edge, .14, THUNDER, alpha * .9F);
                    }
                    for (int i = 0; i < 6; i++) {
                        double angle = phase + i * Math.PI / 3;
                        Vec3 mote = center.add(Math.cos(angle) * radius * grow, .4 + i % 2 * .3,
                                Math.sin(angle) * radius * grow);
                        beam(ctx, matrix, mote, mote.add(0, .45, 0), .11, BOLT, alpha);
                    }
                }
                case "r_charge" -> {
                    double blend=Math.min(1,(System.nanoTime()-s.updated)/50_000_000.0);
                    double power=Math.max(0,Math.min(1,(s.previousVec.x+(s.vec.x-s.previousVec.x)*blend)*1.0));
                    a=s.previousPos.lerp(s.pos,blend).subtract(camera);
                    // Server progress is authoritative; packet refreshes do not restart the ramp.
                    double size=.85+power*1.65;
                    float chargeAlpha=(float)(.5+power*.5);
                    plane(ctx,matrix,a.add(0,-.15,0),size,CHARGE,-phase,chargeAlpha);
                    cage(ctx,matrix,a.add(0,.45+power*.75,0),new Vec3(0,0,1),.35+power*.65,phase,chargeAlpha,true);
                    for(int i=0;i<12;i++) {
                        double travel=(s.age*.055+i/12.0)%1.0;
                        double angle=i*Math.PI*2/12+phase*.35;
                        double reach=(1-travel)*(2.4+power);
                        Vec3 mote=a.add(Math.cos(angle)*reach,.3+travel*(1.0+power*2),Math.sin(angle)*reach);
                        beam(ctx,matrix,mote,mote.add(0,.18+power*.15,0),.10+power*.08,BOLT,chargeAlpha);
                    }
                    for(int i=0;i<7;i++) {
                        double angle=phase*1.4+i*Math.PI*2/7;
                        double orbit=3.3-power*1.8;
                        Vec3 edge=a.add(Math.cos(angle)*orbit,1.2+power*2,Math.sin(angle)*orbit);
                        beam(ctx,matrix,edge,a.add(0,.6+power*.7,0),.25+power*.2,THUNDER,chargeAlpha);
                    }
                    // A growing upward leader unmistakably separates gathering from the sky strike.
                    beam(ctx,matrix,a.add(0,.7,0),a.add(0,1.2+power*5,0),.35+power*.65,BOLT,chargeAlpha);
                }
                case "r_warn" -> plane(ctx, matrix, a.add(0,.10,0), Math.max(1,s.vec.x), RING, phase, alpha * .75F);
                case "r_field" -> {
                    double radius = Math.max(1, Math.min(8,s.vec.x));
                    plane(ctx, matrix, a.add(0,.12,0), radius, WEB, phase * .15, alpha);
                    plane(ctx, matrix, a.add(0,.16,0), radius, RING, -phase * .12, alpha);
                    for (int i=0;i<6;i++) {
                        double angle=phase+i*Math.PI/3;
                        Vec3 edge=a.add(Math.cos(angle)*radius, .1, Math.sin(angle)*radius);
                        beam(ctx,matrix,edge,edge.add(0,.85,0),.30,BOLT,alpha);
                    }
                }
                case "e_start", "e_hit" -> {
                    plane(ctx,matrix,a.add(0,.16,0),1.8,RING,phase,alpha);
                    beam(ctx,matrix,a.add(0,.3,0),a.add(0,2.4,0),1.5,DASH,alpha);
                }
                case "e_trail" -> {
                    Vec3 dir=s.vec.lengthSqr()<.001 ? new Vec3(0,0,1):s.vec.normalize();
                    beam(ctx,matrix,a.add(0,.55,0),a.add(dir.scale(-2.3)).add(0,.55,0),1.2,DASH,alpha);
                }
                case "r_strike" -> {
                    Vec3 end=a.add(s.vec);
                    beam(ctx,matrix,a,end,4.2,THUNDER,alpha);
                    beam(ctx,matrix,a,end,1.25,BOLT,alpha);
                    for(int i=0;i<5;i++) {
                        double angle=i*Math.PI*2/5+phase*.1;
                        beam(ctx,matrix,a.add(Math.cos(angle)*3.4,0,Math.sin(angle)*3.4),end,1.7,THUNDER,alpha*.8F);
                    }
                    double radius=com.pkmoba.combat.PikachuCombat.R_RADIUS;
                    double spread=Math.min(1,(s.age+partial)/9.0);
                    for(int i=0;i<8;i++) {
                        double angle=i*Math.PI/4;
                        Vec3 edge=end.add(Math.cos(angle)*radius*spread,.18,Math.sin(angle)*radius*spread);
                        beam(ctx,matrix,end.add(0,.2,0),edge,.65,THUNDER,alpha);
                    }
                    plane(ctx,matrix,end.add(0,.18,0),radius*(.4+.6*spread),RING,phase,alpha);
                    plane(ctx,matrix,end.add(0,.22,0),radius,CHARGE,-phase,alpha*.7F);
                }
                default -> { }
            }
        }
    }
    /** Bounded electric arcs add volume without rendering a flat net. */
    private static void cage(WorldRenderContext c,Matrix4f m,Vec3 center,Vec3 facing,double radius,double phase,float alpha,boolean caught) {
        Vec3 normal=new Vec3(facing.x,0,facing.z);
        normal=normal.lengthSqr()<.001?new Vec3(0,0,1):normal.normalize();
        Vec3 side=new Vec3(-normal.z,0,normal.x);
        for(int loop=0;loop<2;loop++) {
            for(int i=0;i<24;i++) {
                double t0=i*Math.PI*2/24,t1=(i+1)*Math.PI*2/24;
                double wobble=1+.035*Math.sin(phase*3+i);
                Vec3 p0=center.add(side.scale(Math.cos(t0)*radius*wobble)).add(0,Math.sin(t0)*radius,0)
                        .add(normal.scale(Math.sin(t0*2+loop*Math.PI)*radius*.22));
                Vec3 p1=center.add(side.scale(Math.cos(t1)*radius*wobble)).add(0,Math.sin(t1)*radius,0)
                        .add(normal.scale(Math.sin(t1*2+loop*Math.PI)*radius*.22));
                beam(c,m,p0,p1,caught?.09:.065,BOLT,alpha);
            }
        }
    }
    /** Two crossed full-bright sheets keep the lightning visible from overhead and side views. */
    private static void beam(WorldRenderContext c, Matrix4f m, Vec3 a, Vec3 b, double width, ResourceLocation tex, float alpha) {
        Vec3 axis=b.subtract(a); if(axis.lengthSqr()<.0001)return;
        Vec3 side=axis.normalize().cross(new Vec3(0,1,0));
        if(side.lengthSqr()<.01)side=new Vec3(1,0,0);
        side=side.normalize().scale(width*.5);
        Vec3 other=axis.normalize().cross(side).normalize().scale(width*.5);
        VertexConsumer v=c.consumers().getBuffer(RenderType.entityTranslucentEmissive(tex));
        quad(v,m,a.subtract(side),b.subtract(side),b.add(side),a.add(side),alpha);
        quad(v,m,a.subtract(other),b.subtract(other),b.add(other),a.add(other),alpha);
    }
    private static void plane(WorldRenderContext c,Matrix4f m,Vec3 p,double r,ResourceLocation tex,double angle,float alpha) {
        Vec3 x=new Vec3(Math.cos(angle)*r,0,Math.sin(angle)*r), z=new Vec3(-Math.sin(angle)*r,0,Math.cos(angle)*r);
        VertexConsumer v=c.consumers().getBuffer(RenderType.entityTranslucentEmissive(tex));
        quad(v,m,p.subtract(x).subtract(z),p.add(x).subtract(z),p.add(x).add(z),p.subtract(x).add(z),alpha);
    }
    private static void quad(VertexConsumer v,Matrix4f m,Vec3 a,Vec3 b,Vec3 c,Vec3 d,float alpha) {
        vertex(v,m,a,0,0,alpha);vertex(v,m,b,1,0,alpha);vertex(v,m,c,1,1,alpha);vertex(v,m,d,0,1,alpha);
        vertex(v,m,d,0,1,alpha);vertex(v,m,c,1,1,alpha);vertex(v,m,b,1,0,alpha);vertex(v,m,a,0,0,alpha);
    }
    private static void vertex(VertexConsumer v,Matrix4f m,Vec3 p,float u,float t,float alpha) {
        v.addVertex(m,(float)p.x,(float)p.y,(float)p.z).setColor(255,255,255,(int)(255*Math.max(0,Math.min(1,alpha))))
                .setUv(u,t).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0,1,0);
    }
    private static final class Scene {
        final ClientLevel level; final String kind; Vec3 pos,vec,previousPos,previousVec; final int life;
        int age,refreshedAge;
        long updated=System.nanoTime();
        Vec3 facing;
        Scene(ClientLevel level,String kind,Vec3 pos,Vec3 vec,int life) {
            this.level=level;this.kind=kind;this.pos=pos;this.vec=vec;this.previousPos=pos;
            this.previousVec=vec;this.facing=vec;this.life=life;
        }
    }
}
