package com.pkmoba.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * Gardevoir: psychic weight, not sparkles.
 *
 * <p>Q 精神强念 = layered telekinetic rings squeezing inward with orbiting rune shards; W 月亮之力 =
 * a full moon disc that cracks into a crescent before flying; E 瞬间移动 = a clean blink ring with a
 * short star trail; R 预知未来 = a slow clock rune overhead and a star detonation.
 */
public final class GardevoirFx {
    private GardevoirFx() {
    }

    public static void render(FxView v) {
        Fx fx = v.fx;
        float a = v.fade;
        switch (v.skill) {
            case 0 -> { // Psychic: rings collapse inward, runes orbit
                if (v.is("start") || v.is("buff")) {
                    Vec3 c = v.head();
                    fx.glow(c, .55, a * .8f);
                    for (int i = 0; i < 3; i++) {
                        double r = 1.0 - .25 * i - .35 * v.progress;
                        fx.ring(c, Math.max(.2, r), .022, a * (.8f - i * .2f));
                    }
                    fx.orbit(c, .85, 4, .16, v.time, a * .8f, .3);
                } else if (v.in("projectile", "lob")) {
                    fx.ring(v.pos, .5, .026, a * .9f);
                    fx.ring(v.pos, .75, .02, a * .6f);
                    fx.sphere(v.pos, new Vec3(.22, .22, .22), v.time * .4, false, a * .9f);
                    fx.orbit(v.pos, .55, 3, .12, v.time, a * .7f, .5);
                } else if (v.in("impact", "melee", "field")) {
                    fx.ringWave(v.pos, .6 + 1.4 * v.progress, .03, 3, v.time, a);
                    fx.orbit(v.pos, 1.0, 5, .14, v.time, a * .8f, .2);
                    fx.glow(v.pos, .8, a * .8f);
                }
            }
            case 1 -> { // Moonblast: a moon that becomes a crescent
                if (v.is("start") || v.is("buff")) {
                    fx.sheet(v.head().add(0, .6, 0), 1.1, 1.1, v.time * .05, a * .9f);
                    fx.glow(v.head(), .6, a * .8f);
                    fx.orbit(v.head(), .9, 5, .1, v.time, a * .6f, .3);
                } else if (v.in("projectile", "lob")) {
                    fx.sheet(v.pos, 1.0, 1.0, v.time * .08, a);
                    fx.crescent(v.pos, .75, -1.2, 1.7, .22, v.time * .2, a * .85f);
                    fx.flow(v.pos.subtract(v.dir.scale(1.8)), v.pos, .3, v.time, a * .6f);
                } else if (v.in("impact", "melee")) {
                    fx.crescent(v.pos, 1.0 + .4 * v.progress, -1.0, 2.2, .3, v.time * .2, a);
                    fx.ringWave(v.pos, .7 + 1.5 * v.progress, .035, 3, v.time, a * .9f);
                    fx.burst(v.pos, 1.1, 10, .4, v.time, a * .8f);
                }
            }
            case 2 -> { // Teleport: clean blink
                if (v.is("start") || v.is("buff")) {
                    fx.ring(v.owner.add(0, .9, 0), .7, .03, a * .8f);
                } else if (v.in("blink", "impact", "heal")) {
                    fx.ringWave(v.pos.add(0, .5, 0), .6 + 1.2 * v.progress, .03, 2, v.time, a);
                    fx.starBurst(v.pos.add(0, 1.0, 0), 1.1, 8, v.time, a * .8f);
                }
            }
            default -> { // Future Sight: clock rune overhead, then a star falls
                if (v.is("start") || v.is("buff") || v.is("field")) {
                    Vec3 c = v.owner.add(0, 2.2, 0);
                    fx.sheet(c, 1.3, 1.3, v.time * .03, a * .85f);
                    fx.ring(c, .9, .025, a * .6f);
                    fx.motes(v.owner.add(0, 1.0, 0), .8, 6, .14, v.time, a * .6f);
                } else if (v.is("impact") || v.is("release")) {
                    fx.ringWave(v.pos, .8 + 1.6 * v.progress, .035, 3, v.time, a);
                    fx.starBurst(v.pos.add(0, .6, 0), 1.4 + v.progress, 10, v.time, a);
                    fx.beam(v.pos.add(0, 5, 0), v.pos.add(0, .4, 0), .45, a * .55f);
                }
            }
        }
    }
}
