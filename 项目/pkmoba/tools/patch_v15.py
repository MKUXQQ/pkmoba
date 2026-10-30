"""0.82: Blaziken pass 2 - lift-then-charge Flame Charge, big fire wall, more pixel fire.

* R 闪焰冲锋 now rises straight up, then charges in a straight line onto the clicked point;
  no flip/other animation is played.
* E fire wall is much longer (7.2 blocks) and taller (3.2), with tongues of flame on top and
  embers rising off the base.
* More fire everywhere: bigger plumes for Q/W, ember particles back up a notch.

Run from the project root: python tools/patch_v15.py
"""
from pathlib import Path

COMBAT = Path("src/main/java/com/pkmoba/combat/ExpansionCombat.java")
EFFECTS = Path("src/main/java/com/pkmoba/client/ExpansionEffects.java")
FX = Path("src/main/java/com/pkmoba/client/fx/BlazikenFx.java")

R_CASE = '''            default -> { // 闪焰冲锋: rise straight up, then charge onto the clicked point.
                final int windup = 5;
                final int hover = 5;
                final int dash = 9;
                if (t == 1) {
                    s.origin = e.position();
                    e.setDeltaMovement(Vec3.ZERO);
                    cue(e, s, "attack", e.position(), s.direction);
                }
                if (t <= windup + hover) {
                    // Levitate: no animation, the body simply rises off the ground.
                    double f = Math.min(1, (t - 1) / (double) windup);
                    Vec3 up = s.origin.add(0, 1.8 * f, 0);
                    e.teleportTo(up.x, up.y, up.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    e.resetFallDistance();
                    if (t <= windup) cue(e, s, "rise", up, s.direction);
                } else if (t <= windup + hover + dash) {
                    // Straight horizontal charge from the hover point to the clicked point.
                    double f = (t - windup - hover) / (double) dash;
                    Vec3 from = s.origin.add(0, 1.8, 0);
                    Vec3 wanted = new Vec3(from.x + (s.point.x - from.x) * f,
                            from.y + (s.point.y - from.y) * f,
                            from.z + (s.point.z - from.z) * f);
                    e.teleportTo(wanted.x, wanted.y, wanted.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    e.resetFallDistance();
                    cue(e, s, "charge", wanted, s.direction);
                } else if (t == windup + hover + dash + 1 && !s.stopped) {
                    s.stopped = true;
                    e.teleportTo(s.point.x, s.point.y, s.point.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    cue(e, s, "impact", s.point, s.direction);
                    boolean branded = false;
                    for (LivingEntity target : around(l, e, s.point, m.radius())) {
                        float damage = m.damage();
                        if (takeBlazeMark(target)) {
                            damage = damage * 1.6F + 20F;
                            branded = true;
                        }
                        hit(l, e, s, target, damage, 1);
                    }
                    if (branded) {
                        cue(e, s, "detonate", s.point, s.direction);
                        for (LivingEntity target : around(l, e, s.point, m.radius() * 1.5))
                            hit(l, e, s, target, 12, 1);
                    }
                    finish(e, s);
                }
            }
'''

WALL = '''    private static void blazikenFireWall(WorldRenderContext c, Scene s, double age, float fade) {
        if (c.matrixStack() == null || c.consumers() == null) return;
        Vec3 dir = s.dir.lengthSqr() > 1E-5 ? s.dir.normalize() : new Vec3(0, 0, 1);
        double rise = Math.min(1.0, age / 3.0);
        double height = 3.2 * rise;
        if (height < .05) return;
        Vec3 up = new Vec3(0, 1, 0);
        final double half = 3.6;
        Vec3 a = s.pos.subtract(dir.scale(half));
        Vec3 e = s.pos.add(dir.scale(half));
        Vec3 n = dir.cross(up);
        n = n.lengthSqr() > 1E-6 ? n.normalize().scale(.05) : new Vec3(0, 0, .05);
        ResourceLocation wall = ResourceLocation.fromNamespaceAndPath("pkmoba",
                "textures/effect/blaziken_wall.png");
        ResourceLocation flame = ResourceLocation.fromNamespaceAndPath("pkmoba",
                "textures/effect/blaziken_flame.png");
        float alpha = Math.min(1f, fade);
        for (int side = -1; side <= 1; side += 2) {
            Vec3 off = n.scale(side);
            ExpansionSkillMeshes.raw(c, wall,
                    a.add(off), a.add(off).add(up.scale(height)),
                    e.add(off).add(up.scale(height)), e.add(off),
                    0f, (float) (1.0 - rise), 2.4f, 1f, alpha);
        }
        // Tongues of flame licking along the top edge, so the wall looks like fire, not a decal.
        for (int i = 0; i <= 5; i++) {
            Vec3 base = a.add(dir.scale(half * 2.0 * i / 5)).add(0, height * .92, 0);
            double flick = .85 + .25 * Math.sin(age * .9 + i * 1.7);
            ExpansionSkillMeshes.raw(c, flame, base, base.add(0, 1.5 * flick, 0),
                    base.add(dir.scale(1.5)).add(0, 1.5 * flick, 0), base.add(dir.scale(1.5)),
                    0f, 0f, 1f, 1f, alpha);
        }
        ExpansionSkillMeshes.raw(c, flame, a, a.add(up.scale(.3)), e.add(up.scale(.3)), e,
                0f, .55f, 1f, 1f, alpha * .9f);
        // Embers lifting off the base: cheap pixel fire that keeps the wall alive.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            java.util.Random rnd = new java.util.Random((long) (s.pos.x * 977 + s.pos.z * 131));
            if (rnd.nextFloat() < .55f) {
                for (int i = 0; i < 3; i++) {
                    double along = (rnd.nextDouble() - .5) * half * 2;
                    Vec3 p = s.pos.add(dir.scale(along)).add(0, .25 + rnd.nextDouble() * .5, 0);
                    mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME,
                            p.x, p.y, p.z, 0, .05 + rnd.nextDouble() * .05, 0);
                }
            }
        }
    }
'''

FLARE = '''    private static void flareBlitz(FxView v, Fx plume, Fx band, Fx star, float a) {
        if (v.is("rise") || v.is("attack") || v.is("warn")) {
            // The body simply lifts off: fire pours out under the feet and climbs with it.
            Vec3 feet = v.feet().add(0, .1, 0);
            plume(plume, feet, 1.0 + .8 * v.progress, v.time, a, 0);
            plume(plume, feet.add(0, .5, 0), .8 + .7 * v.progress, v.time + .4, a * .9f, 1);
            for (int i = 0; i < 3; i++) {
                double angle = i * 2.1 + v.time;
                Vec3 at = v.pos.add(Math.cos(angle) * .7, .1, Math.sin(angle) * .7);
                plume(plume, at, .7, v.time + i, a * .8f, i + 2);
            }
            band.beam(feet, feet.add(0, 2.4, 0), .5, a * .5f);
        } else if (v.is("charge")) {
            // Straight-line charge: a long fire trail streams out behind the body.
            Vec3 body = v.pos.add(0, .5, 0);
            plume(plume, body, 1.25, v.time, a, 1);
            for (int i = 1; i <= 5; i++) {
                Vec3 back = body.subtract(v.dir.scale(.55 * i));
                plume(plume, back, .95 - .13 * i, v.time + i * .18, a * (.9f - .14f * i), i + 2);
            }
            band.beam(body.subtract(v.dir.scale(3.0)), body, .55, a * .55f);
        } else if (v.in("impact", "detonate")) {
            float burst = (float) Math.min(1.0, v.progress * 1.6);
            Vec3 core = v.pos.add(0, .45, 0);
            plume(plume, core, 1.8 + .9 * burst, v.time, a, 5);
            plume(plume, core, 1.2 + .6 * burst, v.time + .5, a * .9f, 6);
            for (int i = 0; i < 9; i++) {
                double angle = i * Math.PI * 2 / 9 + .3;
                double dist = .7 + 1.8 * burst;
                plume(plume, v.pos.add(Math.cos(angle) * dist, .1, Math.sin(angle) * dist),
                        .9 + .35 * burst, v.time + i, a * .9f, i + 7);
            }
            star.starBurst(core, 1.2 + 1.1 * burst, 10, v.time, a * .95f);
        }
    }
'''


def patch_combat() -> None:
    text = COMBAT.read_text(encoding="utf-8")
    start = text.index("            default -> { // 闪焰冲锋: leap through the air")
    end = text.index("        }\n    }\n", start) + len("        }\n    }\n")
    text = text[:start] + R_CASE + text[end:]
    text = text.replace('                    if (t == windup + 1) cue(e, s, "leap", wanted, s.direction);\n', "")
    COMBAT.write_text(text, encoding="utf-8")
    print("patched R (rise then straight charge)")


def patch_effects() -> None:
    text = EFFECTS.read_text(encoding="utf-8")
    start = text.index("    private static void blazikenFireWall(")
    end = text.index("    public static boolean accept(EffectPayload payload) {")
    text = text[:start] + WALL + "\n" + text[end:]
    # bigger wall life; drop the flip hook now that R plays no animation
    text = text.replace('                case "trail" -> 26;', '                case "trail" -> 34;')
    text = text.replace('''            if (phase.equals("leap") && species.id().equals("blaziken") && skill == 3) {
                animate(entity, species.id(), skill, "_cast");
            }
''', "")
    # keep the body pose still during R: skip all clip beats for that move
    text = text.replace('''                if (species.id().equals("blaziken") && skill == 3) {
                    schedule(entity, species.id(), "r_sustain", 6);
                    schedule(entity, species.id(), "r_sustain", 14);
                }
''', "")
    # embers back up a little
    text = text.replace('if (rnd.nextFloat() > (FLAME_EMBERS ? .26f : .62f)) return;',
                        'if (rnd.nextFloat() > (FLAME_EMBERS ? .40f : .62f)) return;')
    text = text.replace('int n = FLAME_EMBERS ? 1 + (int) Math.min(2, radius * .7)',
                        'int n = FLAME_EMBERS ? 2 + (int) Math.min(3, radius * .9)')
    EFFECTS.write_text(text, encoding="utf-8")
    print("patched wall + embers")


def patch_fx() -> None:
    text = FX.read_text(encoding="utf-8")
    start = text.index("    private static void flareBlitz(")
    end = text.index("\n}\n", start)
    FX.write_text(text[:start] + FLARE + text[end:], encoding="utf-8")
    print("patched BlazikenFx.flareBlitz")


if __name__ == "__main__":
    patch_combat()
    patch_effects()
    patch_fx()
