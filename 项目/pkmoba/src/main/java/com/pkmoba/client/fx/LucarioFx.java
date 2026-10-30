package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * Lucario - steel-blue aura fighter. 0.91 draws every move from pixel sheets only: the 3D sphere
 * and crystal meshes used for Meteor Mash / Thunder Punch read as smooth toys next to the pixel
 * roster, so each beat is now a single chunky silhouette.
 */
public final class LucarioFx {
    private LucarioFx() {
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/" + name + ".png");
    }

    private static final ResourceLocation SWORD = tex("lu_sword");
    private static final ResourceLocation COMET = tex("lu_comet");
    private static final ResourceLocation BOLT = tex("lu_bolt");
    private static final ResourceLocation PUNCH = tex("lu_punch");
    private static final ResourceLocation STAR = tex("lu_star");
    private static final ResourceLocation STREAK = tex("lu_streak");
    private static final ResourceLocation GLOW = tex("lu_glow");

    public static void render(FxView v) {
        var c = v.fx.context();
        float a = Math.min(1f, v.fade);
        Fx sword = new Fx(c, SWORD);
        Fx comet = new Fx(c, COMET);
        Fx bolt = new Fx(c, BOLT);
        Fx punch = new Fx(c, PUNCH);
        Fx star = new Fx(c, STAR);
        Fx streak = new Fx(c, STREAK);
        Fx glow = new Fx(c, GLOW);
        if (v.phase.startsWith("aura")) return;
        switch (v.skill) {
            case 0 -> swordsDance(v, sword, star, glow, streak, a);
            case 1 -> meteorMash(v, comet, punch, star, streak, glow, a);
            case 2 -> thunderPunch(v, punch, bolt, star, glow, a);
            default -> extremeSpeed(v, sword, streak, glow, star, a);
        }
    }

    /** Q 剑舞: five blades stand upright in the world and bob gently. */
    private static void swordsDance(FxView v, Fx sword, Fx star, Fx glow, Fx streak, float a) {
        Vec3 base = v.pos;
        Vec3 centre = base.add(0, 1.25, 0);
        glow.sheet(centre, 1.9, 1.6, 0, a * .16f);
        for (int i = 0; i < 4; i++) {
            double angle = v.time * .45 + i * (Math.PI * 2 / 4);
            double bob = .12 * Math.sin(v.time * .5 + i * 1.3);
            Vec3 at = base.add(Math.cos(angle) * .82, 1.0 + bob, Math.sin(angle) * .82);
            double yaw = Math.toDegrees(angle) + 90;
            // the blade is 1:2 and stands on the Y axis: no camera-dependent tilt
            sword.upright(at, .44, 1.3, yaw, a * .6f);
            sword.upright(at, .26, .85, yaw, a * .88f);
        }
        streak.groundRing(base, 1.25, 2, v.time, a * .34f);
        if (v.in("start", "release", "impact")) {
            star.starBurst(centre, 1.2, 8, v.time, a * .45f);
            sword.upright(centre, .62, 1.9, 30, a * .7f);
        }
    }

    /** W 彗星拳: a comet sheet with its wake lags behind, then a star burst on contact. */
    private static void meteorMash(FxView v, Fx comet, Fx punch, Fx star, Fx streak, Fx glow, float a) {
        Vec3 fwd = v.dir.lengthSqr() > 1E-5 ? v.dir.normalize() : new Vec3(0, 0, 1);
        Vec3 chest = v.chest();
        double heading = Math.toDegrees(Math.atan2(fwd.z, fwd.x));
        if (v.in("start", "attack", "windup")) {
            Vec3 hand = chest.add(fwd.scale(.5));
            glow.sheet(hand, .9, .9, 0, a * .6f);
            punch.sheet(hand, .95, .95, heading, a * .95f);
        } else if (v.in("meteor", "release")) {
            Vec3 head = chest.add(fwd.scale(1.9));
            glow.sheet(head, 2.0, 1.6, heading, a * .45f);
            comet.sheet(head, 1.5, 1.5, heading, a);
            for (int i = 1; i <= 4; i++) {
                Vec3 at = head.subtract(fwd.scale(i * .55)).add(0, (i % 2 == 0 ? .14 : -.1), 0);
                comet.sheet(at, .85 - i * .12, .85 - i * .12, heading, a * (0.5f - i * 0.08f));
            }
        } else if (v.in("impact", "melee", "detonate")) {
            double burst = Math.min(1.0, v.progress * 1.8);
            Vec3 at = v.pos;
            star.starBurst(at, 1.2 + 1.1 * burst, 10, v.time, a * .85f);
            glow.sheet(at, 1.6 + 1.6 * burst, 1.6 + 1.6 * burst, 0, a * .45f);
            streak.groundRing(at.subtract(0, .5, 0), 1.1 + 1.6 * burst, 2, v.time, a * .7f);
            comet.sheet(at, 1.1, 1.1, heading, a * .55f);
        }
    }

    /** E 雷电拳: fist sheet at the knuckle with bolt sheets fanning out of the impact. */
    private static void thunderPunch(FxView v, Fx punch, Fx bolt, Fx star, Fx glow, float a) {
        Vec3 fwd = v.dir.lengthSqr() > 1E-5 ? v.dir.normalize() : new Vec3(0, 0, 1);
        Vec3 chest = v.chest();
        double heading = Math.toDegrees(Math.atan2(fwd.z, fwd.x));
        if (v.in("start", "attack")) {
            Vec3 strike = chest.add(fwd.scale(1.05));
            glow.sheet(strike, 1.1, 1.1, 0, a * .55f);
            punch.sheet(strike, 1.15, 1.15, heading, a * .95f);
            bolt.sheet(strike.add(0, .55, 0), .8, 1.3, 25 + v.time * 90, a * .6f);
        } else if (v.is("bolt")) {
            Vec3 strike = chest.add(fwd.scale(2.1));
            punch.sheet(strike, 1.45, 1.45, heading, a);
            glow.sheet(strike, 2.1, 1.9, 0, a * .5f);
            bolt.sheet(strike, 1.8, 2.0, v.time * 120, a * .9f);
            bolt.sheet(strike.add(fwd.scale(-.3)), 1.3, 1.6, -v.time * 100, a * .7f);
            star.starBurst(strike, 1.2, 6, v.time, a * .7f);
        } else if (v.in("impact", "melee", "detonate")) {
            double burst = Math.min(1.0, v.progress * 1.9);
            star.starBurst(v.pos, 1.0 + 1.0 * burst, 8, v.time, a * .9f);
            glow.sheet(v.pos, 1.8 + 1.8 * burst, 1.8 + 1.8 * burst, 0, a * .5f);
            for (int i = 0; i < 4; i++) {
                double angle = i * 90 + v.time * 60;
                bolt.sheet(v.pos.add(Math.cos(Math.toRadians(angle)) * .7, .1,
                        Math.sin(Math.toRadians(angle)) * .7), .7, 1.1, angle, a * .7f);
            }
        }
    }

    /** R 神速: three speed sheets behind the dash plus a clean arrival flash. */
    private static void extremeSpeed(FxView v, Fx sword, Fx streak, Fx glow, Fx star, float a) {
        Vec3 body = v.pos.add(0, .55, 0);
        Vec3 fwd = v.dir.lengthSqr() > 1E-5 ? v.dir.normalize() : new Vec3(0, 0, 1);
        double heading = Math.toDegrees(Math.atan2(fwd.z, fwd.x));
        if (v.in("start", "dash", "trail")) {
            // long speed sheets plus three shrinking afterimages give the dash a clear direction
            streak.sheet(body, 3.0, 1.2, heading, a * .9f);
            streak.sheet(body.subtract(fwd.scale(1.2)).add(0, -.08, 0), 2.3, .95, heading, a * .6f);
            streak.sheet(body.subtract(fwd.scale(2.3)).add(0, -.16, 0), 1.7, .75, heading, a * .4f);
            for (int i = 1; i <= 3; i++) {
                Vec3 ghost = body.subtract(fwd.scale(i * .75));
                glow.sheet(ghost, 1.35 - i * .22, 1.55 - i * .22, 0, a * (0.28f - i * 0.06f));
                sword.sheet(ghost, .55, .8, -45, a * (0.22f - i * 0.05f));
            }
            streak.groundRing(v.pos, 1.1, 2, v.time, a * .5f);
        }
        if (v.in("arrive", "impact", "melee", "detonate")) {
            star.starBurst(v.pos.add(0, .6, 0), 1.3, 10, v.time, a * .9f);
            glow.sheet(v.pos.add(0, .6, 0), 1.7, 1.4, 0, a * .55f);
            glow.sheet(v.pos.add(0, 1.3, 0), .55, 2.1, 0, a * .5f);
            streak.sheet(v.pos.add(0, .7, 0), 2.2, 1.0, heading, a * .45f);
            streak.groundRing(v.pos, 1.5, 3, v.time, a * .65f);
        }
    }
}
