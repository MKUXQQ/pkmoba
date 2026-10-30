package com.pkmoba.client;

import com.cobblemon.mod.common.entity.PosableEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.client.fx.Fx;
import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 0.93: impact flash + body animation for the left-click punch.
 *
 * <p>The server only sends one cue per punch ({@code pkmoba:bap/<ownerId>}); everything else (the
 * punch sheet, the star burst, the body clip) is cosmetic and stays on the client.
 */
public final class BasicAttackFx {
    private BasicAttackFx() {
    }

    private static final ResourceLocation LU_PUNCH =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/lu_punch.png");
    private static final ResourceLocation LU_STAR =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/lu_star.png");
    private static final ResourceLocation LU_BOLT =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/lu_bolt.png");
    private static final ResourceLocation BZ_SLASH =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/bz5_slash.png");
    private static final ResourceLocation BZ_STAR =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/bz5_star.png");
    private static final ResourceLocation BZ_FLAME =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/bz5_pillar.png");
    private static final ResourceLocation CE_L =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/ce_flare_l.png");
    private static final ResourceLocation CE_R =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/ce_flare_r.png");
    private static final ResourceLocation CE_CROSS =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/ce_cross.png");
    private static final ResourceLocation CE_EMBER =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/ce_ember.png");
    private static final long LIFE_MS = 340L;
    private static final Deque<Hit> HITS = new ArrayDeque<>();
    private static boolean ready;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("pkmoba/bap");

    private record Hit(Vec3 at, Vec3 dir, String species, long start) {
    }

    public static void init() {
        if (ready) return;
        ready = true;
        WorldRenderEvents.AFTER_ENTITIES.register(BasicAttackFx::render);
    }

    public static boolean accept(EffectPayload payload) {
        String id = payload.effectId();
        if (id == null || !id.startsWith("pkmoba:bap/")) return false;
        String rest = id.substring("pkmoba:bap/".length());
        int slash = rest.lastIndexOf('/');
        if (slash <= 0) return true;
        String species = rest.substring(0, slash);
        HITS.addLast(new Hit(new Vec3(payload.x(), payload.y(), payload.z()),
                new Vec3(payload.dx(), payload.dy(), payload.dz()), species, System.currentTimeMillis()));
        while (HITS.size() > 24) HITS.removeFirst();
        int ownerId;
        try {
            ownerId = Integer.parseInt(rest.substring(slash + 1));
        } catch (NumberFormatException ignored) {
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        int stage = stageOf(species);
        // 0.135 Armarouge fires its bolts from alternating arms, the third from both cannons.
        boolean arm = species != null && species.startsWith("armarouge");
        String clip = arm
                ? switch (stage) {
                    case 2 -> "q_fire_r";
                    case 3 -> "q_fire_heavy";
                    default -> "q_fire_l";
                }
                : switch (stage) {
                    case 1 -> "q_swing_l";
                    case 2 -> "q_swing_r";
                    case 3 -> "q_cross";
                    default -> "w_melee";
                };
        String model = stage > 0 ? (arm ? "armarouge" : "ceruledge") : species;
        if (mc.level != null && mc.level.getEntity(ownerId) instanceof PokemonEntity pokemon) {
            try {
                ((PosableEntity) pokemon).playAnimation(
                        "q.bedrock_primary('pkmoba_" + model + "', '" + clip + "', q.curve('one'))",
                        java.util.List.of());
            } catch (RuntimeException error) {
                // a missing clip must never break the attack feedback
                LOG.warn("bap clip {}/{} failed: {}", model, clip, error.toString());
            }
        }
        return true;
    }

    /**
     * Ceruledge and Armarouge ship their chains as <species><stage> so the client knows the beat;
     * every other species uses the generic single-punch feedback.
     */
    private static int stageOf(String species) {
        if (species == null) return 0;
        boolean known = species.startsWith("ceruledge") || species.startsWith("armarouge");
        if (!known || species.length() <= 9) return 0;
        char digit = species.charAt(species.length() - 1);
        return digit >= '1' && digit <= '3' ? digit - '0' : 0;
    }

    private static void render(WorldRenderContext context) {
        if (HITS.isEmpty() || context.matrixStack() == null || context.consumers() == null) return;
        long now = System.currentTimeMillis();
        while (!HITS.isEmpty() && now - HITS.peekFirst().start() > LIFE_MS) HITS.removeFirst();
        if (HITS.isEmpty()) return;
        for (Hit hit : HITS) {
            int stage = stageOf(hit.species());
            if (stage > 0) {
                renderChain(context, hit, stage, now);
                continue;
            }
            boolean fire = "blaziken".equals(hit.species());
            Fx punch = new Fx(context, fire ? BZ_SLASH : LU_PUNCH);
            Fx star = new Fx(context, fire ? BZ_STAR : LU_STAR);
            Fx bolt = new Fx(context, fire ? BZ_FLAME : LU_BOLT);
            double age = (now - hit.start()) / (double) LIFE_MS;
            float alpha = (float) Math.max(0, 1 - age);
            Vec3 dir = hit.dir().lengthSqr() > 1E-5 ? hit.dir().normalize() : new Vec3(0, 0, 1);
            double heading = Math.toDegrees(Math.atan2(dir.z, dir.x));
            punch.sheet(hit.at(), 1.15 + age * .55, 1.15 + age * .55, heading, alpha);
            star.starBurst(hit.at(), .7 + age * .9, 8, now / 1000.0, alpha * .8f);
            bolt.sheet(hit.at().add(0, .35, 0), .6, .95, heading + 20, alpha * .7f);
            bolt.sheet(hit.at().add(0, -.3, 0), .5, .8, heading - 25, alpha * .55f);
        }
    }

    /** Left blade, right blade, then the crossed heavy swing: three distinct silhouettes. */
    private static void renderChain(WorldRenderContext context, Hit hit, int stage, long now) {
        double age = (now - hit.start()) / (double) LIFE_MS;
        float alpha = (float) Math.max(0, 1 - age);
        Vec3 dir = hit.dir().lengthSqr() > 1E-5 ? hit.dir().normalize() : new Vec3(0, 0, 1);
        double heading = Math.toDegrees(Math.atan2(dir.z, dir.x));
        boolean heavy = stage == 3;
        Fx blade = new Fx(context, stage == 1 ? CE_L : stage == 2 ? CE_R : CE_CROSS);
        Fx ember = new Fx(context, CE_EMBER);
        double size = (heavy ? 1.75 : 1.25) + age * (heavy ? .8 : .5);
        blade.sheet(hit.at(), size, size, heavy ? heading + 45 : heading + (stage == 1 ? -22 : 22), alpha);
        ember.starBurst(hit.at(), (heavy ? 1.8 : .95) + age * .7, heavy ? 9 : 7, now / 1000.0, alpha * .75f);
        if (heavy) {
            blade.sheet(hit.at(), size * .7, size * .7, heading - 35, alpha * .55f);
            ember.sheet(hit.at(), .9, .9, heading + age * 120, alpha * .6f);
        }
    }
}
