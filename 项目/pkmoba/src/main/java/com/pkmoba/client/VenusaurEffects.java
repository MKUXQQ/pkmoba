package com.pkmoba.client;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.combat.Skill;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.LightTexture;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Textured Venusaur skill scenes and the matching channel-only cast pose. */
public final class VenusaurEffects {
    private static final ResourceLocation LASH = tex("venusaur/power_lash");
    private static final ResourceLocation IMPACT = tex("venusaur/power_impact");
    private static final ResourceLocation AURA = tex("venusaur/sludge_aura");
    private static final ResourceLocation VINE = tex("venusaur/vine_strip");
    private static final ResourceLocation LEAF = tex("venusaur/leaf_strip");
    private static final ResourceLocation RAZOR = tex("venusaur/razor_leaf");
    private static final ResourceLocation SEED = tex("venusaur/seed_projectile");
    private static final ResourceLocation FLOWERS = tex("venusaur/flower_sea");
    private static final ResourceLocation BEAM = tex("venusaur/solar_beam");
    private static final ResourceLocation SLUDGE = tex("venusaur/toxic_glob");
    private static final ResourceLocation SLUDGE_POOL = tex("venusaur/sludge_pool");
    private static final ResourceLocation SLUDGE_HIGHLIGHT = tex("venusaur/sludge_highlight");
    private static final List<Scene> SCENES = new ArrayList<>();
    private static boolean initialized;

    private VenusaurEffects() {}
    private static ResourceLocation tex(String path) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/" + path.replace('/', '_') + ".png");
    }

    public static void init() {
        if (initialized) return;
        initialized = true;
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.VenusaurEffectPayload.TYPE,
                (payload, context) -> context.client().execute(() -> accept(payload)));
        VenusaurSkillAnimator.init();
        WorldRenderEvents.AFTER_TRANSLUCENT.register(VenusaurEffects::render);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            synchronized (SCENES) { SCENES.clear(); }
        });
    }

    private static void accept(com.pkmoba.network.VenusaurEffectPayload payload) {
        if (payload == null || payload.position() == null || !finite(payload.position())) return;
        int life = Math.max(1, payload.durationTicks());
        synchronized (SCENES) {
            if ("CLEAR".equals(payload.kind()) || payload.durationTicks() <= 0) {
                SCENES.removeIf(s -> s.entityId.equals(payload.entityId()));
                return;
            }
            long born = System.nanoTime();
            if ("E_BEAM".equals(payload.kind())) SCENES.removeIf(s -> s.entityId.equals(payload.entityId()) && "E_CHARGE".equals(s.kind));
            SCENES.removeIf(s -> s.entityId.equals(payload.entityId()) && s.kind.equals(payload.kind())
                    && "R_FIELD".equals(s.kind) && (s.value < 0) != (payload.value() < 0));
            for (Scene scene : SCENES) {
                if (scene.entityId.equals(payload.entityId()) && scene.kind.equals(payload.kind())) {
                    scene.update(payload, born, life);
                    return;
                }
            }
            SCENES.add(new Scene(payload, born, life));
        }
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || context.matrixStack() == null || context.consumers() == null) return;
        long now = System.nanoTime();
        PoseStack pose = context.matrixStack();
        pose.pushPose();
        Vec3 cam = context.camera().getPosition();
        pose.translate(-cam.x, -cam.y, -cam.z);
        synchronized (SCENES) {
            Iterator<Scene> it = SCENES.iterator();
            while (it.hasNext()) {
                Scene s = it.next();
                double age = (now - s.born) / 50_000_000.0;
                double remaining = (s.expires - now) / 50_000_000.0;
                if (remaining <= 0) { it.remove(); continue; }
                boolean continuous = s.kind.startsWith("E_") || s.kind.startsWith("W_") || "Q_VINE".equals(s.kind);
                float fade = continuous ? 1.0F : (float) Math.min(1.0, Math.min(age / 4.0, remaining / 10.0));
                // The burst must be visible on the impact tick, not fade in after its shockwave.
                if ("R_FIELD".equals(s.kind) && s.value >= 0) fade = (float)Math.min(1, remaining / 10.0);
                renderScene(mc, context, pose, s, age, Math.max(0.1F, fade));
            }
        }
        pose.popPose();
    }

    private static void renderScene(Minecraft mc, WorldRenderContext c, PoseStack pose, Scene s, double age, float alpha) {
        Vec3 d = s.direction.lengthSqr() < 1e-5 ? new Vec3(0, 0, 1) : s.direction.normalize();
        if ("Q_VINE".equals(s.kind)) {
            Vec3 anchor = flowerAnchor(mc, s.entityId, s.position);
            double phase = Math.max(age, s.value + (System.nanoTime() - s.updated) / 50_000_000.0);
            Vec3 side = new Vec3(-d.z, 0, d.x);
            if (side.lengthSqr() < .001) side = new Vec3(1, 0, 0);
            side = side.normalize();
            // Coil visibly around the flower, crack outward at tick20 (the actual hit), then recoil.
            if (phase < 14) {
                double tension = Math.min(1, phase / 14);
                for (int arm = -1; arm <= 1; arm += 2) {
                    Vec3 previous = anchor.add(side.scale(arm * .45));
                    for (int i = 1; i <= 18; i++) {
                        double t = i / 18.0, angle = t * Math.PI * 3.5 + phase * .12 * arm;
                        Vec3 next = anchor.add(side.scale(Math.cos(angle) * (1.4 - .45 * tension) * t))
                                .add(d.scale(-Math.sin(angle) * .9 * t)).add(0, t * (1.0 + tension), 0);
                        Vec3 delta = next.subtract(previous);
                        crossStrip(c, pose, previous, delta.normalize(), delta.length(), .21, LASH, alpha);
                        previous = next;
                    }
                }
                ring(c, pose, anchor.add(0,-.55,0), 1.7 - tension * .65, .12, LEAF, alpha);
            } else {
                double strike = smooth(Math.min(1, (phase - 14) / 6.0));
                double retract = phase <= 20 ? 1 : Math.max(0, (30 - phase) / 10);
                double reach = s.radius * strike * retract;
                curvedVine(c, pose, anchor.add(side.scale(.38)), d, reach, phase, .44, alpha);
                curvedVine(c, pose, anchor.add(side.scale(-.38)), d, reach, phase + 1.7, .35, alpha);
                if (phase >= 19.5 && phase < 27) {
                    float flash = (float)Math.max(0, 1 - (phase - 20) / 7);
                    Vec3 tip = anchor.add(d.scale(s.radius));
                    orb(c, pose, tip, 1.1 + (phase - 20) * .08, IMPACT, alpha * flash);
                    ring(c, pose, tip.add(0,.04,0), .6 + (phase - 20) * .28, .17, IMPACT, alpha * flash);
                    for (int i=0;i<8;i++) {
                        double a=i*Math.PI/4;
                        Vec3 arc = side.scale(Math.cos(a)).add(0,Math.sin(a),0);
                        crossStrip(c,pose,tip,arc,1.2,.16,LEAF,alpha*flash);
                    }
                }
            }
        } else if ("W_CHARGE".equals(s.kind)) {
            Vec3 anchor = flowerAnchor(mc, s.entityId, s.position);
            double charge = Math.min(1, age / 20.0);
            for (int i = 0; i < 6; i++) {
                double phase = age * .18 + i * Math.PI / 3.0;
                double radius = 1.25 - charge * .65;
                Vec3 leaf = anchor.add(Math.cos(phase) * radius, .15 + .18 * Math.sin(phase * 2), Math.sin(phase) * radius);
                crossStrip(c, pose, leaf, new Vec3(-Math.sin(phase), .15, Math.cos(phase)).normalize(), .55, .18, RAZOR, alpha);
            }
            ring(c, pose, anchor, 1.25 - charge * .65, .06, LEAF, alpha * .65F);
        } else if (s.kind.startsWith("W_LEAF")) {
            double t = Math.min(1, (System.nanoTime() - s.updated) / 50_000_000.0);
            Vec3 leaf = s.position.add(s.direction.scale(t));
            Vec3 side = new Vec3(-d.z, 0, d.x).normalize();
            for (int i = -1; i <= 1; i++) {
                Vec3 at = leaf.add(side.scale(i * .23)).add(0, Math.sin((s.value + t) * .7 + i) * .08, 0);
                crossStrip(c, pose, at.subtract(d.scale(.25)), d, .65, .20, RAZOR, alpha);
                crossStrip(c, pose, at.subtract(d.scale(.7)), d, .5, .06, LEAF, alpha * .55F);
            }
        } else if ("SEED_SHOT".equals(s.kind)) {
            orb(c, pose, s.position, .18, SEED, alpha);

        } else if ("R_FIELD".equals(s.kind)) {
            if (s.value < 0) {
                double charge = Math.min(1.0, age / 30.0);
                double t = smooth(Math.max(0.0, Math.min(1.0, (age - 30.0) / Math.max(1, 48 - 30))));
                Vec3 target = s.position.add(s.direction);
                Vec3 origin = age < 30 ? flowerAnchor(mc, s.entityId, s.position) : s.position;
                Vec3 flight = origin.lerp(target, t).add(0, Math.sin(Math.PI * t) * 3.2, 0);
                if (age < 30) {
                    // Charizard-R style converging charge: broad aura, rising rings and inflowing energy.
                    disc(c, pose, origin.add(0,-1.3,0), (2.8 + charge) * .55, AURA, alpha * .65F);
                    for (int i=0;i<12;i++) {
                        double angle=age*.13+i*Math.PI/6;
                        double r=1.54*(1-charge*.7);
                        Vec3 mote=origin.add(Math.cos(angle)*r, .5*Math.sin(angle*2), Math.sin(angle)*r);
                        orb(c,pose,mote,.12+charge*.10,SLUDGE_HIGHLIGHT,alpha);
                        crossStrip(c,pose,mote,origin.subtract(mote).normalize(),.65,.10,AURA,alpha*.8F);
                    }
                    for (int ring = 0; ring < 3; ring++) {
                        double radius = ((1.6 - charge * .8) + ring * .22) * .55;
                        ring(c, pose, origin.add(0, -.6 + ring * .3, 0), radius, .10, SLUDGE, alpha * .75F);
                    }
                }
                double lobing = 1.0 + .08 * Math.sin(age * .8);
                sludgeBlob(c, pose, flight, (.40 + .65 * charge) * lobing * .55, age, alpha);
                for (int i = 0; i < 6; i++) {
                    double phase = age * .31 + i * Math.PI / 3.0;
                    double lobe = (.22 + .035 * Math.sin(age * .5 + i)) * .55;
                    Vec3 bump = flight.add(Math.cos(phase) * lobe, Math.sin(age * .22 + i) * .11,
                            Math.sin(phase) * lobe);
                    orb(c, pose, bump, (.12 + .025 * Math.sin(age * .4 + i)) * .55, SLUDGE, alpha);
                }
                orb(c, pose, flight.add(0, .24 + .04 * Math.sin(age * .5), 0), .075, SLUDGE_HIGHLIGHT, alpha);
                Vec3 trail = flight.subtract(origin);
                if (trail.lengthSqr() > .01) {
                    Vec3 trailDir = trail.normalize().scale(-1);
                    for (int i = 1; i <= 3; i++) {
                        double trailOffset = i * .35;
                        Vec3 drip = flight.add(trailDir.scale(trailOffset));
                        orb(c, pose, drip, .095 - i * .015, SLUDGE, alpha * (.82F - i * .12F));
                    }
                    strip(c, pose, flight, trailDir, Math.min(1.0, trail.length() * .35), .16, SLUDGE, alpha * .48F);
                }
            } else {
                double burst = Math.max(0, 1.0 - age / 7.0);
                double pulse = 1.0 + .035 * Math.sin(age * .22);
                double radius = s.radius * pulse; // Match the authoritative sludge damage radius.
                disc(c, pose, s.position.add(0, .005, 0), radius * 1.35, SLUDGE_POOL, alpha * .76F);
                disc(c, pose, s.position.add(0, .012, 0), radius * .92, SLUDGE, alpha * .92F);
                ring(c, pose, s.position.add(0, .035, 0), radius * (.83 + .03 * Math.sin(age * .18)), .07, SLUDGE_HIGHLIGHT, alpha * .76F);
                for (int i = 0; i < 7; i++) {
                    double a = age * .01 + Math.PI * 2.0 * i / 7.0;
                    double r = radius * (.62 + .05 * Math.sin(age * .12 + i * 1.7));
                    Vec3 lobe = s.position.add(Math.cos(a) * r, .035, Math.sin(a) * r);
                    orb(c, pose, lobe, .10 + .018 * Math.sin(age * .17 + i), SLUDGE, alpha);
                }
                for (int i = 0; i < 5; i++) {
                    double a = age * .012 + Math.PI * 2.0 * i / 5.0;
                    Vec3 bubble = s.position.add(Math.cos(a) * radius * (.24 + i * .1), .055, Math.sin(a) * radius * (.24 + i * .1));
                    orb(c, pose, bubble, .045 + (i % 2) * .015, SLUDGE_HIGHLIGHT, alpha * .88F);
                }
                if (age < 12) {
                    double shock = Math.min(1, age / 10);
                    ring(c,pose,s.position.add(0,.14,0),radius*(.3+shock*.85),.28,AURA,alpha*(float)(1-shock));
                    for(int column=0;column<8;column++) {
                        double angle=column*Math.PI/4;
                        Vec3 base=s.position.add(Math.cos(angle)*radius*.65,0,Math.sin(angle)*radius*.65);
                        crossStrip(c,pose,base,new Vec3(Math.cos(angle)*.25,1,Math.sin(angle)*.25).normalize(),
                                (1-shock)*1.9,.33,SLUDGE,alpha*(float)(1-shock));
                    }
                }
                if (age < 8) {
                    for (int i = 0; i < 12; i++) {
                        double a = Math.PI * 2.0 * i / 12.0 + age * .18;
                        double reach = radius * (1.0 + .55 * burst) * (i % 3 == 0 ? 1.18 : 1.0);
                        Vec3 droplet = s.position.add(Math.cos(a) * reach,
                                .12 + burst * (.28 + .10 * Math.sin(age * .4 + i)), Math.sin(a) * reach);
                        double size = .065 + .035 * burst + (i % 3) * .012;
                        orb(c, pose, droplet, size, SLUDGE, alpha * (float)(.8 * burst));
                        if (i % 3 == 0) {
                            strip(c, pose, droplet.add(0, .04, 0), new Vec3(0, -1, 0), .12 + .12 * burst,
                                    .035 + .01 * burst, SLUDGE, alpha * (float)(.72 * burst));
                        }
                    }
                    orb(c, pose, s.position.add(0, .32 * burst, 0), .18 * burst, SLUDGE, alpha * (float)(.45 * burst));
                }
            }
        } else if ("E_CHARGE".equals(s.kind)) {
            double pulse = .40 + .14 * (float)Math.sin(age * .24);
            orb(c, pose, s.position, pulse, BEAM, alpha);
            ring(c, pose, s.position, .85 + .15 * Math.sin(age * .18), .10, LEAF, alpha);
            for (int i = 0; i < 4; i++) {
                double a = age * .12 + Math.PI * 2 * i / 4.0;
                Vec3 mote = s.position.add(Math.cos(a) * .58, Math.sin(a * 1.7) * .25, Math.sin(a) * .58);
                orb(c, pose, mote, .075, FLOWERS, alpha);
            }
        } else if ("E_BEAM".equals(s.kind)) {
            double blend = Math.min(1, (System.nanoTime() - s.updated) / 50_000_000.0);
            Vec3 beamStart = s.previousPosition.lerp(s.position, blend);
            Vec3 beamEnd = s.previousPosition.add(s.previousDirection.normalize().scale(s.previousRadius))
                    .lerp(s.position.add(d.scale(s.radius)), blend);
            Vec3 beamDelta = beamEnd.subtract(beamStart);
            d = beamDelta.lengthSqr() < 1.0e-6 ? d : beamDelta.normalize();
            double beamWidth = Math.max(.42, s.value > 0 ? s.value * .55 : .47);
            double beamLength = Math.min(com.pkmoba.combat.VenusaurCombat.E_RANGE, Math.max(.01, beamDelta.length()));
            crossStrip(c, pose, beamStart, d, beamLength, beamWidth * 1.7, LEAF, alpha * .70F);
            crossStrip(c, pose, beamStart.add(0, .025, 0), d, beamLength, beamWidth, BEAM, alpha);
            crossStrip(c, pose, beamStart.add(0, .05, 0), d, beamLength, beamWidth * .34, FLOWERS, alpha);
            int streaks = 9;
            for (int i = 0; i < streaks; i++) {
                double t = ((age * .09 + i / (double) streaks) % 1.0) * beamLength;
                Vec3 streak = beamStart.add(d.scale(t));
                double offset = (i % 2 == 0 ? 1 : -1) * beamWidth * .65;
                Vec3 glint = streak.add(i % 2 == 0 ? new Vec3(0, offset, 0) : new Vec3(beamWidth * .5, 0, 0));
                orb(c, pose, glint, .06 + .02 * (i % 3), FLOWERS, alpha);
            }
        }
    }

    private static Vec3 flowerAnchor(Minecraft mc, UUID id, Vec3 fallback) {
        if (mc.level == null || id == null) return fallback;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (id.equals(entity.getUUID())) {
                if (entity instanceof PokemonEntity pokemon) {
                    try { return com.pkmoba.combat.VenusaurAnchor.position(pokemon); }
                    catch (Throwable ignored) { }
                }
                return entity.position().add(0.0, 34.5 / 16.0 * 1.2, 3.0 / 16.0 * 1.2);
            }
        }
        return fallback;
    }

    private static void curvedVine(WorldRenderContext c, PoseStack p, Vec3 o, Vec3 dir, double length, double phase, double width, float alpha) {
        if (length <= 0.05) return;
        Vec3 side = new Vec3(-dir.z, 0, dir.x).normalize();
        Vec3 previous = o;
        int segments = 18;
        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            Vec3 next = o.add(dir.scale(length * t)).add(side.scale(Math.sin(t * Math.PI) * Math.sin(phase * .18 + t * 2.0) * .45));
            crossStrip(c, p, previous, next.subtract(previous).normalize(), next.subtract(previous).length(), width * (1.0 - .45 * t), LASH, alpha);
            previous = next;
        }
    }

    private static double smooth(double t) { return t * t * (3.0 - 2.0 * t); }
    private static void strip(WorldRenderContext c, PoseStack p, Vec3 o, Vec3 dir, double length, double width, ResourceLocation tex, float alpha) {
        Vec3 side = new Vec3(-dir.z, 0, dir.x).normalize().scale(width);
        quad(c, p, o.add(side), o.subtract(side), o.add(dir.scale(length)).subtract(side), o.add(dir.scale(length)).add(side), tex, alpha);
    }
    private static void crossStrip(WorldRenderContext c, PoseStack p, Vec3 o, Vec3 dir, double length, double width, ResourceLocation tex, float alpha) {
        strip(c, p, o, dir, length, width, tex, alpha);
        Vec3 horizontal = new Vec3(-dir.z, 0, dir.x);
        if (horizontal.lengthSqr() < 1.0e-5) horizontal = new Vec3(1, 0, 0);
        horizontal = horizontal.normalize();
        Vec3 side = dir.cross(horizontal).normalize().scale(width);
        quad(c, p, o.add(side), o.subtract(side), o.add(dir.scale(length)).subtract(side), o.add(dir.scale(length)).add(side), tex, alpha * .94F);
    }
    private static void disc(WorldRenderContext c, PoseStack p, Vec3 o, double r, ResourceLocation tex, float alpha) {
        quad(c, p, o.add(-r, .03, -r), o.add(-r, .03, r), o.add(r, .03, r), o.add(r, .03, -r), tex, alpha);
    }
    private static void ring(WorldRenderContext c, PoseStack p, Vec3 o, double radius, double thickness, ResourceLocation tex, float alpha) {
        int segments = 32;
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2.0 * i / segments;
            double a1 = Math.PI * 2.0 * (i + 1) / segments;
            Vec3 inner0 = o.add(Math.cos(a0) * (radius - thickness), 0, Math.sin(a0) * (radius - thickness));
            Vec3 outer0 = o.add(Math.cos(a0) * (radius + thickness), 0, Math.sin(a0) * (radius + thickness));
            Vec3 inner1 = o.add(Math.cos(a1) * (radius - thickness), 0, Math.sin(a1) * (radius - thickness));
            Vec3 outer1 = o.add(Math.cos(a1) * (radius + thickness), 0, Math.sin(a1) * (radius + thickness));
            quad(c, p, inner0, outer0, outer1, inner1, tex, alpha);
        }
    }
    private static void sludgeBlob(WorldRenderContext c, PoseStack p, Vec3 center, double radius, double age, float alpha) {
        orb(c, p, center, radius, SLUDGE, alpha);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2.0 * i / 8.0 + age * .19;
            double lump = radius * (.58 + .04 * Math.sin(age * .6 + i));
            Vec3 bump = center.add(Math.cos(a) * lump, Math.sin(age * .16 + i) * radius * .27,
                    Math.sin(a) * lump);
            orb(c, p, bump, radius * (.34 + .035 * Math.sin(age * .4 + i)), SLUDGE, alpha);
        }
        Vec3 glint = center.add(-radius * .28, radius * .66, radius * .24);
        orb(c, p, glint, radius * .24, SLUDGE_HIGHLIGHT, alpha * .92F);
    }
    private static void orb(WorldRenderContext c, PoseStack p, Vec3 o, double r, ResourceLocation tex, float alpha) {
        Vec3 up = new Vec3(0, 1, 0), side = new Vec3(1, 0, 0), cross = new Vec3(0, 0, 1);
        quad(c, p, o.add(side.scale(-r)).add(up.scale(-r)), o.add(side.scale(-r)).add(up.scale(r)), o.add(side.scale(r)).add(up.scale(r)), o.add(side.scale(r)).add(up.scale(-r)), tex, alpha);
        quad(c, p, o.add(cross.scale(-r)).add(up.scale(-r)), o.add(cross.scale(-r)).add(up.scale(r)), o.add(cross.scale(r)).add(up.scale(r)), o.add(cross.scale(r)).add(up.scale(-r)), tex, alpha);
    }
    private static void quad(WorldRenderContext c, PoseStack p, Vec3 a, Vec3 b, Vec3 d, Vec3 e, ResourceLocation tex, float alpha) {
        VertexConsumer v = c.consumers().getBuffer(RenderType.entityTranslucentEmissive(tex));
        vertex(v, p, a, 0, 0, alpha); vertex(v, p, b, 0, 1, alpha); vertex(v, p, d, 1, 1, alpha); vertex(v, p, e, 1, 0, alpha);
        vertex(v, p, e, 1, 0, alpha); vertex(v, p, d, 1, 1, alpha); vertex(v, p, b, 0, 1, alpha); vertex(v, p, a, 0, 0, alpha);
    }
    private static void vertex(VertexConsumer v, PoseStack p, Vec3 x, float u, float t, float a) {
        v.addVertex(p.last(), (float)x.x, (float)x.y, (float)x.z).setColor(1,1,1,a).setUv(u,t)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0,1,0);
    }
    private static final class Scene {
        final UUID entityId;
        final String kind;
        final long born;
        Vec3 position;
        Vec3 direction;
        double radius;
        Vec3 previousPosition;
        Vec3 previousDirection;
        double previousRadius;
        int life;
        float value;
        long updated;
        long expires;
        Scene(com.pkmoba.network.VenusaurEffectPayload payload, long now, int life) {
            this.entityId = payload.entityId(); this.kind = payload.kind(); this.born = now;
            update(payload, now, life);
        }
        void update(com.pkmoba.network.VenusaurEffectPayload payload, long now, int life) {
            this.previousPosition = this.position == null ? payload.position() : this.position;
            this.previousDirection = this.direction == null ? payload.direction() : this.direction;
            this.previousRadius = this.position == null ? Math.max(.01, payload.radius()) : this.radius;
            this.position = payload.position();
            this.direction = payload.direction() == null ? new Vec3(0, 0, 1) : payload.direction();
            if (this.previousDirection == null) this.previousDirection = this.direction;
            this.radius = Math.max(.01, payload.radius()); this.life = life; this.value = payload.value();
            this.updated = now;
            // One-tick grace bridges packet/render scheduling without restarting fade or motion phase.
            this.expires = now + (life + 1L) * 50_000_000L;
        }
    }
}
