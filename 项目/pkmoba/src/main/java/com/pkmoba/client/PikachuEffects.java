package com.pkmoba.client;

import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-owned QWER cues, rendered as dense, layered, full-bright electric texture meshes.
 *
 * <p>Every cue is a composition rather than a single primitive: a jagged core bolt, a wide
 * glow shell, branching arcs, orbiting sparks, ground shock rings and, for the ultimate, a
 * radiating electric cage. Nothing is camera-facing or a flat sheet.
 */
public final class PikachuEffects {
    private static final Map<String, Scene> SCENES = new LinkedHashMap<>();
    private static final double TAU = Math.PI * 2;
    private PikachuEffects() {}

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
            case "r_warn", "r_charge", "w_charge", "w_orb_fly" -> 10;
            case "q_bolt" -> 15;
            case "w_orb_burst" -> 26;
            case "r_field" -> 30;
            case "e_start", "e_trail", "e_hit" -> 19;
            case "r_strike" -> 38;
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
        while (SCENES.size() > 200) SCENES.remove(SCENES.keySet().iterator().next());
        return true;
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }


    /** Blocky vanilla spark garnish so Pikachu reads as busy even at a glance. */
    private static void electricParticles(Scene s, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || alpha <= .05f) return;
        long now = mc.level.getGameTime();
        java.util.Random rnd = new java.util.Random((long) (s.pos.x * 92821 + s.pos.z * 68917) * 31 + now / 2);
        if (rnd.nextFloat() > .7f) return;
        Vec3 end = s.pos.add(s.vec);
        int n = 3 + rnd.nextInt(4);
        for (int i = 0; i < n; i++) {
            double f = rnd.nextDouble();
            Vec3 p = s.vec.lengthSqr() > .01 ? s.pos.add(s.vec.scale(f)) : s.pos.add(rnd.nextDouble() - .5, rnd.nextDouble(), rnd.nextDouble() - .5);
            double jx = (rnd.nextDouble() - .5) * .5, jy = (rnd.nextDouble() - .2) * .5, jz = (rnd.nextDouble() - .5) * .5;
            mc.level.addParticle(rnd.nextFloat() < .65f ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.END_ROD,
                    p.x + jx, p.y + jy, p.z + jz,
                    (rnd.nextDouble() - .5) * .1, rnd.nextDouble() * .12, (rnd.nextDouble() - .5) * .1);
        }
    }

    private static void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ctx.matrixStack() == null || ctx.consumers() == null) return;
        Vec3 camera = ctx.camera().getPosition();
        Matrix4f matrix = ctx.matrixStack().last().pose();
        double partial = ctx.tickCounter().getGameTimeDeltaPartialTick(false);
        for (Scene s : SCENES.values()) {
            if (s.level != mc.level || s.pos.distanceToSqr(camera) > 160 * 160) continue;
            double alive = s.life - (s.age - s.refreshedAge) - partial;
            float alpha = (float) Math.min(1, alive / 4.0);
            if (alpha <= 0) continue;
            double t = (mc.level.getGameTime() + partial) * .16 + s.seed;
            Vec3 a = s.pos.subtract(camera);
            // Chunky vanilla electric sparkle: the pixel-Minecraft layer on top of the painted sheets.
            electricParticles(s, alpha);
            // Per-skill painted layer first, then the original electric core geometry on top.
            com.pkmoba.client.fx.PikachuFx.render(ctx, s.kind, s.pos, s.vec, t, alpha);
            switch (s.kind) {
                case "q_bolt" -> {
                    Vec3 end = a.add(s.vec);
                    glowBolt(ctx, matrix, a, end, .062, alpha, 17);
                    double len = s.vec.length();
                    Vec3 axis = s.vec.normalize();
                    for (int i = 0; i < 4; i++) {
                        double f = .22 + i * .19;
                        Vec3 from = a.add(s.vec.scale(f));
                        Vec3 side = perpendicular(s.vec).scale(.28 + .18 * (i % 2 == 0 ? 1 : -1));
                        glowBolt(ctx, matrix, from, from.add(side).add(axis.scale(len * .12)), .022, alpha * .7F, i + 3);
                    }
                    arcs(ctx, matrix, a, .7, 5, alpha * .8F, t);
                    sparks(ctx, matrix, end, .75, 9, alpha * .85F, t, .045);
                    ElectricSkillMesh.ring(ctx, matrix, end.add(0, -.05, 0), .30, .018, alpha * .5F, t);
                }
                case "w_charge", "r_charge" -> {
                    boolean ult = s.kind.equals("r_charge");
                    double power = Math.max(0, Math.min(1, s.vec.x));
                    double size = (ult ? .26 : .15) + power * (ult ? .52 : .24);
                    Vec3 core = a.add(0, .45, 0);
                    ElectricSkillMesh.orb(ctx, matrix, core, size * 1.45, alpha * .28F, -t * .5);
                    ElectricSkillMesh.orb(ctx, matrix, core, size, alpha * .95F, t);
                    ElectricSkillMesh.orb(ctx, matrix, core, size * .62, alpha, -t * .8);
                    int spokes = ult ? 8 : 4;
                    for (int i = 0; i < spokes; i++) {
                        double angle = t * .6 + i * TAU / spokes;
                        Vec3 edge = core.add(Math.cos(angle) * (size + .22), Math.sin(angle * 2) * .16, Math.sin(angle) * (size + .22));
                        glowBolt(ctx, matrix, edge, core, .018, alpha, i);
                    }
                    groundRings(ctx, matrix, a.add(0, .06, 0), size * 4.2, alpha * .75F, ult ? 4 : 3, t);
                    sparks(ctx, matrix, core, size * 2.1, ult ? 16 : 9, alpha * .9F, t + .4, .05);
                    if (ult) {
                        cage(ctx, matrix, a, Math.max(1.4, size * 4.5), 10, alpha * .55F, t, 1.15);
                        verticalArcs(ctx, matrix, a, 1.5, alpha * .5F, t);
                    }
                }
                case "w_orb_fly" -> {
                    double blend = Math.min(1, (System.nanoTime() - s.updated) / 50_000_000.0);
                    Vec3 moving = s.previousPos.lerp(s.pos, blend).subtract(camera);
                    Vec3 dir = s.vec.lengthSqr() < .001 ? s.facing : s.vec.normalize();
                    ElectricSkillMesh.orb(ctx, matrix, moving, .40, alpha * .30F, -t * .4);
                    ElectricSkillMesh.orb(ctx, matrix, moving, .28, alpha, t);
                    for (int i = 0; i < 3; i++) {
                        glowBolt(ctx, matrix, moving.subtract(dir.scale(.55 + i * .42)),
                                moving.subtract(dir.scale(.15 + i * .34)), .030 - i * .006,
                                alpha * (.8F - i * .18F), i + 9);
                    }
                    ElectricSkillMesh.ring(ctx, matrix, moving, .46, .02, alpha * .6F, t * .7);
                    sparks(ctx, matrix, moving, .55, 7, alpha * .8F, t, .04);
                }
                case "w_orb_burst" -> {
                    double grow = Math.min(1.15, (s.age + partial) / 5.0);
                    double radius = Math.max(.3, s.vec.x) * (.35 + .65 * grow);
                    ElectricSkillMesh.net(ctx, matrix, a, radius, alpha, 0);
                    ElectricSkillMesh.orb(ctx, matrix, a.add(0, .18, 0), radius * .42, alpha * .35F, -t * .4);
                    for (int i = 0; i < 12; i++) {
                        double angle = i * TAU / 12;
                        Vec3 edge = a.add(Math.cos(angle) * radius * 1.25, .06, Math.sin(angle) * radius * 1.25);
                        Vec3 mid = a.add(Math.cos(angle) * radius * .5, .55 + (i % 3) * .18, Math.sin(angle) * radius * .5);
                        glowBolt(ctx, matrix, a.add(Math.cos(angle) * .1, .25, Math.sin(angle) * .1), mid, .03, alpha * .9F, i);
                        glowBolt(ctx, matrix, mid, edge, .024, alpha * .8F, i + 20);
                        ElectricSkillMesh.ring(ctx, matrix, edge, .16, .015, alpha * .5F, t + i);
                    }
                    groundRings(ctx, matrix, a.add(0, .07, 0), radius * 1.6, alpha * .8F, 4, -t);
                    for (int i = 0; i < 5; i++) {
                        glowBolt(ctx, matrix, a, a.add(0, radius * (1.3 + .45 * i), 0), .035 - i * .004,
                                alpha * (.6F - i * .09F), i * 3);
                    }
                    sparks(ctx, matrix, a.add(0, .3, 0), radius * 1.1, 18, alpha * .9F, t + .6, .055);
                }
                case "r_warn" -> {
                    double radius = Math.max(1, s.vec.x);
                    groundRings(ctx, matrix, a.add(0, .07, 0), radius, alpha * .7F, 3, t);
                    cage(ctx, matrix, a, radius, 12, alpha * .45F, t, .9);
                    Vec3 core = a.add(0, .3, 0);
                    ElectricSkillMesh.orb(ctx, matrix, core, .28, alpha * .6F, t);
                    arcs(ctx, matrix, core, .9, 6, alpha * .7F, t);
                }
                case "r_field" -> {
                    double radius = Math.max(1, Math.min(8, s.vec.x));
                    groundRings(ctx, matrix, a.add(0, .07, 0), radius, alpha * .8F, 4, t);
                    cage(ctx, matrix, a, radius, 14, alpha * .8F, t, 1.6);
                    for (int i = 0; i < 10; i++) {
                        double angle = t * .35 + i * TAU / 10;
                        double r = radius * .55;
                        sparks(ctx, matrix, a.add(Math.cos(angle) * r, .35 + Math.sin(t + i) * .35, Math.sin(angle) * r),
                                1.2, 2, alpha * .8F, t + i, .06);
                    }
                    ElectricSkillMesh.orb(ctx, matrix, a.add(0, .45, 0), radius * .16, alpha * .5F, t);
                }
                case "e_start", "e_hit" -> {
                    Vec3 core = a.add(0, .35, 0);
                    boolean hit = s.kind.equals("e_hit");
                    for (int i = 0; i < 8; i++) {
                        double angle = i * TAU / 8 + t * .2;
                        Vec3 edge = core.add(Math.cos(angle) * (hit ? .78 : .48), .1 + (i % 3) * .12,
                                Math.sin(angle) * (hit ? .78 : .48));
                        glowBolt(ctx, matrix, core, edge, .028, alpha, i);
                    }
                    ElectricSkillMesh.orb(ctx, matrix, core, hit ? .34 : .22, alpha * .8F, t);
                    sparks(ctx, matrix, core, hit ? .9 : .6, hit ? 10 : 5, alpha * .85F, t, .05);
                    if (hit) {
                        groundRings(ctx, matrix, a.add(0, .06, 0), .95, alpha * .6F, 2, -t);
                        ElectricSkillMesh.ring(ctx, matrix, core, .62, .024, alpha * .7F, t);
                    }
                }
                case "e_trail" -> {
                    Vec3 dir = s.vec.lengthSqr() < .001 ? new Vec3(0, 0, 1) : s.vec.normalize();
                    Vec3 head = a.add(0, .4, 0);
                    for (int i = 0; i < 4; i++) {
                        Vec3 from = head.subtract(dir.scale(.35 + i * .34));
                        Vec3 to = head.subtract(dir.scale(.12 + i * .30));
                        glowBolt(ctx, matrix, from, to, .032 - i * .005, alpha * (.85F - i * .14F), i * 2);
                    }
                    sparks(ctx, matrix, head, .55, 6, alpha * .8F, t, .038);
                    ElectricSkillMesh.ring(ctx, matrix, head, .34, .018, alpha * .55F, -t);
                }
                case "r_strike" -> {
                    Vec3 end = a.add(s.vec);
                    // Core column plus two staggered shells read as one thick jagged lightning shaft.
                    glowBolt(ctx, matrix, a, end, .16, alpha, 21);
                    glowBolt(ctx, matrix, a.add(.18, .1, -.12), end.add(.10, 0, .08), .075, alpha * .8F, 22);
                    glowBolt(ctx, matrix, a.add(-.16, -.05, .14), end.add(-.08, 0, -.06), .062, alpha * .7F, 23);
                    for (int i = 0; i < 8; i++) {
                        double f = .15 + i * .1;
                        Vec3 from = a.lerp(end, f);
                        Vec3 branch = perpendicular(s.vec).scale((i % 2 == 0 ? 1 : -1) * (.55 + .25 * (i % 3)))
                                .add(0, .25 + .2 * (i % 2), 0);
                        glowBolt(ctx, matrix, from, from.add(branch), .028, alpha * .75F, i + 30);
                    }
                    double radius = com.pkmoba.combat.PikachuCombat.R_RADIUS;
                    double spread = Math.min(1, (s.age + partial) / 7.0);
                    ElectricSkillMesh.orb(ctx, matrix, end, .55 * spread + .1, alpha * .45F, -t);
                    ElectricSkillMesh.orb(ctx, matrix, a, .35, alpha * .5F, t * .8);
                    for (int i = 0; i < 12; i++) {
                        double angle = i * TAU / 12 + t * .1;
                        Vec3 edge = end.add(Math.cos(angle) * radius * spread, .10, Math.sin(angle) * radius * spread);
                        Vec3 rise = end.add(Math.cos(angle) * radius * spread * .72, .9 + (i % 3) * .35,
                                Math.sin(angle) * radius * spread * .72);
                        glowBolt(ctx, matrix, end.add(0, .1, 0), edge, .030, alpha * .85F, i);
                        glowBolt(ctx, matrix, edge, rise, .022, alpha * .55F, i + 40);
                    }
                    groundRings(ctx, matrix, end.add(0, .05, 0), radius * 1.25, alpha * .85F, 4, t);
                    groundRings(ctx, matrix, a.add(0, .05, 0), 1.5 * spread + .3, alpha * .6F, 2, -t);
                    sparks(ctx, matrix, end, radius * .9, 22, alpha * .95F, t + .3, .07);
                    arcs(ctx, matrix, end.add(0, .2, 0), radius * .9, 8, alpha * .8F, t + 1.1);
                }
                default -> { }
            }
        }
    }

    // ---- composition helpers ---------------------------------------------------------------

    /** Jagged bolt drawn twice: a wide translucent shell plus the bright core. */
    private static void glowBolt(WorldRenderContext c, Matrix4f m, Vec3 a, Vec3 b, double radius, float alpha, double seed) {
        ElectricSkillMesh.bolt(c, m, a, b, radius * 1.85, alpha * .32F, seed);
        ElectricSkillMesh.bolt(c, m, a, b, radius, alpha, seed);
    }

    /** Branching arcs around a point, used for charge-up and impact crackle. */
    private static void arcs(WorldRenderContext c, Matrix4f m, Vec3 center, double radius, int count, float alpha, double t) {
        for (int i = 0; i < count; i++) {
            double angle = t * .7 + i * TAU / count;
            Vec3 from = center.add(Math.cos(angle) * radius * .55, Math.sin(angle * 2) * radius * .25,
                    Math.sin(angle) * radius * .55);
            Vec3 to = center.add(Math.cos(angle + .4) * radius * 1.15, Math.sin(angle * 1.7) * radius * .5,
                    Math.sin(angle + .4) * radius * 1.15);
            glowBolt(c, m, from, to, .020, alpha, i * 1.7 + t);
        }
    }

    /** Small bright motes distributed through a spherical shell. */
    private static void sparks(WorldRenderContext c, Matrix4f m, Vec3 center, double radius, int count,
                               float alpha, double t, double size) {
        for (int i = 0; i < count; i++) {
            double theta = i * 2.39996 + t * .6;
            double phi = (i * 1.7) % Math.PI;
            double r = radius * (.45 + (i % 4) / 5.0);
            Vec3 p = center.add(Math.cos(theta) * Math.cos(phi) * r, Math.sin(phi) * r * .8,
                    Math.sin(theta) * Math.cos(phi) * r);
            ElectricSkillMesh.orb(c, m, p, size * (.7 + (i % 3) * .25), alpha, t + i);
        }
    }

    /** Concentric ground shock rings with slight rotation offsets. */
    private static void groundRings(WorldRenderContext c, Matrix4f m, Vec3 center, double radius,
                                    float alpha, int count, double t) {
        for (int i = 0; i < count; i++) {
            double r = radius * (.62 + .16 * i + .04 * Math.sin(t + i));
            ElectricSkillMesh.ring(c, m, center.add(0, i * .04, 0), r, .022 + i * .006,
                    alpha * (1 - i * .16F), t * (.4 + i * .25));
        }
    }

    /** Vertical electric fence: grounded bolts rising all around the area. */
    private static void cage(WorldRenderContext c, Matrix4f m, Vec3 base, double radius, int count,
                             float alpha, double t, double height) {
        for (int i = 0; i < count; i++) {
            double angle = i * TAU / count + t * .05;
            Vec3 foot = base.add(Math.cos(angle) * radius, .05, Math.sin(angle) * radius);
            double h = height * (.75 + (i % 3) * .18);
            Vec3 top = foot.add(0, h, 0);
            glowBolt(c, m, foot, top, .026, alpha, i * 3 + t);
            // Connect neighbouring posts so it reads as a fence, not a picket line.
            double nextAngle = (i + 1) * TAU / count + t * .05;
            Vec3 nextTop = base.add(Math.cos(nextAngle) * radius, h * .92, Math.sin(nextAngle) * radius);
            glowBolt(c, m, top.add(0, -.1, 0), nextTop, .018, alpha * .55F, i * 5.1);
        }
    }

    private static void verticalArcs(WorldRenderContext c, Matrix4f m, Vec3 base, double radius,
                                     float alpha, double t) {
        for (int i = 0; i < 6; i++) {
            double angle = i * TAU / 6 + t * .12;
            Vec3 foot = base.add(Math.cos(angle) * radius, .05, Math.sin(angle) * radius);
            glowBolt(c, m, foot, foot.add(0, 2.4 + (i % 3) * .5, 0), .024, alpha, i * 7 + 1);
        }
    }

    private static Vec3 perpendicular(Vec3 v) {
        Vec3 axis = v.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : v.normalize();
        Vec3 side = axis.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < .01) side = axis.cross(new Vec3(1, 0, 0));
        return side.normalize();
    }

    private static final class Scene {
        final ClientLevel level;
        final String kind;
        Vec3 pos, vec, previousPos, previousVec;
        final int life;
        final double seed = Math.random() * TAU;
        int age, refreshedAge;
        long updated = System.nanoTime();
        Vec3 facing;
        Scene(ClientLevel level, String kind, Vec3 pos, Vec3 vec, int life) {
            this.level = level;
            this.kind = kind;
            this.pos = pos;
            this.vec = vec;
            this.previousPos = pos;
            this.previousVec = vec;
            this.facing = vec;
            this.life = life;
        }
    }
}
