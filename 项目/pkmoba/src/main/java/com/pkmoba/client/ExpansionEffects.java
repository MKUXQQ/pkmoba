package com.pkmoba.client;

import com.cobblemon.mod.common.entity.PosableEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.client.fx.NinetalesFx;
import com.pkmoba.client.fx.BlazikenFx;
import com.pkmoba.client.fx.CeruledgeFx;
import com.pkmoba.client.fx.KrookodileFx;
import com.pkmoba.client.fx.ArmarougeFx;
import com.pkmoba.client.fx.ZoroarkFx;
import com.pkmoba.client.fx.Fx;
import com.pkmoba.client.fx.FxView;
import com.pkmoba.client.fx.GardevoirFx;
import com.pkmoba.client.fx.GengarFx;
import com.pkmoba.client.fx.GreninjaFx;
import com.pkmoba.client.fx.LucarioFx;
import com.pkmoba.client.fx.MeowscaradaFx;
import com.pkmoba.client.fx.TyranitarFx;
import com.pkmoba.combat.ExpansionRoster;
import com.pkmoba.combat.ExpansionWalls;
import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-cued, UV-mapped solid effects for the eight expansion Pokemon.
 *
 * <p>Every move owns a visual style (flame, ice, rock, shadow, psychic, moon, blade, sand,
 * dragon, fist) and every cue builds a layered composition from closed meshes: glowing core,
 * orbiting energy, ground shock waves, debris and a signature burst. Nothing here is a flat
 * billboard. Melee strikes still animate the real Pokemon body through the attack cues.
 */
public final class ExpansionEffects {
    private static final String PREFIX = "pkmoba:expansion/";
    private static final Map<String, Scene> SCENES = new LinkedHashMap<>();
    private static ClientLevel world;
    private static long sequence;
    private ExpansionEffects() {}

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(ExpansionEffects::render);
        // 0.133.1: one-line proof that the baked animation groups resolve, visible without playing.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> logAnimationGroups());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            fireScheduled(mc);
            if (world != mc.level) { clear(); world = mc.level; }
            SCENES.values().removeIf(s -> ++s.age > s.life || mc.level == null || mc.level.getEntity(s.owner) == null);
        });
    }
    private static void logAnimationGroups() {
        String[][] probes = {{"ceruledge", "physical"}, {"ceruledge", "animation.ceruledge.physical"},
                {"pkmoba_ceruledge", "q_cast"}, {"pkmoba_ceruledge", "animation.pkmoba_ceruledge.q_cast"}};
        for (String[] probe : probes) {
            boolean found;
            try {
                found = com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation
                        .BedrockAnimationRepository.INSTANCE.tryGetAnimation(probe[0], probe[1]) != null;
            } catch (RuntimeException error) {
                found = false;
            }
            CLIP_LOG.info("animprobe {} :: {} -> {}", probe[0], probe[1], found);
        }
    }

    private static void clear() {
        SCENES.clear();
        PENDING.clear();
        BEAT.clear();
        world = null;
        ExpansionWalls.clearClient();
    }

    /** Chained body beats (e.g. a spinning Swords Dance) fired on later ticks. */
    private record Pending(int owner, String species, String clip, long due) { }
    private static final List<Pending> PENDING = new java.util.ArrayList<>();

    private static void schedule(Entity entity, String species, String clip, int delay) {
        if (entity == null) return;
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level == null ? 0 : mc.level.getGameTime();
        PENDING.add(new Pending(entity.getId(), species, clip, now + delay));
    }

    private static void fireScheduled(Minecraft mc) {
        if (mc.level == null || PENDING.isEmpty()) return;
        long now = mc.level.getGameTime();
        for (int i = PENDING.size() - 1; i >= 0; i--) {
            Pending p = PENDING.get(i);
            if (p.due > now) continue;
            PENDING.remove(i);
            Entity entity = mc.level.getEntity(p.owner);
            if (entity instanceof PokemonEntity pokemon) {
                if (!matches(pokemon, p.species)) continue;
                playClip(pokemon, p.species, p.clip);
            }
        }
    }

    public static boolean isMelee(String id, int skill) {
        return switch (id) {
            case "lucario" -> skill > 0;
            case "blaziken" -> true;
            case "tyranitar" -> skill == 1;
            case "gengar" -> skill == 3;
            case "greninja" -> skill == 1;
            case "meowscarada" -> skill == 1;
            case "ceruledge" -> skill == 1 || skill == 2;
            case "hisuian_zoroark" -> skill == 0 || skill == 1;
            case "krookodile" -> skill == 0;
            default -> false;
        };
    }

    /** Visual language per move: drives palette accents and the mesh composition. */
    private static String style(String id, int skill) {
        return switch (id) {
            case "lucario" -> switch (skill) { case 0 -> "AURA"; case 1 -> "FIST"; case 2 -> "RUSH"; default -> "FIST"; };
            case "alolan_ninetales" -> switch (skill) { case 2 -> "MIST"; default -> "ICE"; };
            case "blaziken" -> "FLAME";
            case "gengar" -> switch (skill) { case 0 -> "SHADOW"; case 1 -> "SLUDGE"; case 2 -> "HYPNOSIS"; default -> "DREAM"; };
            case "tyranitar" -> switch (skill) { case 0 -> "ROCK"; case 1 -> "FANG"; case 2 -> "SAND"; default -> "ROCK"; };
            case "gardevoir" -> switch (skill) { case 0 -> "PSYCHIC"; case 1 -> "MOON"; case 2 -> "BLINK"; default -> "GLEAM"; };
            case "greninja" -> switch (skill) { case 0 -> "SHURIKEN"; case 1 -> "DARKBLADE"; case 2 -> "DARK"; default -> "WATER"; };
            case "meowscarada" -> switch (skill) { case 0 -> "FLOWER"; case 1 -> "CLAW"; case 2 -> "FLIP"; default -> "GRASS"; };
            case "ceruledge" -> switch (skill) { case 0 -> "PSYBLADE"; case 1 -> "SHADOW"; case 2 -> "BITTER"; default -> "DANCE"; };
            case "hisuian_zoroark" -> switch (skill) { case 0 -> "GHOSTCLAW"; case 1 -> "MALICE"; case 2 -> "SHADOWBALL"; default -> "ILLUSION"; };
            case "krookodile" -> switch (skill) { case 0 -> "CRUNCH"; case 1 -> "DIG"; case 2 -> "QUAKE"; default -> "OUTRAGE"; };
            case "armarouge" -> switch (skill) { case 0 -> "PSYSHOCK"; case 1 -> "FLAMECHARGE"; case 2 -> "CANNON"; default -> "FORCE"; };
            default -> "AURA";
        };
    }


    /**
     * 0.81 蓄能焰袭: a real fire wall that grows out of the ground behind the dash.
     *
     * <p>It is a vertical quad spanning the dash direction (a curtain, not a camera-facing sprite),
     * drawn with the shared textured-mesh path so it is visible from both sides: the early billboard
     * version sat flat on the floor and could not be seen at all.
     */


    public static boolean accept(EffectPayload payload) {
        // 0.133 Hisuian Zoroark: malice marks ride their own cue because they belong to the victim,
        // not to the caster's skill scene; zero stacks means the timer ran out and the sigil fades.
        if (payload.effectId().startsWith("pkmoba:mark/")) {
            Minecraft markClient = Minecraft.getInstance();
            if (markClient.level == null) return true;
            if (world != markClient.level) { clear(); world = markClient.level; }
            String[] mark = payload.effectId().split("/");
            int stacks = mark.length > 2 ? Integer.parseInt(mark[2]) : 0;
            String phase = stacks <= 0 ? "markfade" : "mark" + Math.min(3, stacks);
            Vec3 at = new Vec3(payload.x(), payload.y(), payload.z());
            SCENES.put("mark:" + mark[1], new Scene("hisuian_zoroark", 0, phase, Integer.parseInt(mark[1]),
                    at, Vec3.ZERO, 18, markClient.level.getGameTime()));
            return true;
        }
        if (payload.effectId().startsWith("pkmoba:ars/")) {
            Minecraft arClient = Minecraft.getInstance();
            if (arClient.level == null) return true;
            if (world != arClient.level) { clear(); world = arClient.level; }
            String[] shot = payload.effectId().split("/");
            if (shot.length < 3) return true;
            try {
                int shotOwner = Integer.parseInt(shot[2]);
                String stage = shot[1];
                String shotPhase = stage.equals("hit") ? "shot_hit" : "shot" + stage;
                Vec3 shotPos = new Vec3(payload.x(), payload.y(), payload.z());
                Vec3 shotDir = new Vec3(payload.dx(), payload.dy(), payload.dz());
                if (!ExpansionAnchors.finite(shotPos) || !ExpansionAnchors.finite(shotDir)) return true;
                int shotLife = stage.equals("hit") ? 8 : 3;
                SCENES.put("shot:" + shotOwner + ":" + stage, new Scene("armarouge", 0, shotPhase, shotOwner,
                        shotPos, shotDir, shotLife, arClient.level.getGameTime()));
            } catch (NumberFormatException ignored) { /* cosmetic only */ }
            return true;
        }
        if (!payload.effectId().startsWith(PREFIX)) return false;
        String[] parts = payload.effectId().split("/");
        Minecraft mc = Minecraft.getInstance();
        if (parts.length != 5 || mc.level == null) return true;
        if (CUE_LOG.size() < 60 && CUE_LOG.add(parts[1] + ":" + parts[2] + ":" + parts[3])) {
            CLIP_LOG.info("cue in {} skill={} phase={} owner={}", parts[1], parts[2], parts[3], parts[4]);
        }
        if (world != mc.level) { clear(); world = mc.level; }
        try {
            int owner = Integer.parseInt(parts[4]);
            String phase = parts[3];
            if (phase.equals("clear")) {
                Entity entity = mc.level.getEntity(owner);
                if (entity != null) ExpansionWalls.remove(entity.getUUID());
                SCENES.values().removeIf(s -> s.owner == owner);
                return true;
            }
            var species = ExpansionRoster.resolve(parts[1]);
            int skill = Integer.parseInt(parts[2]);
            if (species == null || skill < 0 || skill > 3) return true;
            Vec3 pos = new Vec3(payload.x(), payload.y(), payload.z());
            Vec3 dir = new Vec3(payload.dx(), payload.dy(), payload.dz());
            if (!ExpansionAnchors.finite(pos) || !ExpansionAnchors.finite(dir)) return true;
            Entity entity = mc.level.getEntity(owner);
            var move = species.move(skill);
            if (phase.equals("end")) {
                SCENES.values().removeIf(s -> s.owner == owner && s.skill == skill
                        && (s.phase.equals("start") || s.phase.equals("release")));
                return true;
            }
            // 0.101 (m15488): the hypnosis badge is owned by the server's StatusBadges stream only.
            // Mirroring the cue here created a second icon on the victim's head, so it is gone.
            boolean melee = isMelee(species.id(), skill);
            if (phase.equals("quake")) HitFeedback.onEarthquake(pos);
            if (phase.equals("start")) animate(entity, species.id(), skill, "_windup");
            if (phase.equals("release")) animate(entity, species.id(), skill, "_cast");
            // Action beats are server-timed independently of a successful hit, so a whiff still
            // looks like a real attack and multi-hit moves can alternate limbs cleanly.
            // 0.81: the flip is timed to the actual airborne leap, not to the button press, so the
            // animation matches what the body is doing on screen.
        if (phase.equals("leap") && species.id().equals("blaziken") && skill == 3) {
                animate(entity, species.id(), skill, "_leap");
            }
            if (phase.equals("attack")) {
                animateAttack(entity, species.id(), skill, pos, dir);
                // 0.54: the two Blaziken dashes keep the borrowed run cycle going after the
                // impact pose, so the body does not freeze mid-dash.
                if (species.id().equals("blaziken") && skill == 2) {
                    schedule(entity, species.id(), "e_sustain", 5);
                    schedule(entity, species.id(), "e_sustain", 12);
                }
                // 0.56 Lucario Extreme Speed: keep the sprint stance for the whole dash instead of
                // dropping back to the locomotion cycle between the dash beats.
                if (species.id().equals("lucario") && skill == 2) {
                    schedule(entity, species.id(), "e_sustain", 5);
                    schedule(entity, species.id(), "e_sustain", 14);
                }
                return true;
            }
            if (phase.equals("melee") || (phase.equals("followup") && melee)) {
                animate(entity, species.id(), skill, "_melee");
                sound(pos, true);
            } else if (phase.equals("impact") || phase.equals("detonate")) {
                sound(pos, true);
                if (species.id().equals("gardevoir") && skill == 1)
                    HitFeedback.onEpicSlam(pos);
            }
            else if (phase.equals("release")) sound(pos, false);
            if (phase.equals("wall") && entity instanceof PokemonEntity pokemon) {
                ExpansionWalls.create(pokemon, pos, dir, move.radius(), move.duration());
            }
            int life = switch (phase) {
                // Effects linger noticeably longer than the cast itself: users want the skill to be
                // readable after the input, not a single-frame flash.
                case "start" -> Math.max(8, move.windup() + 8);
                // 0.78: Moonblast is launched once and animated client-side for the whole fall;
                // the Blaziken kit adds a thrown kick, its charge and the brand flash.
                case "launch" -> 27;
                case "kick" -> 16;
                // 0.80 蓄能焰袭 leaves one low fire-wall segment per dash step.
                // A fire wall has to stand on the field for about five seconds.
                case "trail" -> 105;
                // 0.124 Greninja: a dash streak lives a third of a second, not five.
                case "streak" -> 6;
                case "brand" -> 18;
                case "charge" -> 16;
                case "projectile", "lob" -> 9;
                case "melee", "followup" -> 14;
                case "impact", "blink", "heal" -> 24;
                // 0.54 Blaziken mark loop: the mark is re-cued at 2 Hz on a moving victim, the
                // warning lane during the charge every 4 ticks, the other two fire once.
                case "mark" -> 16;
                // 巨声: the arc wave is re-cued every other tick while it travels.
                case "sound" -> 12;
                case "detonate" -> 26;
                case "warn" -> 12;
                case "haste" -> 34;
                case "dance" -> 44;
                case "refresh" -> 22;
                // 0.55 Lucario: the aura ring is re-cued at 2 Hz while stacks are held (short life,
                // like the Blaziken mark), and a spend burst is a one-shot that outlives the cue.
                case "aura1", "aura2", "aura3" -> 14;
                // 0.86 Lucario: palm blast, sprint streaks and the aura detonation.
                case "palm" -> 16;
                case "meteor" -> 18;
                case "bolt" -> 14;
                case "dash" -> 26;
                case "buff" -> 18;
                case "blade" -> 20;
                // 0.96: the aurora screen has to stay up for the full five seconds of the guard.
                case "veil" -> 100;
                // 0.90 Alolan Ninetales: frost fan, channelled beam and drifting storm.
                case "wind" -> 24;
                case "whip" -> 10;
                case "beam" -> 10;
                // 0.94 Ninеtales: the crystal shard keeps its wake while it flies.
                case "shard" -> 14;
                case "blizzard" -> 30;
                case "rush" -> 12;
                case "grow" -> 14;
                case "spend0", "spend1", "spend2", "spend3" -> 26;
                case "spike" -> 32;
                case "quake" -> 22;
                case "frost" -> 18;
                case "wall" -> Math.max(40, move.duration() + 14);
                case "field" -> Math.max(140, move.duration() + 14);
                // 0.134 Krookodile: the digging trail is re-cued every tick, the quake charge
                // every other tick, and both ground effects outlive their cue.
                case "dig_move" -> 4;
                case "bite_step" -> 6;
                case "quake_charge" -> 8;
                case "quake_hit" -> 22;
                case "rage_start" -> 56;
                case "dig_down", "dig_up" -> 18;
                // 0.135 Armarouge: the psy lance, the flame trail and the cannon shell all ride the
                // same scene object and are refreshed every tick, so they only need a few frames of
                // afterglow; the rune and the pulses want to linger.
                case "psy", "flame" -> 4;
                case "cannon" -> 5;
                case "rune" -> 20;
                case "pulse1", "pulse2", "pulse3", "pulse4", "pulse5" -> 12;
                default -> phase.startsWith("shuriken") ? 5 : 14;
            };
            String key = owner + ":" + skill + ":" + phase;
            Scene previous = SCENES.get(key);
            if ((phase.equals("projectile") || phase.equals("lob") || phase.equals("dig_move")
                    || phase.equals("psy") || phase.equals("flame") || phase.equals("cannon")
                    || phase.equals("charge") || phase.equals("rise") || phase.equals("air")
                    || phase.startsWith("shuriken"))
                    && previous != null) {
                previous.from = previous.position(mc.level.getGameTime());
                previous.pos = pos;
                previous.updateTime = mc.level.getGameTime();
                previous.age = 0;
            } else {
                if (phase.equals("spike") || phase.equals("melee")) key += ":" + sequence++;
                SCENES.put(key, new Scene(species.id(), skill, phase, owner, pos, dir, life, mc.level.getGameTime()));
            }
            while (SCENES.size() > 200) SCENES.remove(SCENES.keySet().iterator().next());
        } catch (RuntimeException ignored) { /* malformed cues are cosmetic only */ }
        return true;
    }


    /**
     * Vanilla particle garnish. The textured sheets carry the art; these blocky Minecraft particles
     * add the chunky pixel sparkle the user asked for and make every skill read as busy even from
     * far away. They are cosmetic only and capped so a full arena cannot flood the client.
     */
    private static void mcParticles(Scene s, String style, Vec3 at, double radius, float fade) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || fade <= .05f || radius <= 0) return;
        long now = mc.level.getGameTime();
        java.util.Random rnd = new java.util.Random((long) (at.x * 7349 + at.y * 1571 + at.z * 4513) * 31 + now / 2);
        // 0.81: fire has its own textured sheets now, so particles are embers only.
        final boolean FLAME_EMBERS = "FLAME".equals(style);
        if (rnd.nextFloat() > (FLAME_EMBERS ? .40f : .62f)) return;
        int n = FLAME_EMBERS ? 2 + (int) Math.min(3, radius * .9)
                : 2 + (int) Math.min(7, radius * 2.2);
        for (int i = 0; i < n; i++) {
            double x = at.x + (rnd.nextDouble() - .5) * radius * 2.0;
            double y = at.y + (rnd.nextDouble() - .15) * radius * 1.7;
            double z = at.z + (rnd.nextDouble() - .5) * radius * 2.0;
            double vx = (rnd.nextDouble() - .5) * .12;
            double vy = .02 + rnd.nextDouble() * .12;
            double vz = (rnd.nextDouble() - .5) * .12;
            switch (style) {
                case "FLAME" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME, x, y, z, vx, vy, vz);
                case "ICE", "MIST" -> mc.level.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, vx * .5, -vy * .4, vz * .5);
                case "SHADOW", "DREAM" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.SOUL : ParticleTypes.SQUID_INK, x, y, z, vx, vy * .5, vz);
                case "SLUDGE" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.WITCH : ParticleTypes.POOF, x, y, z, vx, vy * .4, vz);
                case "HYPNOSIS" -> mc.level.addParticle(ParticleTypes.END_ROD, x, y, z, vx * .4, vy * .3, vz * .4);
                case "PSYCHIC", "MOON", "BLINK" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.END_ROD : ParticleTypes.ENCHANT, x, y, z, vx * .5, vy * .4, vz * .5);
                case "AURA" -> mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, vx, vy, vz);
                case "ROCK", "SAND", "QUAKE" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.CRIT : ParticleTypes.POOF, x, y, z, vx, vy * .6, vz);
                case "FIST", "RUSH", "BLADE", "FANG" -> mc.level.addParticle(ParticleTypes.CRIT, x, y, z, vx * 1.4, vy, vz * 1.4);
                case "WATER", "SHURIKEN" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.SPLASH : ParticleTypes.BUBBLE, x, y, z, vx * .6, vy * .6, vz * .6);
                case "DARK", "FLIP" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.SQUID_INK : ParticleTypes.CRIT, x, y, z, vx, vy * .5, vz);
                case "FLOWER", "GRASS" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.COMPOSTER, x, y, z, vx * .5, vy * .5, vz * .5);
                case "CLAW" -> mc.level.addParticle(ParticleTypes.CRIT, x, y, z, vx * 1.5, vy, vz * 1.5);
                case "PSYSHOCK" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.END_ROD
                        : (rnd.nextBoolean() ? ParticleTypes.ENCHANT : ParticleTypes.ELECTRIC_SPARK), x, y, z, vx * .6, vy * .5, vz * .6);
                case "FLAMECHARGE" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.FLAME
                        : (rnd.nextBoolean() ? ParticleTypes.SMALL_FLAME : ParticleTypes.LAVA), x, y, z, vx, vy * 1.2, vz);
                case "CANNON" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.FLAME
                        : (rnd.nextBoolean() ? ParticleTypes.LARGE_SMOKE : ParticleTypes.CRIT), x, y, z, vx * .8, vy, vz * .8);
                case "FORCE" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.ENCHANT
                        : (rnd.nextBoolean() ? ParticleTypes.END_ROD : ParticleTypes.PORTAL), x, y, z, vx * .5, vy * .6, vz * .5);
                case "DRAGON" -> mc.level.addParticle(rnd.nextBoolean() ? ParticleTypes.DRAGON_BREATH : ParticleTypes.CRIT, x, y, z, vx, vy * .5, vz);
                default -> mc.level.addParticle(ParticleTypes.END_ROD, x, y, z, vx, vy, vz);
            }
        }
    }

    private static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || context.matrixStack() == null || context.consumers() == null
                || context.camera() == null) return;
        Vec3 camera = context.camera().getPosition();
        double time = mc.level.getGameTime() + context.tickCounter().getGameTimeDeltaPartialTick(false);
        for (Scene s : SCENES.values()) {
            Entity owner = mc.level.getEntity(s.owner);
            if (owner == null || s.pos.distanceToSqr(camera) > 16384) continue;
            ExpansionAnchors.Set anchors = ExpansionAnchors.of(owner);
            double age = s.age + context.tickCounter().getGameTimeDeltaPartialTick(false);
            double progress = Math.min(1, age / s.life);
            float fade = (float) Math.min(1, (s.life - age) / 3.0);
            if (fade <= 0) continue;
            Vec3 dir = s.dir.lengthSqr() > 1E-5 ? s.dir.normalize() : owner.getLookAngle();
            ResourceLocation tex = material(s.id, s.skill);
            boolean melee = isMelee(s.id, s.skill);
            var move = ExpansionRoster.resolve(s.id);
            if (move == null) continue;
            double radius = move.move(s.skill).radius();
            String st = style(s.id, s.skill);
            // 0.67: Gengar runs purely on its own textured shapes — vanilla particle smoke and the
            // packet stream read as leftovers from the old kit, so no particle call happens for it.
            // Gengar and Gardevoir are drawn purely from their own textured sheets: vanilla
            // particles are what made the redesigned skills still read as the old kit.
            // 0.80 Blaziken opts back in to the chunky vanilla flame particles: they are the clearest
            // "this is fire" signal at any distance, and its own sheets now draw the shapes.
            // 0.100: every skill is drawn only from its own textured sheets - vanilla particles are gone.
            // 0.135 Armarouge: the textured layers are the art; these chunky particles are the
            // garnish that makes fire feel thick in the air and psy energy feel charged.
            if ("armarouge".equals(s.id)) {
                double pr = switch (s.phase) {
                    case "psy" -> .58;
                    case "cannon" -> .85;
                    case "flame" -> .95;
                    case "rune", "quake" -> 2.2;
                    case "pulse1", "pulse2", "pulse3", "pulse4", "pulse5" -> 1.7;
                    case "shot1", "shot2", "shot3" -> .35;
                    case "shot_hit", "impact", "release" -> 1.15;
                    default -> .8;
                };
                Vec3 pat = (s.phase.equals("psy") || s.phase.equals("cannon") || s.phase.startsWith("shot"))
                        ? s.pos : owner.position().add(0, 1.0, 0);
                mcParticles(s, st, pat, pr, fade);
            }
            if (dispatch(context, s, anchors, tex, time, age, progress, fade, dir, radius, owner.position())) continue;
            switch (s.phase) {
                case "start" -> {
                    Vec3 at = melee ? anchors.chest() : anchors.handPrimary();
                    double charge = .12 + .20 * progress;
                    pulse(context, at, charge, time, tex, fade);
                    orbit(context, at, .34 + .16 * progress, time, tex, fade * .9F, 6, .055);
                    groundRing(context, owner.position(), .55 + .55 * progress, .04, time, tex, fade * .65F, 2);
                    if (st.equals("FLAME")) flames(context, at.add(0, -.20, 0), .85, time, tex, fade, 7);
                    if (st.equals("PSYCHIC") || st.equals("MOON") || st.equals("AURA")) {
                        ringWave(context, at, .70 + .35 * progress, .03, time, tex, fade * .8F, 3);
                        orbit(context, at, .60, time, tex, fade, 9, .05);
                    }
                    if (st.equals("ICE") || st.equals("MIST")) shards(context, at, .48, time, tex, fade, 7, .30);
                    if (st.equals("SHADOW") || st.equals("SLUDGE")) {
                        pulse(context, at, charge * 1.7, -time * .07, tex, fade * .55F);
                        wisp(context, at, .55, time, tex, fade, 7);
                    }
                    if (st.equals("SAND") || st.equals("ROCK")) dust(context, at, .55, time, tex, fade);
                }
                case "release" -> {
                    Vec3 at = melee ? anchors.chest() : anchors.handPrimary();
                    pulse(context, at, .26 + .20 * progress, time, tex, fade);
                    groundRing(context, owner.position(), .8 + 1.5 * progress, .055, time, tex, fade, 2);
                    launchSparks(context, at, dir, time, tex, fade, 9);
                    signature(context, st, at, dir, time, tex, fade, progress, .55);
                }
                case "projectile", "lob" -> {
                    Vec3 p = s.position(time);
                    double size = s.phase.equals("lob") ? .46 : .34;
                    pulse(context, p, size, time, tex, fade);
                    trail(context, p, dir, time, tex, fade * .9F, 7, size * .8);
                    orbit(context, p, size * 1.75, time, tex, fade * .85F, 6, size * .28);
                    if (s.phase.equals("lob")) dust(context, p, size * 1.1, time, tex, fade * .8F);
                    signature(context, st, p, dir, time, tex, fade * .8F, progress, size * .8);
                }
                case "melee", "followup" -> {
                    // Impact contact: the body already animates, this is the physical hit flash.
                    pulse(context, s.pos, .18 + .24 * progress, time, tex, fade * .95F);
                    ringWave(context, s.pos, .55 + 1.05 * progress, .045, time, tex, fade * .9F, 2);
                    shards(context, s.pos, .35 + .55 * progress, time, tex, fade, 7, .26);
                    if (st.equals("FLAME")) flames(context, s.pos.add(0, -.25, 0), 1.1, time, tex, fade, 8);
                    if (st.equals("FIST") || st.equals("RUSH")) {
                        punchLines(context, s.pos, dir, time, tex, fade);
                    }
                }
                case "impact" -> {
                    pulse(context, s.pos, .38 + .55 * progress, time, tex, fade);
                    ringWave(context, s.pos, .75 + Math.min(2.6, radius) * progress, .06, time, tex, fade, 3);
                    shards(context, s.pos, .55 + Math.min(2.2, radius) * progress, time, tex, fade, 12, .34);
                    signature(context, st, s.pos, dir, time, tex, fade, progress, Math.min(1.6, radius * .7));
                }
                case "wall" -> {
                    Vec3 side = new Vec3(-dir.z, 0, dir.x);
                    if (side.lengthSqr() < .01) side = new Vec3(1, 0, 0);
                    side = side.normalize();
                    double grow = Math.min(1, age / 4);
                    for (int i = -5; i <= 5; i++) {
                        Vec3 at = s.pos.add(side.scale(i * radius / 5));
                        ExpansionSkillMeshes.crystal(context, at, .42,
                                (2.1 + (i % 2 == 0 ? .5 : 0)) * grow, i * .7 + time * .02, tex, fade);
                        if (st.equals("ICE")) ExpansionSkillMeshes.crystal(context, at.add(0, 1.5 * grow, 0), .22,
                                .95 * grow, -i * .5, tex, fade * .8F);
                    }
                    groundRing(context, s.pos, radius * 1.3, .06, time, tex, fade * .8F, 2);
                }
                case "spike" -> {
                    ExpansionSkillMeshes.crystal(context, s.pos, .55,
                            (2.0 + (s.owner % 3) * .22) * Math.min(1, age / 3), time * .015, tex, fade);
                    for (int i = 0; i < 6; i++) {
                        double a = i * 1.047 + time * .02;
                        ExpansionSkillMeshes.crystal(context, s.pos.add(Math.cos(a) * .55, 0, Math.sin(a) * .55),
                                .22, (1.1 + (i % 3) * .25) * Math.min(1, age / 3), a, tex, fade * .9F);
                    }
                    groundRing(context, s.pos, radius * 1.1, .05, time, tex, fade * .85F, 2);
                }
                case "field" -> {
                    Vec3 base = owner.position();
                    groundRing(context, base, radius, .06, time, tex, fade * .9F, 2);
                    for (int i = 0; i < 30; i++) {
                        double angle = time * .055 + i * 2.39996;
                        double r = radius * (.22 + (i % 8) / 11.0);
                        double h = st.equals("ICE") ? .25 + (1 - (time * .035 + i * .13) % 1) * 3.2 : .1 + (i % 6) * .40;
                        Vec3 p = base.add(Math.cos(angle) * r, h, Math.sin(angle) * r);
                        if (st.equals("ICE") || st.equals("MIST")) {
                            ExpansionSkillMeshes.crystal(context, p, .09, .26, angle, tex, fade * .9F);
                        } else if (st.equals("SAND")) {
                            ExpansionSkillMeshes.sphere(context, p, new Vec3(.16, .10, .16), angle, tex, fade * .7F, true);
                        } else {
                            ExpansionSkillMeshes.crystal(context, p, .07, .22, angle, tex, fade * .9F);
                        }
                    }
                    if (st.equals("ICE")) flames(context, base, 3.2, time, tex, fade * .45F, 10);
                    if (st.equals("SAND")) dust(context, base.add(0, 1.1, 0), 2.4, time, tex, fade * .55F);
                }
                case "buff" -> {
                    // Swords Dance: orbiting silver blades, a rising flare and a spinning ground halo.
                    Vec3 chest = anchors.chest();
                    groundRing(context, owner.position(), 1.25, .05, time, tex, fade * .9F, 3);
                    for (int i = 0; i < 6; i++) {
                        double angle = time * .12 + i * Math.PI / 3;
                        Vec3 at = chest.add(Math.cos(angle) * .95, -.15 + Math.sin(time * .09 + i) * .35,
                                Math.sin(angle) * .95);
                        ExpansionSkillMeshes.crystal(context, at, .085, 1.05, angle + time * .1, tex, fade);
                    }
                    orbit(context, chest, .85, time, tex, fade * .9F, 10, .06);
                    launchSparks(context, chest, new Vec3(0, 1, 0), time, tex, fade * .8F, 7);
                }
                case "heal" -> {
                    Vec3 destination = s.dir.lengthSqr() > .01 ? s.dir : anchors.chest();
                    for (int i = 0; i < 7; i++) {
                        double f = (time * .075 + i / 7.0) % 1;
                        Vec3 at = s.pos.lerp(destination, f);
                        ExpansionSkillMeshes.sphere(context, at, new Vec3(.10, .10, .10), time * .1, tex, fade, false);
                        ExpansionSkillMeshes.tube(context, at.subtract(destination.subtract(s.pos).normalize().scale(.5)),
                                at, .035, tex, fade * .7F);
                    }
                    ringWave(context, destination, .8, .04, time, tex, fade * .8F, 2);
                }
                case "blink" -> {
                    double gather = 1 - progress;
                    pulse(context, s.pos, .3 + .5 * gather, -time * .12, tex, fade);
                    pulse(context, s.dir, .3 + .5 * progress, time * .12, tex, fade);
                    for (int i = 0; i < 5; i++) {
                        double y = i * .5;
                        ringWave(context, s.pos.add(0, y, 0), .55 - i * .07, .03, time, tex, fade * .6F, 1);
                        ringWave(context, s.dir.add(0, y, 0), .55 - i * .07, .03, time, tex, fade * .6F, 1);
                    }
                }
                default -> { }
            }
        }
    }

    /**
     * Route one cue to that Pokemon's own visual class. Every one of the eight has bespoke
     * choreography per move and per phase, so no two skills can share a silhouette any more.
     */
    private static boolean dispatch(WorldRenderContext context, Scene s, ExpansionAnchors.Set anchors,
                                    ResourceLocation tex, double time, double age, double progress,
                                    float fade, Vec3 dir, double radius, Vec3 ownerPos) {
        Fx fx = new Fx(context, tex);
        // Abundance pass shared by every species: a moving packet stream + chunky pixel sparks +
        // a soft halo. Per-skill classes still own the composition, this only guarantees the effect
        // is bright, textured and visibly in motion at any distance.
        // 0.56: Lucario opts out — its owner asked for a crisp read of the body animation, and the
        // stream+halo pass was what smeared the aura skills into one blue blob at close range.
        // 0.100: no shared streak/spark/halo pass either; the per-species sheets are the whole look.
        FxView v = new FxView(fx, anchors, s.skill, s.phase, s.pos, dir, ownerPos,
                time, progress, age, radius, fade);
        switch (s.id) {
            case "lucario" -> LucarioFx.render(v);
            case "alolan_ninetales" -> NinetalesFx.render(v);
            case "blaziken" -> BlazikenFx.render(v);
            case "gengar" -> GengarFx.render(v);
            case "tyranitar" -> TyranitarFx.render(v);
            case "gardevoir" -> GardevoirFx.render(v);
            case "greninja" -> GreninjaFx.render(v);
            case "meowscarada" -> MeowscaradaFx.render(v);
            case "ceruledge" -> CeruledgeFx.render(v);
            case "hisuian_zoroark" -> ZoroarkFx.render(v);
            case "krookodile" -> KrookodileFx.render(v);
            case "armarouge" -> ArmarougeFx.render(v);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Style-specific extra burst layered on top of the generic composition. */
    private static void signature(WorldRenderContext c, String st, Vec3 at, Vec3 dir, double time,
                                  ResourceLocation t, float a, double progress, double scale) {
        switch (st) {
            case "FLAME" -> {
                flames(c, at.add(0, -.25, 0), 1.5 * scale + .6, time, t, a, 10);
                for (int i = 0; i < 8; i++) {
                    double ang = i * .785 + time * .08;
                    Vec3 p = at.add(Math.cos(ang) * scale * .9, Math.sin(i * 1.3) * scale * .5, Math.sin(ang) * scale * .9);
                    ExpansionSkillMeshes.sphere(c, p, new Vec3(.10, .14, .10), ang, t, a * .9F, true);
                }
            }
            case "ICE", "MIST" -> {
                shards(c, at, scale, time, t, a, 10, .42);
                for (int i = 0; i < 5; i++) {
                    double ang = -Math.PI / 2 + i * .5;
                    Vec3 p = at.add(dir.scale(scale * .6)).add(Math.cos(ang) * .45, Math.sin(ang) * .45, 0);
                    ExpansionSkillMeshes.crystal(c, p, .13, .55, ang, t, a * .9F);
                }
            }
            case "ROCK", "FANG" -> {
                for (int i = 0; i < 7; i++) {
                    double ang = i * 2.39996 + time * .03;
                    Vec3 p = at.add(Math.cos(ang) * scale * .8, .2 + (1 - progress) * 2.2 + (i % 3) * .35, Math.sin(ang) * scale * .8);
                    ExpansionSkillMeshes.sphere(c, p, new Vec3(.30, .38, .28), ang, t, a, true);
                }
                groundRing(c, at.add(0, -.35, 0), scale * 1.3, .07, time, t, a * .9F, 2);
            }
            case "SAND" -> dust(c, at, scale * 1.5, time, t, a * .85F);
            case "SHADOW", "SLUDGE", "DREAM" -> {
                wisp(c, at, scale * 1.2, time, t, a, 12);
                pulse(c, at, scale * .75, -time * .06, t, a * .7F);
                if (st.equals("SLUDGE")) {
                    for (int i = 0; i < 9; i++) {
                        double ang = i * 2.39996;
                        Vec3 p = at.add(Math.cos(ang) * scale, -.3 + (i % 4) * .22, Math.sin(ang) * scale);
                        ExpansionSkillMeshes.sphere(c, p, new Vec3(.12, .16, .12), ang, t, a * .85F, true);
                    }
                }
            }
            case "PSYCHIC", "AURA" -> {
                ringWave(c, at, scale * 1.4, .045, time, t, a, 3);
                orbit(c, at, scale * 1.2, time, t, a, 10, .08);
                for (int i = 0; i < 4; i++) {
                    ExpansionSkillMeshes.ring(c, at.add(0, -.2 + i * .35, 0), scale * (.8 + i * .22), .035, t, a * .8F);
                }
            }
            case "MOON" -> {
                crescent(c, at, scale * 1.2, time, t, a);
                orbit(c, at, scale * 1.1, time, t, a * .9F, 9, .07);
                ringWave(c, at, scale * 1.5, .04, -time, t, a * .7F, 2);
            }
            case "BLADE" -> {
                for (int i = 0; i < 8; i++) {
                    double ang = i * .785 + time * .25;
                    Vec3 p = at.add(Math.cos(ang) * scale, -.2 + Math.sin(time * .12 + i) * .3, Math.sin(ang) * scale);
                    ExpansionSkillMeshes.crystal(c, p, .075, scale * 1.1, ang, t, a);
                }
                ringWave(c, at, scale * 1.1, .05, time, t, a * .8F, 2);
            }
            case "DRAGON", "QUAKE" -> {
                for (int i = -2; i <= 2; i++) {
                    Vec3 p = at.add(dir.scale(scale * .45)).add(new Vec3(-dir.z, 0, dir.x).normalize().scale(i * .30));
                    ExpansionSkillMeshes.crystal(c, p, .12, scale * 1.3, i * .4 + time * .05, t, a);
                }
                groundRing(c, at.add(0, -.4, 0), scale * 1.6, .07, time, t, a * .9F, 2);
            }
            default -> orbit(c, at, scale, time, t, a, 8, .07);
        }
    }

    // ---- building blocks -------------------------------------------------------------------

    private static void pulse(WorldRenderContext c, Vec3 p, double size, double time,
                              ResourceLocation t, float a) {
        ExpansionSkillMeshes.sphere(c, p, new Vec3(size, size, size), time * .05, t, a, false);
        ExpansionSkillMeshes.sphere(c, p, new Vec3(size * 1.55, size * 1.55, size * 1.55), -time * .04, t, a * .45F, false);
        ExpansionSkillMeshes.sphere(c, p, new Vec3(size * .55, size * .55, size * .55), time * .13,
                t, Math.min(1F, a * 1.6F), false);
    }

    private static void shards(WorldRenderContext c, Vec3 p, double spread, double time,
                               ResourceLocation t, float a, int count, double size) {
        for (int i = 0; i < count; i++) {
            double ang = i * 2.39996 + time * .02;
            Vec3 at = p.add(Math.cos(ang) * spread, Math.sin(i * 1.7) * spread * .7, Math.sin(ang) * spread);
            ExpansionSkillMeshes.crystal(c, at, size * .30, size, ang + time * .05, t, a);
        }
    }

    private static void ringWave(WorldRenderContext c, Vec3 p, double radius, double thickness,
                                 double time, ResourceLocation t, float a, int layers) {
        for (int i = 0; i < layers; i++) {
            double r = radius * (.62 + .34 * i + .08 * Math.sin(time * .2 + i));
            ExpansionSkillMeshes.ring(c, p.add(0, .07 + i * .30, 0), r, thickness, t, a * (1 - i * .16F));
        }
    }

    private static void groundRing(WorldRenderContext c, Vec3 base, double radius, double thickness,
                                   double time, ResourceLocation t, float a, int layers) {
        for (int i = 0; i < layers; i++) {
            ExpansionSkillMeshes.ring(c, base.add(0, .06 + i * .05, 0), radius * (1 - i * .18), thickness,
                    t, a * (1 - i * .22F));
        }
    }

    private static void orbit(WorldRenderContext c, Vec3 p, double radius, double time,
                              ResourceLocation t, float a, int count, double size) {
        for (int i = 0; i < count; i++) {
            double ang = time * .16 + i * Math.PI * 2 / count;
            double y = Math.sin(time * .09 + i * 1.3) * radius * .5;
            Vec3 at = p.add(Math.cos(ang) * radius, y, Math.sin(ang) * radius);
            ExpansionSkillMeshes.sphere(c, at, new Vec3(size, size, size), ang, t, a, false);
            Vec3 prev = p.add(Math.cos(ang - .35) * radius,
                    Math.sin(time * .09 + i * 1.3 - .3) * radius * .5, Math.sin(ang - .35) * radius);
            ExpansionSkillMeshes.tube(c, prev, at, size * .45, t, a * .55F);
        }
    }

    private static void trail(WorldRenderContext c, Vec3 p, Vec3 dir, double time,
                              ResourceLocation t, float a, int count, double size) {
        for (int i = 0; i < count; i++) {
            double f = i / (double) count;
            Vec3 at = p.subtract(dir.scale(f * 1.55));
            double s = size * (1 - f * .75);
            ExpansionSkillMeshes.sphere(c, at, new Vec3(s, s, s), time * .1 + i * .4, t, (float) (a * (1 - f * .6)), true);
        }
        ExpansionSkillMeshes.tube(c, p.subtract(dir.scale(1.5)), p, size * .3, t, a * .5F);
    }

    private static void launchSparks(WorldRenderContext c, Vec3 at, Vec3 dir, double time,
                                     ResourceLocation t, float a, int count) {
        for (int i = 0; i < count; i++) {
            double ang = i * .785 + time * .06;
            Vec3 side = new Vec3(-dir.z, 0, dir.x);
            if (side.lengthSqr() < .01) side = new Vec3(1, 0, 0);
            side = side.normalize();
            Vec3 p = at.add(dir.scale(.25 + (i % 4) * .22)).add(side.scale(Math.cos(ang) * .28))
                    .add(0, Math.sin(ang) * .28, 0);
            ExpansionSkillMeshes.crystal(c, p, .045, .30 + (i % 3) * .1, ang, t, a);
        }
    }

    private static void flames(WorldRenderContext c, Vec3 base, double height, double time,
                               ResourceLocation t, float a, int count) {
        for (int i = 0; i < count; i++) {
            double ang = i * 2.39996 + time * .05;
            double f = (i % 5) / 5.0;
            Vec3 at = base.add(Math.cos(ang) * (.20 + f * .30), f * height, Math.sin(ang) * (.20 + f * .30));
            ExpansionSkillMeshes.crystal(c, at, .10 + .05 * (i % 3), height * (.32 + .12 * (i % 4)),
                    ang + time * .12, t, (float) (a * (1 - f * .4)));
        }
    }

    private static void wisp(WorldRenderContext c, Vec3 p, double radius, double time,
                             ResourceLocation t, float a, int count) {
        for (int i = 0; i < count; i++) {
            double ang = i * 2.39996 + time * .10;
            double h = Math.sin(time * .07 + i) * radius * .8;
            Vec3 at = p.add(Math.cos(ang) * radius, h, Math.sin(ang) * radius);
            ExpansionSkillMeshes.sphere(c, at, new Vec3(.11, .11, .11), -time * .1, t, a * .9F, true);
            ExpansionSkillMeshes.tube(c, at.add(-Math.cos(ang) * .5, -.18, -Math.sin(ang) * .5), at, .05, t, a * .55F);
        }
    }

    private static void dust(WorldRenderContext c, Vec3 base, double radius, double time,
                             ResourceLocation t, float a) {
        for (int i = 0; i < 14; i++) {
            double ang = i * 2.39996 + time * .05;
            double r = radius * (.4 + (i % 5) / 8.0);
            Vec3 p = base.add(Math.cos(ang) * r, .15 + (i % 3) * .18, Math.sin(ang) * r);
            ExpansionSkillMeshes.sphere(c, p, new Vec3(.15, .09, .15), ang, t, a * .8F, true);
        }
    }

    private static void punchLines(WorldRenderContext c, Vec3 p, Vec3 dir, double time,
                                   ResourceLocation t, float a) {
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        if (side.lengthSqr() < .01) side = new Vec3(1, 0, 0);
        side = side.normalize();
        for (int i = -2; i <= 2; i++) {
            Vec3 start = p.subtract(dir.scale(.6)).add(side.scale(i * .16));
            ExpansionSkillMeshes.tube(c, start, p.add(side.scale(i * .10)), .035, t, a * .85F);
        }
        for (int i = 0; i < 4; i++) {
            double ang = time * .2 + i * 1.57;
            ExpansionSkillMeshes.crystal(c, p.add(Math.cos(ang) * .5, Math.sin(ang) * .5, 0), .05, .30, ang, t, a);
        }
    }

    private static void crescent(WorldRenderContext c, Vec3 p, double radius, double time,
                                 ResourceLocation t, float a) {
        int segments = 12;
        Vec3 previous = null;
        for (int i = 0; i <= segments; i++) {
            double ang = Math.PI * (i / (double) segments) - Math.PI * .5 + time * .15;
            Vec3 at = p.add(Math.cos(ang) * radius, Math.sin(ang) * radius * .9, Math.sin(ang * 2) * .15);
            if (previous != null) ExpansionSkillMeshes.tube(c, previous, at, .075, t, a);
            previous = at;
        }
    }

    private static ResourceLocation material(String species, int skill) {
        return ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/expansion/" + species + "_" + "qwer".charAt(skill) + "_surface.png");
    }
    private static void animate(Entity entity, String species, int skill, String suffix) {
        if (!(entity instanceof PokemonEntity pokemon)) return;
        if (!matches(pokemon, species)) return;
        playClip(pokemon, species, "qwer".charAt(skill) + suffix);
    }

    /** Single place that turns a clip name into a Cobblemon animation command. */
    private static final java.util.Set<String> LOGGED_CLIPS = new java.util.HashSet<>();
    private static final java.util.Set<String> CUE_LOG = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final org.slf4j.Logger CLIP_LOG =
            org.slf4j.LoggerFactory.getLogger("pkmoba/clip");

    /** Regional forms spawn as their base species, so compare through the registry alias. */
    private static boolean matches(PokemonEntity pokemon, String species) {
        String path = pokemon.getPokemon().getSpecies().getResourceIdentifier().getPath();
        return path.equals(species)
                || path.equals(com.pkmoba.pokemon.SpeciesRegistry.cobblePath(species));
    }

    private static void playClip(PokemonEntity pokemon, String species, String clip) {
        try {
            ((PosableEntity) pokemon).playAnimation(
                    "q.bedrock_primary('pkmoba_" + species + "', '" + clip + "', q.curve('one'))", List.of());
            if (LOGGED_CLIPS.size() < 40 && LOGGED_CLIPS.add(species + ":" + clip)) {
                String group = "pkmoba_" + species;
                CLIP_LOG.info("clip {} {} -> {} (repo={}/{})", species, clip, pokemon.getId(),
                        com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation
                                .BedrockAnimationRepository.INSTANCE
                                .tryGetAnimation(group, clip) != null,
                        com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation
                                .BedrockAnimationRepository.INSTANCE
                                .tryGetAnimation(group, "animation." + group + "." + clip) != null);
            }
        } catch (RuntimeException error) {
            if (LOGGED_CLIPS.size() < 40 && LOGGED_CLIPS.add("!" + species + ":" + clip)) {
                CLIP_LOG.warn("clip {}/{} failed: {}", species, clip, error.toString());
            }
        }
    }

    /** Per-owner beat counter so alternating limbs (e.g. Double Kick) really alternate. */
    private static final Map<Integer, Integer> BEAT = new java.util.HashMap<>();

    /** Attack beat cues: play the real body attack even when the hit misses. */
    private static void animateAttack(Entity entity, String species, int skill, Vec3 pos, Vec3 dir) {
        if (!(entity instanceof PokemonEntity pokemon)) return;
        if (!matches(pokemon, species)) return;
        int beat = BEAT.merge(pokemon.getId(), 1, Integer::sum);
        String clip = switch (species) {
            // Blaze Kick -> spinning high kick; Double Kick -> strictly alternating legs;
            // Flame Charge -> running lean; Flare Blitz -> full-body shoulder charge.
            case "blaziken" -> switch (skill) {
                case 0 -> "q_melee";
                case 1 -> beat % 2 == 0 ? "w_kick_left" : "w_kick_right";
                case 2 -> "e_melee";
                default -> "r_melee";
            };
            // Bone Rush -> alternating club sweeps (left copy is the mirrored clip), Extreme Speed
            // -> sprint lean, Close Combat -> the compressed punch.
            case "lucario" -> switch (skill) {
                case 0 -> "q_melee";
                case 1 -> beat % 2 == 0 ? "w_melee2" : "w_melee";
                case 2 -> "e_melee";
                default -> "r_melee";
            };
            // 0.133.6 Hisuian Zoroark: alternating single-paw rakes for the claw, and the
            // per-skill finisher for everything else.
            case "hisuian_zoroark" -> switch (skill) {
                case 0 -> beat % 2 == 0 ? "q_claw_r" : "q_claw_l";
                default -> "qwer".charAt(skill) + "_melee";
            };
            // 0.135 Armarouge: right arm, left arm, then both cannons for the heavy bolt.
            case "armarouge" -> switch (skill) {
                case 0 -> "q_fire_l";
                case 1 -> "q_fire_r";
                case 2 -> "q_fire_heavy";
                default -> "q_fire_heavy";
            };
            // 0.134 Krookodile: the punch chain is three different bites, the skills are their own.
            case "krookodile" -> switch (skill) {
                case 0 -> switch (beat % 3) {
                    case 0 -> "q_bite_l";
                    case 1 -> "q_bite_r";
                    default -> "q_bite_heavy";
                };
                default -> "qwer".charAt(skill) + "_melee";
            };
            // 0.133.7 Ceruledge raises both blades on every punch; the real arm rotation comes
            // from the baked Cobblemon clips, not from a hand-authored pose.
            case "ceruledge" -> switch (skill) {
                case 0 -> beat % 2 == 0 ? "q_swing_r" : "q_swing_l";
                case 1 -> beat % 2 == 0 ? "q_swing_r" : "q_swing_l";
                case 2 -> "q_swing_r";
                default -> "q_swing_l";
            };
            default -> "qwer".charAt(skill) + "_melee";
        };
        playClip(pokemon, species, clip);
        // One swing is over in a few ticks, which reads as a twitch. Both duelists keep the
        // motion alive by replaying the same clip twice more while the swing is still on screen.
        if (species.equals("hisuian_zoroark") || species.equals("ceruledge")
                || species.equals("krookodile")
                || species.equals("armarouge")) {
            schedule(pokemon, species, clip, 5);
            schedule(pokemon, species, clip, 10);
        }
    }

    private static void sound(Vec3 point, boolean impact) {
        // 0.79: the user asked for every skill cast sound the player triggers to be removed.
    }
    private static final class Scene {
        final String id, phase;
        final int skill, owner, life;
        Vec3 pos, from;
        final Vec3 dir;
        long updateTime;
        int age;
        Scene(String id, int skill, String phase, int owner, Vec3 pos, Vec3 dir, int life, long now) {
            this.id = id; this.skill = skill; this.phase = phase; this.owner = owner;
            this.pos = pos; this.from = pos; this.dir = dir; this.life = life; this.updateTime = now;
        }
        Vec3 position(double time) { return from.lerp(pos, Math.clamp(time - updateTime, 0, 1)); }
    }
}
