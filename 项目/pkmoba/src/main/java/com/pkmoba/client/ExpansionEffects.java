package com.pkmoba.client;

import com.cobblemon.mod.common.entity.PosableEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pkmoba.combat.ExpansionRoster;
import com.pkmoba.combat.ExpansionWalls;
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
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded server-cued world scenes for the eight expansion species.
 *
 * <p>The server only sends {@code pkmoba:expansion/<species>/<skill>/<phase>/<entityId>} plus a point
 * and a direction; every visual decision is client-side cosmetics. This renderer therefore:
 * <ul>
 *   <li>anchors projectiles, beams and fans to real hand/mouth/foot positions
 *       ({@link ExpansionAnchors}, live Cobblemon locators with body-relative fallback);</li>
 *   <li>gives each of the 32 skills its own {@link Style} (bolt, lob, fan, rush, dig, drain, stomp,
 *       rockfall, wall, ice storm, sandstorm, spikes, blades, blink, mark) instead of one generic
 *       shape pass;</li>
 *   <li>plays a layered local sound per stage (charge / cast / impact) that never loops per frame;</li>
 *   <li>degrades safely: unknown phases or missing models fall back to a generic burst, nothing
 *       here can throw into the render loop or the network receiver.</li>
 * </ul>
 * No damage, aim or hit prediction happens here; the server stays authoritative.
 */
public final class ExpansionEffects {

    private static final Logger LOGGER = LoggerFactory.getLogger("pkmoba");
    private static final String PREFIX = "pkmoba:expansion/";

    /** Hard cap so a broken server cannot grow this without bound. */
    private static final int MAX_SCENES = 160;
    /** Trail samples kept for projectile/lob history (per scene). */
    private static final int MAX_TRAIL = 24;
    /** Render cull and audio distance (blocks). */
    private static final double CULL_SQR = 128.0 * 128.0;
    private static final double AUDIO_SQR = 96.0 * 96.0;

    private static final Map<String, Scene> SCENES = new LinkedHashMap<>();
    private static ClientLevel world;
    private static long spikeSeq;

    private ExpansionEffects() {
    }

    // ---------------------------------------------------------------------------------------------
    // contract
    // ---------------------------------------------------------------------------------------------

    /** Which silhouette a skill draws. One per skill; shared geometry is not shared skills. */
    private enum Style {
        AURA, BOLT, LOB, FAN, RUSH, DIG, DRAIN, STOMP, ROCKFALL, WALL,
        ICE_STORM, SAND_STORM, SPIKES, BLADES, BLINK, MARK
    }

    /** Damage flavour, only used to pick the layered sound set. */
    private enum Element { FIGHTING, ICE, FIRE, GHOST, ROCK, DRAGON, STEEL, PSYCHIC }

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(ExpansionEffects::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != world) {
                clear();
                world = mc.level;
            }
            if (mc.level != null) {
                for (Scene s : SCENES.values()) {
                    // Follower zones (tyranitar E sandstorm) travel with the caster: keep the stored
                    // point in sync so distance culling measures the zone, not the original cast.
                    if (s.phase.equals("field") && s.style != Style.ICE_STORM) {
                        Entity entity = mc.level.getEntity(s.owner);
                        if (entity != null) {
                            s.pos = entity.position();
                        }
                    }
                }
            }
            SCENES.values().removeIf(s -> ++s.age > s.life
                    || mc.level == null
                    || mc.level.getEntity(s.owner) == null);
        });
    }

    private static void clear() {
        SCENES.clear();
        world = null;
        // Client-only wall mirrors must never survive a disconnect or a dimension change.
        ExpansionWalls.clearClient();
    }

    /**
     * Consume an expansion cue. Returns false for every other payload so the receiver chain keeps
     * working, true once this family owns the id (even when the single cue was invalid).
     */
    public static boolean accept(EffectPayload p) {
        if (!p.effectId().startsWith(PREFIX)) {
            return false;
        }
        String[] parts = p.effectId().split("/");
        if (parts.length != 5) {
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return true;
        }
        if (world != mc.level) {
            clear();
            world = mc.level;
        }
        try {
            int owner = Integer.parseInt(parts[4]);
            String phase = parts[3];
            Vec3 pos = new Vec3(p.x(), p.y(), p.z());
            Vec3 dir = new Vec3(p.dx(), p.dy(), p.dz());
            if (!finite(pos) || !finite(dir)) {
                return true;
            }
            // clear can arrive with a placeholder skill (<=0) before the species is resolvable,
            // so it must be honoured before any roster/skill validation.
            if (phase.equals("clear")) {
                dropOwner(mc, owner);
                return true;
            }
            var species = ExpansionRoster.find(parts[1]);
            int skill = Integer.parseInt(parts[2]);
            if (species == null || skill < 0 || skill > 3) {
                return true;
            }
            var move = species.move(skill);
            Style style = styleFor(species.id(), skill);
            Entity entity = mc.level.getEntity(owner);
            switch (phase) {
                case "end" -> retire(owner, skill);
                case "start" -> {
                    add(new Scene(species, skill, style, "start", owner, pos, dir, Math.max(2, move.windup())));
                    animate(entity, species, skill, "_windup");
                    playStage(species, skill, "start", anchored(entity, style), 1.0F);
                }
                case "release" -> {
                    add(new Scene(species, skill, style, "release", owner, pos, dir, Math.max(6, move.duration())));
                    animate(entity, species, skill, sustained(style) ? "_sustain" : "_cast");
                    playStage(species, skill, "release", anchored(entity, style), 1.0F);
                }
                case "projectile", "lob" -> {
                    // per-tick world position: keep one scene per (owner, skill, phase) and extend
                    // its trail instead of spawning a scene per packet.
                    Scene scene = SCENES.get(owner + ":" + skill + ":" + phase);
                    if (scene != null && scene.style == style) {
                        scene.pos = pos;
                        scene.dir = dir;
                        scene.age = 0;
                        scene.push(pos);
                    } else {
                        scene = new Scene(species, skill, style, phase, owner, pos, dir, 3);
                        scene.push(pos);
                        add(scene);
                    }
                    if (phase.equals("lob")) {
                        playStage(species, skill, "release", pos, 0.8F);
                    }
                }
                case "impact" -> {
                    add(new Scene(species, skill, style, "impact", owner, pos, dir, 14));
                    playStage(species, skill, "impact", pos, 1.0F);
                }
                case "spike" -> {
                    // several spikes arrive over a few ticks; distinct keys keep them all visible.
                    String key = owner + ":" + skill + ":spike:" + (spikeSeq++);
                    SCENES.put(key, new Scene(species, skill, style, "spike", owner, pos, dir, 16));
                }
                case "heal" -> {
                    // p = victim, d = caster: the return beam runs from p to d.
                    add(new Scene(species, skill, style, "heal", owner, pos, dir, 16));
                    playStage(species, skill, "impact", pos, 0.8F);
                }
                case "blink" -> {
                    add(new Scene(species, skill, style, "blink", owner, pos, dir, 14));
                    playStage(species, skill, "release", pos, 0.9F);
                }
                case "wall" -> {
                    // Only cued when the server really created the slab: mirror it client-side so
                    // prediction and collision preview agree, and draw the wall from this scene only.
                    if (entity instanceof PokemonEntity pokemon) {
                        ExpansionWalls.create(pokemon, pos, dir, move.radius(), move.duration());
                    }
                    add(new Scene(species, skill, style, "wall", owner, pos, dir, Math.max(40, move.duration())));
                    playStage(species, skill, "release", pos, 0.8F);
                }
                case "field" -> {
                    add(new Scene(species, skill, style, "field", owner, pos, dir, Math.max(20, move.duration())));
                    if (entity != null) {
                        animate(entity, species, skill, "_sustain");
                    }
                }
                case "buff" -> {
                    add(new Scene(species, skill, style, "buff", owner, pos, dir, Math.max(20, move.duration())));
                    if (entity != null) {
                        animate(entity, species, skill, "_sustain");
                    }
                }
                case "followup" -> {
                    add(new Scene(species, skill, style, "followup", owner, pos, dir, 10));
                    playStage(species, skill, "impact", pos, 0.7F);
                }
                default -> {
                    // Unknown phase from a newer server: keep the generic silhouette instead of
                    // dropping the cue silently.
                    add(new Scene(species, skill, style, "release", owner, pos, dir, Math.max(6, move.duration())));
                }
            }
        } catch (RuntimeException failure) {
            LOGGER.debug("Invalid expansion scene", failure);
        }
        return true;
    }

    private static void add(Scene scene) {
        SCENES.put(scene.owner + ":" + scene.skill + ":" + scene.phase, scene);
        while (SCENES.size() > MAX_SCENES) {
            SCENES.remove(SCENES.keySet().iterator().next());
        }
    }

    private static void dropOwner(Minecraft mc, int owner) {
        Entity entity = mc.level == null ? null : mc.level.getEntity(owner);
        if (entity != null) {
            // Clear the client-only wall mirror of that caster (walls of other casters stay).
            ExpansionWalls.remove(entity.getUUID());
        }
        SCENES.entrySet().removeIf(entry -> entry.getValue().owner == owner);
    }

    /** end: the skill finished; retire the transient poses but keep persistent zones and buffs. */
    private static void retire(int owner, int skill) {
        SCENES.remove(owner + ":" + skill + ":start");
        SCENES.remove(owner + ":" + skill + ":release");
        // Detached zones (tyranitar E sandstorm, abomasnow R snowstorm) and the swords-dance buff are
        // cued once and the server calls finish() immediately afterwards, so the "end" that follows
        // must not erase them: they keep damaging on their own timer and expire by scene life only.
        SCENES.remove(owner + ":" + skill + ":heal");
        SCENES.remove(owner + ":" + skill + ":blink");
        SCENES.remove(owner + ":" + skill + ":followup");
    }

    private static Style styleFor(String species, int skill) {
        return switch (species) {
            case "lucario" -> skill == 0 ? Style.BOLT : skill == 2 ? Style.RUSH : Style.FAN;
            case "abomasnow" -> skill == 0 ? Style.BOLT : skill == 1 ? Style.FAN : skill == 2 ? Style.WALL : Style.ICE_STORM;
            case "blaziken" -> skill < 2 ? Style.FAN : Style.RUSH;
            case "gengar" -> skill == 0 ? Style.BOLT : skill == 1 ? Style.LOB : skill == 2 ? Style.FAN : Style.DRAIN;
            case "tyranitar" -> skill == 0 ? Style.ROCKFALL : skill == 1 ? Style.FAN : skill == 2 ? Style.SAND_STORM : Style.SPIKES;
            case "garchomp" -> skill == 0 ? Style.FAN : skill == 1 ? Style.STOMP : skill == 2 ? Style.DIG : Style.RUSH;
            case "scizor" -> skill == 3 ? Style.BLADES : skill == 1 ? Style.FAN : Style.RUSH;
            case "gardevoir" -> skill == 0 ? Style.BOLT : skill == 1 ? Style.LOB : skill == 2 ? Style.BLINK : Style.MARK;
            default -> Style.AURA;
        };
    }

    private static boolean sustained(Style style) {
        return switch (style) {
            case RUSH, DIG, ICE_STORM, SAND_STORM, WALL, BLADES, LOB -> true;
            default -> false;
        };
    }

    private static Element elementFor(String species) {
        return switch (species) {
            case "abomasnow" -> Element.ICE;
            case "blaziken" -> Element.FIRE;
            case "gengar" -> Element.GHOST;
            case "tyranitar" -> Element.ROCK;
            case "garchomp" -> Element.DRAGON;
            case "scizor" -> Element.STEEL;
            case "gardevoir" -> Element.PSYCHIC;
            default -> Element.FIGHTING;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // rendering
    // ---------------------------------------------------------------------------------------------

    private static void render(WorldRenderContext c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || c.matrixStack() == null || c.consumers() == null) {
            return;
        }
        Vec3 camera = c.camera().getPosition();
        float partial = c.tickCounter().getGameTimeDeltaPartialTick(false);
        Ctx x = new Ctx(c, c.matrixStack().last().pose(), camera,
                mc.level.getGameTime() + partial, partial);
        for (Scene s : SCENES.values()) {
            if (s.pos.distanceToSqr(camera) > CULL_SQR) {
                continue;
            }
            Entity owner = mc.level.getEntity(s.owner);
            if (owner == null) {
                continue;
            }
            float alpha = (float) Math.min(0.85, (s.life - s.age) / 4.0);
            if (alpha <= 0.0F) {
                continue;
            }
            double t = s.life <= 0 ? 1.0 : Math.min(1.0, (s.age + partial) / s.life);
            ExpansionAnchors.Set a = ExpansionAnchors.of(owner);
            Vec3 dir = s.dir.lengthSqr() > 1.0E-4 ? s.dir.normalize() : new Vec3(0, 0, 1);
            String id = s.species.id();
            ResourceLocation orb = tex(id, "orb");
            ResourceLocation ring = tex(id, "ring");
            ResourceLocation slash = tex(id, "slash");
            ResourceLocation field = tex(id, "field");
            try {
                switch (s.phase) {
                    case "start" -> renderStart(x, s, a, dir, t, alpha, orb, ring, slash, field);
                    case "release" -> renderRelease(x, s, a, dir, t, alpha, orb, ring, slash, field);
                    case "projectile", "lob" -> renderShot(x, s, dir, t, alpha, orb, ring, slash);
                    case "impact" -> renderImpact(x, s, a, dir, t, alpha, orb, ring, slash, field);
                    case "spike" -> renderSpike(x, s, dir, t, alpha, orb, ring, slash);
                    case "heal" -> renderHeal(x, s, a, t, alpha, orb, ring, slash);
                    case "blink" -> renderBlink(x, s, dir, t, alpha, ring, orb, slash);
                    case "field" -> renderField(x, s, a, dir, t, alpha, orb, ring, field);
                    case "wall" -> renderWall(x, s, dir, t, alpha, ring, field, slash);
                    case "buff" -> renderBuff(x, s, a, t, alpha, orb, ring, slash);
                    case "followup" -> renderFollowup(x, s, a, dir, t, alpha, orb, ring, slash);
                    default -> renderRelease(x, s, a, dir, t, alpha, orb, ring, slash, field);
                }
            } catch (RuntimeException ignored) {
                // A single malformed anchor must never kill the whole world render pass.
            }
        }
    }

    private record Ctx(WorldRenderContext c, Matrix4f matrix, Vec3 camera, double time, float partial) {
    }

    /** Charge-up silhouette: where the skill gathers before the cast. */
    private static void renderStart(Ctx x, Scene s, ExpansionAnchors.Set a, Vec3 dir, double t,
                                    float alpha, ResourceLocation orb, ResourceLocation ring,
                                    ResourceLocation slash, ResourceLocation field) {
        Vec3 p = s.pos;
        double range = Math.max(1.0, s.move().range());
        switch (s.style) {
            case BOLT -> {
                Vec3 hand = a.get(ExpansionAnchors.Slot.HAND_PRIMARY);
                orb(x, hand.add(0, 0.15, 0), 0.18 + 0.30 * t, orb, alpha);
                ringAt(x, a.get(ExpansionAnchors.Slot.FOOT_PRIMARY), 0.7 + 0.4 * t, ring, alpha * 0.5F);
                motes(x, hand, 5, 0.6 - 0.3 * t, 0.5, orb, alpha * 0.6F);
            }
            case LOB -> {
                Vec3 hand = a.get(ExpansionAnchors.Slot.HAND_PRIMARY);
                Vec3 charge = s.species.id().equals("gardevoir")
                        ? a.head().add(0, 0.9 + t * 1.1, 0)
                        : hand.add(0, 0.5 + t * 0.5, 0);
                orb(x, charge, 0.22 + 0.45 * t, orb, alpha);
                ringAt(x, p.add(0, 0.06, 0), Math.max(0.5, s.move().radius() * 0.7), ring, alpha * 0.45F);
                motes(x, charge, 6, 1.2 - 0.6 * t, 0.8, orb, alpha * 0.7F);
            }
            case FAN -> {
                Vec3 hand = a.get(ExpansionAnchors.Slot.HAND_PRIMARY);
                billboard(x, hand, 0.9 + 0.4 * t, 0.9 + 0.4 * t, x.time * 2.4 + t * 6.0, slash, alpha * 0.7F);
                ground(x, a.position(), 0.8 + 0.5 * t, x.time, slash, alpha * 0.35F);
            }
            case RUSH, DIG -> {
                Vec3 foot = a.get(ExpansionAnchors.Slot.FOOT_PRIMARY);
                ringAt(x, foot.add(0, 0.05, 0), 0.8 + 0.7 * t, ring, alpha);
                for (int i = 0; i < 4; i++) {
                    Vec3 tail = a.position().subtract(dir.scale(0.6 + i * 0.5));
                    billboard(x, tail.add(0, 0.1, 0), 0.5, 0.35, Math.atan2(dir.z, dir.x), slash, alpha * (0.5F - i * 0.1F));
                }
            }
            case DRAIN -> {
                Vec3 hand = a.get(ExpansionAnchors.Slot.HAND_PRIMARY);
                beam(x, p, hand, 0.16, slash, alpha * 0.25F);
                motes(x, p, 4, 0.6, 0.7, orb, alpha * 0.5F);
            }
            case STOMP -> {
                Vec3 foot = a.get(ExpansionAnchors.Slot.FOOT_PRIMARY);
                ringAt(x, foot.add(0, 0.05, 0), 1.0 + 0.8 * t, ring, alpha);
                for (int i = 0; i < 5; i++) {
                    double ang = i * Math.PI * 2 / 5;
                    Vec3 mote = foot.add(Math.cos(ang) * (0.8 + 0.6 * t), 0.3 + t * 0.6, Math.sin(ang) * (0.8 + 0.6 * t));
                    billboard(x, mote, 0.22, 0.22, ang, orb, alpha * 0.6F);
                }
            }
            case ROCKFALL -> {
                ringAt(x, p.add(0, 0.05, 0), Math.max(0.8, s.move().radius() * 0.85), ring, alpha * 0.6F);
                for (int i = 0; i < 6; i++) {
                    double ang = i * Math.PI * 2 / 6 + x.time * 0.4;
                    double h = 4.5 - t * 2.0 - (i % 3) * 0.6;
                    Vec3 mote = p.add(Math.cos(ang) * s.move().radius() * 0.5, h, Math.sin(ang) * s.move().radius() * 0.5);
                    billboard(x, mote, 0.4, 0.5, ang, orb, alpha * 0.8F);
                }
            }
            case WALL -> {
                Vec3 perp = new Vec3(-dir.z, 0, dir.x).normalize();
                for (int i = -3; i <= 3; i++) {
                    Vec3 base = p.add(perp.scale(i * (s.move().radius() / 3.0))).add(0, 0.05, 0);
                    pillar(x, base, 0.35, Math.max(0.3, t * 2.6), field, alpha * 0.45F);
                }
                ringAt(x, p.add(0, 0.05, 0), Math.max(0.8, s.move().range()), ring, alpha * 0.35F);
            }
            case ICE_STORM -> {
                ringAt(x, p.add(0, 0.05, 0), 0.6 + t * s.move().radius() * 0.9, ring, alpha * 0.55F);
                for (int i = 0; i < 8; i++) {
                    double ang = i * Math.PI / 4 + x.time * 0.5;
                    double r = s.move().radius() * (0.3 + 0.6 * t) * (0.7 + 0.3 * Math.sin(x.time + i));
                    Vec3 mote = p.add(Math.cos(ang) * r, 0.3 + t * 2.4, Math.sin(ang) * r);
                    billboard(x, mote, 0.24, 0.24, ang, orb, alpha * 0.7F);
                }
            }
            case SAND_STORM -> {
                Vec3 body = a.position();
                ringAt(x, body.add(0, 0.05, 0), 0.9 + t * 1.6, ring, alpha * 0.6F);
                motes(x, body, 8, 1.6, 1.2, field, alpha * 0.35F);
            }
            case SPIKES -> {
                for (int i = 0; i < 5; i++) {
                    Vec3 mark = a.position().add(dir.scale((2.0 + i * 2.0) * t));
                    ringAt(x, mark.add(0, 0.05, 0), 0.5, ring, alpha * (0.5F - i * 0.06F));
                }
            }
            case BLADES -> {
                Vec3 chest = a.get(ExpansionAnchors.Slot.CHEST);
                for (int i = 0; i < 3; i++) {
                    double ang = x.time * 2.0 + i * Math.PI * 2 / 3;
                    Vec3 blade = chest.add(Math.cos(ang) * 1.2, 0.4 + 0.3 * Math.sin(x.time + i), Math.sin(ang) * 1.2);
                    billboard(x, blade, 0.9, 0.35, ang + Math.PI / 2, slash, alpha * 0.8F);
                }
            }
            case BLINK -> {
                ringAt(x, p.add(0, 0.05, 0), 1.0 + 0.6 * t, ring, alpha);
                billboard(x, p.add(0, 0.6 + t, 0), 0.5, 1.2, x.time, slash, alpha * 0.5F);
            }
            case MARK -> {
                ringAt(x, p.add(0, 0.05, 0), Math.max(0.8, s.move().radius() * 0.9), ring, alpha * 0.8F);
                billboard(x, p.add(0, 1.4 + t, 0), 0.8, 1.8, x.time * 1.5, field, alpha * 0.4F);
            }
            default -> {
                field(x, a.position(), Math.max(1.0, s.move().radius()), x.time, field, alpha * 0.5F);
                motes(x, a.position(), 6, 1.4, 1.6, orb, alpha * 0.6F);
            }
        }
        // target telegraph for aimed skills, always drawn: the player must see where it lands
        if (s.style != Style.RUSH && s.style != Style.AURA && s.style != Style.SAND_STORM) {
            ringAt(x, p.add(0, 0.04, 0), Math.min(4.0, 0.5 + range * 0.2), ring, alpha * 0.30F);
        }
    }

    /** The moment the skill fires: each style has a different silhouette. */
    private static void renderRelease(Ctx x, Scene s, ExpansionAnchors.Set a, Vec3 dir, double t,
                                      float alpha, ResourceLocation orb, ResourceLocation ring,
                                      ResourceLocation slash, ResourceLocation field) {
        Vec3 p = s.pos;
        Vec3 body = a.position();
        double range = Math.max(1.0, s.move().range());
        switch (s.style) {
            case BOLT -> {
                Vec3 hand = a.get(ExpansionAnchors.Slot.HAND_PRIMARY);
                orb(x, hand.add(0, 0.1, 0), 0.32 + 0.20 * t, orb, alpha);
                beam(x, hand, p, 0.28, slash, alpha * 0.55F);
                for (int i = 1; i <= 3; i++) {
                    billboard(x, hand.add(dir.scale(i * 1.1)), 0.5 - i * 0.08, 0.5 - i * 0.08,
                            Math.atan2(dir.z, dir.x), orb, alpha * (0.7F - i * 0.15F));
                }
                ringAt(x, p.add(0, 0.06, 0), Math.max(0.6, s.move().radius()), ring, alpha * 0.6F);
            }
            case LOB -> {
                Vec3 hand = a.get(ExpansionAnchors.Slot.HAND_PRIMARY);
                double dist = hand.distanceTo(p);
                double height = Math.max(1.5, dist * 0.35 + 1.2);
                arc(x, hand, p, height, 0.34, orb, alpha, 14);
                orb(x, hand.add(0, 0.2, 0), 0.3, orb, alpha);
                ringAt(x, p.add(0, 0.06, 0), Math.max(0.8, s.move().radius() * 0.9), ring, alpha * 0.55F);
                if (t > 0.75) {
                    burst(x, p.add(0, 0.2, 0), s.move().radius() * 0.7, orb, alpha * (float) ((t - 0.75) * 4.0));
                }
            }
            case FAN -> {
                Vec3 hand = a.get(ExpansionAnchors.Slot.HAND_PRIMARY);
                double base = Math.atan2(dir.z, dir.x);
                int count = s.species.id().equals("blaziken") && s.skill == 1 ? 4 : 3;
                for (int i = 0; i < count; i++) {
                    double spread = (i - (count - 1) / 2.0) * 0.26;
                    Vec3 at = hand.add(dir.scale(range * 0.28)).add(0, 0.35, 0);
                    billboard(x, at, range * 0.55, 0.9, base + spread, slash, alpha * (0.9F - 0.15F * i));
                }
                // ground fan so the sector is readable from the god view
                for (int i = -2; i <= 2; i++) {
                    double spread = i * 0.26;
                    Vec3 mark = body.add(dir.scale(range * 0.55).yRot((float) spread));
                    ground(x, mark.add(0, 0.05, 0), 0.9, base + spread, slash, alpha * 0.30F);
                }
            }
            case RUSH -> {
                for (int i = 0; i < 6; i++) {
                    Vec3 at = body.subtract(dir.scale(i * 0.35)).add(0, 0.35, 0);
                    billboard(x, at, 0.9 - i * 0.10, 1.5 - i * 0.12, Math.atan2(dir.z, dir.x), slash,
                            alpha * (0.8F - i * 0.10F));
                }
                for (int i = 0; i < 4; i++) {
                    ground(x, body.add(dir.scale(1.0 + i * 0.8)).add(0, 0.05, 0), 0.7, Math.atan2(dir.z, dir.x),
                            slash, alpha * 0.3F);
                }
                ringAt(x, a.get(ExpansionAnchors.Slot.FOOT_PRIMARY).add(0, 0.05, 0), 1.1, ring, alpha);
            }
            case DIG -> {
                ringAt(x, a.get(ExpansionAnchors.Slot.FOOT_PRIMARY).add(0, 0.05, 0), 1.4, field, alpha);
                for (int i = 0; i < 5; i++) {
                    Vec3 at = body.subtract(dir.scale(0.4 + i * 0.45)).add(0, 0.25, 0);
                    billboard(x, at, 0.55, 0.4, Math.atan2(dir.z, dir.x), field, alpha * (0.7F - i * 0.1F));
                }
                for (int i = 0; i < 4; i++) {
                    double ang = i * 1.7 + x.time;
                    Vec3 clump = body.add(Math.cos(ang) * 0.9, 0.2 + 0.5 * Math.sin(x.time + i), Math.sin(ang) * 0.9);
                    billboard(x, clump, 0.35, 0.35, ang, orb, alpha * 0.7F);
                }
            }
            case DRAIN -> {
                Vec3 hand = a.get(ExpansionAnchors.Slot.HAND_PRIMARY);
                beam(x, p, hand, 0.34, slash, alpha * 0.75F);
                for (int i = 0; i < 5; i++) {
                    double f = (i / 5.0 + x.time * 0.08) % 1.0;
                    Vec3 mote = p.lerp(hand, f);
                    billboard(x, mote, 0.42, 0.42, x.time, orb, alpha * 0.9F);
                }
            }
            case STOMP -> {
                Vec3 foot = a.get(ExpansionAnchors.Slot.FOOT_PRIMARY);
                ringAt(x, foot.add(0, 0.05, 0), 1.4 + t * range, ring, alpha);
                for (int i = 0; i < 8; i++) {
                    double ang = i * Math.PI / 4;
                    Vec3 at = foot.add(Math.cos(ang) * (1.2 + t * range * 0.8), 0.06, Math.sin(ang) * (1.2 + t * range * 0.8));
                    ground(x, at, 0.9, ang, slash, alpha * 0.45F);
                }
                for (int i = 0; i < 6; i++) {
                    double ang = i * 1.2;
                    Vec3 chunk = foot.add(Math.cos(ang) * 1.4, 0.4 + t * 1.4, Math.sin(ang) * 1.4);
                    billboard(x, chunk, 0.3, 0.3, ang, orb, alpha * 0.7F);
                }
            }
            case ROCKFALL -> {
                for (int i = 0; i < 5; i++) {
                    double ang = i * Math.PI * 2 / 5 + x.time * 0.3;
                    double r = Math.max(0.8, s.move().radius()) * (0.25 + 0.6 * ((i * 37 % 100) / 100.0));
                    Vec3 at = p.add(Math.cos(ang) * r, Math.max(0.2, 5.0 - t * 4.6), Math.sin(ang) * r);
                    billboard(x, at, 0.7, 0.9, ang, orb, alpha);
                }
                ringAt(x, p.add(0, 0.05, 0), Math.max(1.0, s.move().radius()), ring, alpha * 0.6F);
                field(x, p, Math.max(1.5, s.move().radius() * 1.2), x.time, field, alpha * 0.35F);
            }
            case WALL -> {
                // The icy wall is drawn only from the dedicated "wall" scene (server-confirmed);
                // a release cue must not paint a wall the server refused to create.
            }
            case ICE_STORM -> {
                for (int layer = 0; layer < 3; layer++) {
                    ringAt(x, p.add(0, 0.05 + layer * 0.7 + t * 0.8, 0),
                            Math.max(0.6, s.move().radius() * (0.5 + 0.5 * t)), ring, alpha * (0.5F - layer * 0.12F));
                }
                for (int i = 0; i < 12; i++) {
                    double ang = i * Math.PI / 6 + x.time * 0.8;
                    double r = s.move().radius() * (0.2 + 0.7 * ((i * 53 % 100) / 100.0));
                    Vec3 flake = p.add(Math.cos(ang) * r, 0.3 + ((i * 31) % 40) / 20.0 + t, Math.sin(ang) * r);
                    billboard(x, flake, 0.26, 0.26, ang, orb, alpha * 0.85F);
                }
            }
            case SAND_STORM -> {
                ringAt(x, body.add(0, 0.05, 0), 2.2, field, alpha * 0.6F);
                for (int i = 0; i < 10; i++) {
                    double ang = i * Math.PI / 5 + x.time;
                    double r = Math.max(1.2, s.move().radius()) * (0.6 + 0.4 * ((i * 29 % 100) / 100.0));
                    Vec3 grain = body.add(Math.cos(ang) * r, 0.3 + ((i * 17) % 30) / 15.0, Math.sin(ang) * r);
                    billboard(x, grain, 0.34, 0.34, ang, field, alpha * 0.6F);
                }
            }
            case SPIKES -> {
                for (int i = 0; i < 8; i++) {
                    Vec3 mark = a.position().add(dir.scale(2.0 + i * 1.8));
                    ground(x, mark.add(0, 0.05, 0), 0.7, Math.atan2(dir.z, dir.x), slash, alpha * 0.4F);
                }
            }
            case BLADES -> {
                Vec3 chest = a.get(ExpansionAnchors.Slot.CHEST);
                for (int i = 0; i < 4; i++) {
                    double ang = x.time * 3.0 + i * Math.PI / 2;
                    Vec3 blade = chest.add(Math.cos(ang) * (1.2 + t * 1.6), 0.5 + t * 1.4, Math.sin(ang) * (1.2 + t * 1.6));
                    billboard(x, blade, 1.1, 0.4, ang + Math.PI / 2, slash, alpha * 0.9F);
                }
                ringAt(x, body.add(0, 0.05, 0), 1.6 + t * 1.2, ring, alpha * 0.5F);
            }
            case BLINK -> {
                ringAt(x, p.add(0, 0.05, 0), 1.4, ring, alpha);
                ringAt(x, s.dir.add(0, 0.05, 0), 1.4, ring, alpha);
                beam(x, p, s.dir, 0.5, slash, alpha * 0.4F);
            }
            case MARK -> {
                ringAt(x, p.add(0, 0.05, 0), Math.max(1.0, s.move().radius()), ring, alpha);
                beam(x, p, p.add(0, 8, 0), 0.6 + 0.4 * t, field, alpha * 0.55F);
                for (int i = 0; i < 6; i++) {
                    double ang = i * Math.PI / 3 + x.time;
                    Vec3 mote = p.add(Math.cos(ang) * s.move().radius(), 0.2 + t * 2.0, Math.sin(ang) * s.move().radius());
                    billboard(x, mote, 0.3, 0.3, ang, orb, alpha * 0.8F);
                }
            }
            default -> {
                field(x, body, Math.max(1.0, s.move().radius()), x.time, field, alpha * 0.6F);
                ringAt(x, body.add(0, 0.05, 0), 1.6, ring, alpha);
                burst(x, body.add(0, 0.6, 0), 1.2, orb, alpha);
            }
        }
    }

    /** Flying projectile or lobbed object; the trail comes from the server's per-tick positions. */
    private static void renderShot(Ctx x, Scene s, Vec3 dir, double t, float alpha,
                                   ResourceLocation orb, ResourceLocation ring, ResourceLocation slash) {
        double size = s.phase.equals("lob") ? 0.55 : 0.45;
        trail(x, s.trail, size * 0.8, orb, alpha * 0.8F);
        orb(x, s.pos, size, orb, alpha);
        billboard(x, s.pos, size * 2.6, size * 2.6, x.time * 4.0, slash, alpha * 0.35F);
        if (s.style == Style.LOB) {
            ringAt(x, s.pos.add(0, 0.02, 0), size * 1.4, ring, alpha * 0.3F);
        }
    }

    private static void renderImpact(Ctx x, Scene s, ExpansionAnchors.Set a, Vec3 dir, double t,
                                     float alpha, ResourceLocation orb, ResourceLocation ring,
                                     ResourceLocation slash, ResourceLocation field) {
        Vec3 p = s.pos;
        burst(x, p, 0.9 + 0.5 * t, orb, alpha);
        ringAt(x, p.add(0, 0.06, 0), 1.0 + t * 2.2, ring, alpha * 0.8F);
        switch (s.style) {
            case LOB -> {
                for (int i = 0; i < 8; i++) {
                    double ang = i * Math.PI / 4;
                    ground(x, p.add(Math.cos(ang) * (0.6 + t * 1.8), 0.05, Math.sin(ang) * (0.6 + t * 1.8)),
                            0.7, ang, field, alpha * 0.55F);
                }
            }
            case ROCKFALL, STOMP -> {
                for (int i = 0; i < 6; i++) {
                    double ang = i * 1.1 + x.time * 0.5;
                    Vec3 chunk = p.add(Math.cos(ang) * (1.0 + t * 2.0), 0.3 + t * 1.2, Math.sin(ang) * (1.0 + t * 2.0));
                    billboard(x, chunk, 0.42, 0.5, ang, orb, alpha * 0.85F);
                }
                for (int i = 0; i < 6; i++) {
                    double ang = i * Math.PI / 3;
                    ground(x, p.add(Math.cos(ang) * (1.0 + t * 1.6), 0.05, Math.sin(ang) * (1.0 + t * 1.6)),
                            0.8, ang, slash, alpha * 0.4F);
                }
            }
            case FAN -> {
                for (int i = 0; i < 2; i++) {
                    billboard(x, p.add(0, 0.5 + i * 0.45, 0), 1.5, 0.6, Math.atan2(dir.z, dir.x) + (i == 0 ? 0.5 : -0.5),
                            slash, alpha * 0.8F);
                }
            }
            case ICE_STORM -> {
                for (int i = 0; i < 10; i++) {
                    double ang = i * Math.PI / 5 + x.time;
                    Vec3 flake = p.add(Math.cos(ang) * (0.5 + t * 2.0), 0.3 + t * 1.5, Math.sin(ang) * (0.5 + t * 2.0));
                    billboard(x, flake, 0.26, 0.26, ang, orb, alpha * 0.8F);
                }
            }
            case MARK -> {
                ringAt(x, p.add(0, 0.06, 0), 1.5 + t * 3.0, ring, alpha * 0.7F);
                beam(x, p, p.add(0, 6, 0), 1.2, field, alpha * 0.5F);
            }
            case DRAIN -> {
                beam(x, p, a.get(ExpansionAnchors.Slot.HAND_PRIMARY), 0.25, slash, alpha * 0.5F);
            }
            default -> {
                for (int i = 0; i < 5; i++) {
                    double ang = i * 1.3 + x.time;
                    Vec3 mote = p.add(Math.cos(ang) * (0.6 + t * 1.5), 0.3 + t * 1.4, Math.sin(ang) * (0.6 + t * 1.5));
                    billboard(x, mote, 0.28, 0.28, ang, orb, alpha * 0.7F);
                }
            }
        }
    }

    /** Tyranitar R: the server streams one spike cue every few ticks along the line. */
    private static void renderSpike(Ctx x, Scene s, Vec3 dir, double t,
                                    float alpha, ResourceLocation orb, ResourceLocation ring, ResourceLocation slash) {
        double h = 1.2 + 1.6 * Math.min(1.0, t * 2.2);
        spikeUp(x, s.pos, 0.55, h, orb, alpha);
        ringAt(x, s.pos.add(0, 0.05, 0), 1.0, ring, alpha * 0.7F);
        for (int i = 0; i < 3; i++) {
            double ang = i * Math.PI / 3;
            ground(x, s.pos.add(Math.cos(ang) * 1.1, 0.05, Math.sin(ang) * 1.1), 0.7, ang, slash, alpha * 0.35F);
        }
    }

    /** Gengar R: victim at p, caster at d, so the beam is drawn p -> d with motes flowing inward. */
    private static void renderHeal(Ctx x, Scene s, ExpansionAnchors.Set a, double t,
                                   float alpha, ResourceLocation orb, ResourceLocation ring, ResourceLocation slash) {
        Vec3 from = s.pos;
        Vec3 to = s.dir.lengthSqr() > 1.0E-4 ? s.dir : a.get(ExpansionAnchors.Slot.HAND_PRIMARY);
        beam(x, from, to, 0.36, slash, alpha * 0.8F);
        beam(x, from, to, 0.18, orb, alpha * 0.6F);
        for (int i = 0; i < 6; i++) {
            double f = (i / 6.0 + x.time * 0.10) % 1.0;
            billboard(x, from.lerp(to, f), 0.42, 0.42, x.time, orb, alpha * 0.9F);
        }
        ringAt(x, from.add(0, 0.06, 0), 0.9 + t, ring, alpha * 0.5F);
    }

    /** Gardevoir E: rings and light columns at both the departure and arrival point. */
    private static void renderBlink(Ctx x, Scene s, Vec3 dir, double t,
                                    float alpha, ResourceLocation ring, ResourceLocation orb, ResourceLocation slash) {
        ringAt(x, s.pos.add(0, 0.05, 0), 0.8 + t * 1.6, ring, alpha);
        ringAt(x, s.dir.add(0, 0.05, 0), 0.8 + t * 1.6, ring, alpha);
        pillar(x, s.pos, 0.35, 2.2, slash, alpha * 0.6F);
        pillar(x, s.dir, 0.35, 2.2, slash, alpha * 0.6F);
        for (int i = 0; i < 6; i++) {
            double f = (i / 6.0 + x.time * 0.07) % 1.0;
            Vec3 mote = s.pos.lerp(s.dir, f);
            billboard(x, mote, 0.3, 0.3, x.time, orb, alpha * 0.8F);
        }
    }

    /** Abomasnow E: the confirmed icy wall, built along the perpendicular of the cast direction. */
    private static void renderWall(Ctx x, Scene s, Vec3 dir, double t,
                                   float alpha, ResourceLocation ring, ResourceLocation field, ResourceLocation slash) {
        Vec3 p = s.pos;
        double halfWidth = Math.max(1.0, s.move().radius());
        Vec3 perp = new Vec3(-dir.z, 0, dir.x);
        if (perp.lengthSqr() < 1.0E-4) {
            perp = new Vec3(1, 0, 0);
        }
        perp = perp.normalize();
        double rise = Math.min(1.0, t * 4.0);
        for (int i = -3; i <= 3; i++) {
            Vec3 base = p.add(perp.scale(i * (halfWidth / 3.0))).add(0, 0.05, 0);
            double height = 2.4 * rise;
            pillar(x, base, 0.5, height, field, alpha * 0.85F);
            billboard(x, base.add(0, 0.3, 0), 0.7, 0.7, x.time * 0.7 + i, slash, alpha * 0.35F);
        }
        ground(x, p.add(0, 0.05, 0), halfWidth, Math.atan2(dir.z, dir.x), ring, alpha * 0.4F);
        for (int i = 0; i < 6; i++) {
            double ang = i * Math.PI / 3 + x.time * 0.4;
            Vec3 flake = p.add(perp.scale(Math.cos(ang) * halfWidth)).add(0, 0.2 + ((i * 19) % 20) / 10.0, 0);
            billboard(x, flake.add(0, 0, Math.sin(ang) * 0.4), 0.24, 0.24, ang, field, alpha * 0.6F);
        }
    }

    /** Persistent detached zone: tyranitar E sandstorm, abomasnow R snowstorm. */
    private static void renderField(Ctx x, Scene s, ExpansionAnchors.Set a, Vec3 dir, double t,
                                    float alpha, ResourceLocation orb, ResourceLocation ring, ResourceLocation field) {
        // Tyranitar's sandstorm travels with the caster (server damage tests around the entity),
        // while abomasnow's detached snowstorm stays on the world point it was cued at.
        Vec3 base = s.style == Style.ICE_STORM
                ? s.pos
                : a.get(ExpansionAnchors.Slot.FOOT_PRIMARY).add(a.get(ExpansionAnchors.Slot.FOOT_SECONDARY)).scale(0.5);
        double radius = Math.max(1.2, s.move().radius());
        if (s.style == Style.ICE_STORM) {
            // Snowstorm: a tall slow column of drifting flakes instead of the low sand swirl, so the
            // detached abomasnow R zone still reads as ice even after the caster walks away.
            for (int i = 0; i < 18; i++) {
                double ang = i * Math.PI / 9 + x.time * 0.35;
                double r = radius * (0.25 + 0.65 * ((i * 37 % 100) / 100.0));
                double cycle = (x.time * 0.45 + i * 0.17) % 1.0;
                double h = 0.2 + cycle * 3.2;
                Vec3 flake = base.add(Math.cos(ang) * r, h, Math.sin(ang) * r);
                billboard(x, flake, 0.42, 0.42, ang, orb, alpha * (0.35F + 0.35F * (float) (1.0 - cycle)));
            }
            ringAt(x, base.add(0, 0.05, 0), radius * 0.95, ring, alpha * 0.5F);
            billboard(x, base.add(0, 1.6, 0), radius * 1.5, 2.4, x.time * 0.2, field, alpha * 0.22F);
            return;
        }
        ringAt(x, base.add(0, 0.05, 0), radius * (0.8 + 0.2 * Math.sin(x.time * 0.7)), field, alpha * 0.55F);
        ringAt(x, base.add(0, 0.05 + 0.4, 0), radius * 1.1, ring, alpha * 0.25F);
        for (int i = 0; i < 12; i++) {
            double ang = i * Math.PI / 6 + x.time * 0.65;
            double r = radius * (0.5 + 0.5 * ((i * 41 % 100) / 100.0));
            double h = 0.25 + ((i * 23) % 30) / 12.0;
            Vec3 grain = base.add(Math.cos(ang) * r, h, Math.sin(ang) * r);
            billboard(x, grain, 0.36, 0.36, ang, field, alpha * 0.55F);
        }
        motes(x, base, 6, radius, 1.2, orb, alpha * 0.35F);
    }

    /** Scizor R swords dance: orbiting blades around the chest. */
    private static void renderBuff(Ctx x, Scene s, ExpansionAnchors.Set a, double t,
                                   float alpha, ResourceLocation orb, ResourceLocation ring, ResourceLocation slash) {
        Vec3 chest = a.get(ExpansionAnchors.Slot.CHEST);
        final int blades = 4;
        for (int i = 0; i < blades; i++) {
            double ang = x.time * 2.4 + i * Math.PI * 2 / blades;
            double lift = 0.4 + 0.9 * Math.abs(Math.sin(x.time * 0.8 + i));
            Vec3 blade = chest.add(Math.cos(ang) * 1.35, lift, Math.sin(ang) * 1.35);
            billboard(x, blade, 1.2, 0.34, ang + Math.PI / 2, slash, alpha * 0.9F);
        }
        ringAt(x, a.position().add(0, 0.05, 0), 1.5 + 0.3 * Math.sin(x.time), ring, alpha * 0.45F);
        motes(x, a.position(), 5, 1.2, 2.0, orb, alpha * 0.5F);
    }

    /** Scizor Q follow-up jab while the dance buff is active. */
    private static void renderFollowup(Ctx x, Scene s, ExpansionAnchors.Set a, Vec3 dir, double t,
                                       float alpha, ResourceLocation orb, ResourceLocation ring, ResourceLocation slash) {
        Vec3 hand = a.get(ExpansionAnchors.Slot.HAND_PRIMARY);
        beam(x, hand, s.pos, 0.24, slash, alpha * 0.8F);
        billboard(x, s.pos, 0.9, 0.5, Math.atan2(dir.z, dir.x), slash, alpha * 0.8F);
        burst(x, s.pos, 0.7, orb, alpha * 0.8F);
        ringAt(x, s.pos.add(0, 0.05, 0), 0.9, ring, alpha * 0.5F);
    }

    // ---------------------------------------------------------------------------------------------
    // drawing primitives (all textured quads, no camera vectors so no matrix surprises)
    // ---------------------------------------------------------------------------------------------

    /** Two crossed vertical quads, rotated around Y by yaw. */
    private static void billboard(Ctx x, Vec3 p, double w, double h, double yaw, ResourceLocation t, float alpha) {
        double cx = Math.cos(yaw) * w * 0.5;
        double cz = Math.sin(yaw) * w * 0.5;
        Vec3 right = new Vec3(cx, 0, cz);
        Vec3 up = new Vec3(0, h * 0.5, 0);
        Vec3 base = p.subtract(x.camera);
        quad(x, t, base.subtract(right).subtract(up), base.add(right).subtract(up),
                base.add(right).add(up), base.subtract(right).add(up), alpha);
        Vec3 right2 = new Vec3(-cz, 0, cx);
        quad(x, t, base.subtract(right2).subtract(up), base.add(right2).subtract(up),
                base.add(right2).add(up), base.subtract(right2).add(up), alpha);
    }

    /** Horizontal quad (ground decal) rotated around Y. */
    private static void ground(Ctx x, Vec3 p, double r, double yaw, ResourceLocation t, float alpha) {
        Vec3 right = new Vec3(Math.cos(yaw) * r, 0, Math.sin(yaw) * r);
        Vec3 forward = new Vec3(-Math.sin(yaw) * r, 0, Math.cos(yaw) * r);
        Vec3 base = p.subtract(x.camera);
        quad(x, t, base.subtract(right).subtract(forward), base.add(right).subtract(forward),
                base.add(right).add(forward), base.subtract(right).add(forward), alpha);
    }

    private static void ringAt(Ctx x, Vec3 p, double r, ResourceLocation t, float alpha) {
        ground(x, p, r, 0, t, alpha);
        ground(x, p, r, Math.PI / 4, t, alpha);
    }

    /** Textured field decal that slowly spins, used for persistent ground auras. */
    private static void field(Ctx x, Vec3 p, double r, double yaw, ResourceLocation t, float alpha) {
        ground(x, p, r, yaw, t, alpha);
    }

    private static void orb(Ctx x, Vec3 p, double r, ResourceLocation t, float alpha) {
        billboard(x, p, r * 2, r * 2, 0, t, alpha);
        billboard(x, p, r * 2, r * 2, Math.PI / 2, t, alpha);
        ringAt(x, p, r, t, alpha * 0.7F);
    }

    /** Stepped billboards along a segment: reads as a beam and needs no camera basis. */
    private static void beam(Ctx x, Vec3 a, Vec3 b, double w, ResourceLocation t, float alpha) {
        Vec3 delta = b.subtract(a);
        double length = delta.length();
        if (length < 0.05) {
            return;
        }
        Vec3 dir = delta.scale(1.0 / length);
        double yaw = Math.atan2(dir.z, dir.x);
        int steps = Math.max(2, (int) Math.min(18, length / 0.45));
        for (int i = 0; i <= steps; i++) {
            double f = i / (double) steps;
            Vec3 at = a.add(delta.scale(f));
            billboard(x, at, w * (1.0 - 0.35 * f), w * (1.0 - 0.35 * f), yaw, t, alpha);
        }
    }

    /** Parabolic arc sampled from a to b: used for lobbed sludge / moon descent. */
    private static void arc(Ctx x, Vec3 a, Vec3 b, double height, double w, ResourceLocation t,
                            float alpha, int segments) {
        for (int i = 0; i <= segments; i++) {
            double f = i / (double) segments;
            Vec3 at = a.lerp(b, f).add(0, Math.sin(f * Math.PI) * height, 0);
            billboard(x, at, w * (1.0 - 0.3 * f), w * (1.0 - 0.3 * f), x.time * 3.0, t, alpha * (1.0F - 0.4F * (float) f));
        }
    }

    /** Ribbon from per-tick history points. */
    private static void trail(Ctx x, List<Vec3> points, double w, ResourceLocation t, float alpha) {
        for (int i = 0; i < points.size(); i++) {
            double f = i / (double) Math.max(1, points.size() - 1);
            billboard(x, points.get(i), w * (1.0 - 0.6 * f), w * (1.0 - 0.6 * f), x.time * 2.0, t,
                    alpha * (float) (1.0 - 0.6 * f));
        }
    }

    /** Rising stone spike: three stacked quads tapering upward. */
    private static void spikeUp(Ctx x, Vec3 base, double r, double h, ResourceLocation t, float alpha) {
        for (int i = 0; i < 3; i++) {
            double f = i / 3.0;
            Vec3 at = base.add(0, h * (f + 0.15), 0);
            double w = r * (1.0 - 0.55 * f);
            billboard(x, at, w * 2, h * 0.55, 0.6 * i, t, alpha);
            billboard(x, at, w * 2, h * 0.55, 0.6 * i + Math.PI / 2, t, alpha);
        }
    }

    private static void pillar(Ctx x, Vec3 base, double r, double h, ResourceLocation t, float alpha) {
        for (int i = 0; i < 3; i++) {
            double f = i / 2.0;
            billboard(x, base.add(0, h * (0.15 + f * 0.7), 0), r * 2, h * 0.5, f * Math.PI / 3, t, alpha * (1.0F - 0.25F * (float) f));
        }
    }

    private static void burst(Ctx x, Vec3 p, double r, ResourceLocation t, float alpha) {
        for (int i = 0; i < 4; i++) {
            billboard(x, p, r * 2, r * 2, i * Math.PI / 4, t, alpha * (0.9F - i * 0.15F));
        }
        ringAt(x, p, r, t, alpha * 0.8F);
    }

    private static void motes(Ctx x, Vec3 center, int count, double radius, double height,
                              ResourceLocation t, float alpha) {
        for (int i = 0; i < count; i++) {
            double ang = i * Math.PI * 2 / count + x.time * 0.9;
            double r = radius * (0.4 + 0.6 * ((i * 47 % 100) / 100.0));
            double h = height * (0.3 + 0.7 * ((i * 29 % 100) / 100.0));
            billboard(x, center.add(Math.cos(ang) * r, h, Math.sin(ang) * r), 0.3, 0.3, ang, t, alpha);
        }
    }

    private static void quad(Ctx x, ResourceLocation texture, Vec3 a, Vec3 b, Vec3 c, Vec3 d, float alpha) {
        VertexConsumer v = x.c.consumers().getBuffer(RenderType.entityTranslucentEmissive(texture));
        vertex(v, x.matrix, a, 0, 0, alpha);
        vertex(v, x.matrix, b, 1, 0, alpha);
        vertex(v, x.matrix, c, 1, 1, alpha);
        vertex(v, x.matrix, d, 0, 1, alpha);
        vertex(v, x.matrix, d, 0, 1, alpha);
        vertex(v, x.matrix, c, 1, 1, alpha);
        vertex(v, x.matrix, b, 1, 0, alpha);
        vertex(v, x.matrix, a, 0, 0, alpha);
    }

    private static void vertex(VertexConsumer v, Matrix4f m, Vec3 p, float u, float tv, float alpha) {
        v.addVertex(m, (float) p.x, (float) p.y, (float) p.z)
                .setColor(255, 255, 255, (int) (255 * Math.max(0, Math.min(1, alpha))))
                .setUv(u, tv)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0, 1, 0);
    }

    // ---------------------------------------------------------------------------------------------
    // animation + audio
    // ---------------------------------------------------------------------------------------------

    /** Anchor used for the charge sound of a style (the visual effect draws itself from anchors). */
    private static Vec3 anchored(Entity entity, Style style) {
        if (entity == null) {
            return Vec3.ZERO;
        }
        ExpansionAnchors.Set a = ExpansionAnchors.of(entity);
        return switch (style) {
            case RUSH, DIG, STOMP, SPIKES -> a.get(ExpansionAnchors.Slot.FOOT_PRIMARY);
            case WALL, ICE_STORM, SAND_STORM, BLADES, AURA -> a.get(ExpansionAnchors.Slot.CHEST);
            case ROCKFALL, MARK -> a.get(ExpansionAnchors.Slot.HAND_PRIMARY);
            default -> a.get(ExpansionAnchors.Slot.HAND_PRIMARY);
        };
    }

    /** Play a phased bone clip when the model exposes it; fall back to the legacy whole-skill clip. */
    private static void animate(Entity entity, ExpansionRoster.Species species, int skill, String suffix) {
        if (!(entity instanceof PokemonEntity pokemon)) {
            return;
        }
        String actual = pokemon.getPokemon().getSpecies().getResourceIdentifier().getPath();
        if (!actual.equals(species.id()) && !actual.equals("cobblemon:" + species.id())) {
            return;
        }
        char slot = "qwer".charAt(skill);
        try {
            ((PosableEntity) pokemon).playAnimation(
                    "q.bedrock_primary('pkmoba_" + species.id() + "', '" + slot + suffix + "', q.curve('one'))",
                    List.of());
        } catch (Throwable ignored) {
            try {
                ((PosableEntity) pokemon).playAnimation(
                        "q.bedrock_primary('pkmoba_" + species.id() + "', '" + slot + "', q.curve('one'))",
                        List.of());
            } catch (Throwable ignoredToo) {
                // Missing animation data must never break the cast; visuals below still play.
            }
        }
    }

    /**
     * Layered per-stage audio. One call per cue (charge, cast, impact) instead of a per-frame loop;
     * pitches vary per slot so the four skills of one species do not sound identical.
     */
    private static void playStage(ExpansionRoster.Species species, int skill, String stage, Vec3 at, float gain) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (!finite(at) || at.distanceToSqr(mc.player.position()) > AUDIO_SQR) {
            return;
        }
        Element element = elementFor(species.id());
        SoundEvent primary = switch (stage) {
            case "start" -> switch (element) {
                case FIGHTING -> SoundEvents.TOTEM_USE;
                case ICE -> SoundEvents.CANDLE_EXTINGUISH;
                case FIRE -> SoundEvents.FIRE_AMBIENT;
                case GHOST -> SoundEvents.PORTAL_AMBIENT;
                case ROCK -> SoundEvents.WOOL_HIT;
                case DRAGON -> SoundEvents.GENERIC_EXTINGUISH_FIRE;
                case STEEL -> SoundEvents.ITEM_PICKUP;
                case PSYCHIC -> SoundEvents.NOTE_BLOCK_PLING.value();
            };
            case "release" -> switch (element) {
                case FIGHTING -> SoundEvents.PLAYER_ATTACK_STRONG;
                case ICE -> SoundEvents.SNOWBALL_THROW;
                case FIRE -> SoundEvents.FIRECHARGE_USE;
                case GHOST -> SoundEvents.GHAST_SHOOT;
                case ROCK -> SoundEvents.ANVIL_LAND;
                case DRAGON -> SoundEvents.GHAST_SHOOT;
                case STEEL -> SoundEvents.ANVIL_LAND;
                case PSYCHIC -> SoundEvents.BEACON_POWER_SELECT;
            };
            default -> switch (element) {
                case FIGHTING -> SoundEvents.PLAYER_ATTACK_STRONG;
                case ICE -> SoundEvents.WOOL_HIT;
                case FIRE -> SoundEvents.FIRE_EXTINGUISH;
                case GHOST -> SoundEvents.SLIME_SQUISH;
                case ROCK -> SoundEvents.ANVIL_LAND;
                case DRAGON -> SoundEvents.GHAST_SHOOT;
                case STEEL -> SoundEvents.ANVIL_LAND;
                case PSYCHIC -> SoundEvents.BEACON_ACTIVATE;
            };
        };
        float volume = switch (stage) {
            case "start" -> 0.20F;
            case "release" -> 0.45F;
            default -> 0.35F;
        } * gain;
        float pitch = switch (element) {
            case FIGHTING -> 1.15F;
            case ICE -> 1.30F;
            case FIRE -> 0.95F;
            case GHOST -> 0.80F;
            case ROCK -> 0.70F;
            case DRAGON -> 0.85F;
            case STEEL -> 1.20F;
            case PSYCHIC -> 1.45F;
        } * (1.0F - 0.04F * skill);
        mc.level.playLocalSound(at.x, at.y, at.z, primary, SoundSource.PLAYERS, volume, pitch, false);

        // Second, quieter layer that gives each silhouette an extra texture.
        SoundEvent accent = switch (stage) {
            case "start" -> null;
            case "release" -> switch (element) {
                case ICE -> SoundEvents.CANDLE_EXTINGUISH;
                case ROCK, STEEL -> SoundEvents.GENERIC_EXTINGUISH_FIRE;
                case PSYCHIC -> SoundEvents.NOTE_BLOCK_PLING.value();
                case FIGHTING, DRAGON -> SoundEvents.FLINTANDSTEEL_USE;
                default -> null;
            };
            default -> switch (element) {
                case ICE -> SoundEvents.GENERIC_EXTINGUISH_FIRE;
                case FIRE -> SoundEvents.FIRE_AMBIENT;
                default -> null;
            };
        };
        if (accent != null) {
            mc.level.playLocalSound(at.x, at.y, at.z, accent, SoundSource.PLAYERS, volume * 0.55F, pitch * 1.25F, false);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // state
    // ---------------------------------------------------------------------------------------------

    private static ResourceLocation tex(String species, String kind) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/" + species + "_" + kind + ".png");
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static final class Scene {
        final ExpansionRoster.Species species;
        final int skill;
        final Style style;
        final String phase;
        final int owner;
        final int life;
        Vec3 pos;
        Vec3 dir;
        final List<Vec3> trail = new ArrayList<>();
        int age;

        Scene(ExpansionRoster.Species species, int skill, Style style, String phase, int owner,
              Vec3 pos, Vec3 dir, int life) {
            this.species = species;
            this.skill = skill;
            this.style = style;
            this.phase = phase;
            this.owner = owner;
            this.pos = pos;
            this.dir = dir;
            this.life = life;
        }

        ExpansionRoster.Move move() {
            return species.move(skill);
        }

        void push(Vec3 at) {
            trail.add(at);
            while (trail.size() > MAX_TRAIL) {
                trail.remove(0);
            }
        }
    }
}
