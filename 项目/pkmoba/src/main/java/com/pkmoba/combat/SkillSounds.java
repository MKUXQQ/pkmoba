package com.pkmoba.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Per-skill audio (0.102, m15731).
 *
 * <p>There is no redistributable Pokemon sound library in the mod, so every move is voiced with a
 * vanilla sound event chosen to match its element and shape: fire skills use blaze/ghast fire,
 * electric skills use lightning, ice skills use freeze/glass, rock skills use stone/deepslate,
 * psychic skills use amethyst/beacon. Pitch and volume are nudged per species and per slot so two
 * skills never sound identical, and each cast additionally plays the same voice again on impact.
 */
public final class SkillSounds {
    private SkillSounds() {
    }

    private static void play(ServerLevel level, LivingEntity at, SoundEvent sound, float volume, float pitch) {
        if (level == null || sound == null || at == null) return;
        level.playSound(null, at.getX(), at.getY() + 0.6, at.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
    }

    /** Cast sound, fired the moment the skill is accepted by the server. */
    public static void cast(ServerLevel level, LivingEntity entity, String species, int skill) {
        if (species == null) return;
        SoundEvent event;
        float pitch;
        switch (species) {
            case "blaziken" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.BLAZE_SHOOT;
                    case 1 -> SoundEvents.PLAYER_ATTACK_SWEEP;
                    case 2 -> SoundEvents.BLAZE_BURN;
                    default -> SoundEvents.GHAST_SHOOT;
                };
                pitch = 1.15F;
            }
            case "tyranitar" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.STONE_BREAK;
                    case 1 -> SoundEvents.PLAYER_ATTACK_STRONG;
                    case 2 -> SoundEvents.SAND_BREAK;
                    default -> SoundEvents.RAVAGER_STEP;
                };
                pitch = 0.72F;
            }
            case "gengar" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.SOUL_ESCAPE.value();
                    case 1 -> SoundEvents.SLIME_ATTACK;
                    case 2 -> SoundEvents.ENDERMAN_SCREAM;
                    default -> SoundEvents.SOUL_SAND_STEP;
                };
                pitch = 0.85F;
            }
            case "gardevoir" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.AMETHYST_BLOCK_CHIME;
                    case 1 -> SoundEvents.BEACON_ACTIVATE;
                    case 2 -> SoundEvents.ENDERMAN_TELEPORT;
                    default -> SoundEvents.AMETHYST_BLOCK_RESONATE;
                };
                pitch = 1.3F;
            }
            case "lucario" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.PLAYER_ATTACK_SWEEP;
                    case 1 -> SoundEvents.PLAYER_ATTACK_STRONG;
                    case 2 -> SoundEvents.PLAYER_ATTACK_CRIT;
                    default -> SoundEvents.PLAYER_ATTACK_SWEEP;
                };
                pitch = 1.05F;
            }
            case "alolan_ninetales" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.PLAYER_HURT_FREEZE;
                    case 1 -> SoundEvents.AMETHYST_BLOCK_CHIME;
                    case 2 -> SoundEvents.GLASS_BREAK;
                    default -> SoundEvents.LIGHTNING_BOLT_IMPACT;
                };
                pitch = 1.2F;
            }
            case "pikachu" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.LIGHTNING_BOLT_IMPACT;
                    case 1 -> SoundEvents.LIGHTNING_BOLT_THUNDER;
                    case 2 -> SoundEvents.PLAYER_ATTACK_CRIT;
                    default -> SoundEvents.LIGHTNING_BOLT_THUNDER;
                };
                pitch = 1.35F;
            }
            case "venusaur" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.PLAYER_ATTACK_SWEEP;
                    case 1 -> SoundEvents.GRASS_BREAK;
                    case 2 -> SoundEvents.BEACON_ACTIVATE;
                    default -> SoundEvents.BEACON_POWER_SELECT;
                };
                pitch = 0.95F;
            }
            case "blastoise" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.GENERIC_SPLASH;
                    case 1 -> SoundEvents.TRIDENT_RETURN;
                    case 2 -> SoundEvents.GENERIC_SPLASH;
                    default -> SoundEvents.TRIDENT_THUNDER.value();
                };
                pitch = 1.0F;
            }
            case "charizard" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.BLAZE_SHOOT;
                    case 1 -> SoundEvents.GHAST_SHOOT;
                    case 2 -> SoundEvents.GHAST_SHOOT;
                    default -> SoundEvents.GENERIC_EXPLODE.value();
                };
                pitch = 0.9F;
            }
            case "greninja" -> {
                // 0.124: a thrown water blade, a shadow lunge, a dark burst and a pressure hose.
                event = switch (skill) {
                    case 0 -> SoundEvents.TRIDENT_RETURN;
                    case 1 -> SoundEvents.PLAYER_ATTACK_SWEEP;
                    case 2 -> SoundEvents.SOUL_ESCAPE.value();
                    default -> SoundEvents.EVOKER_PREPARE_ATTACK;
                };
                pitch = 1.05F;
            }
            case "ceruledge" -> {
                // 0.132: a psychic blade hum, a shadow slip, twin flame swings and a choral dance.
                event = switch (skill) {
                    case 0 -> SoundEvents.AMETHYST_BLOCK_RESONATE;
                    case 1 -> SoundEvents.SOUL_ESCAPE.value();
                    case 2 -> SoundEvents.BLAZE_SHOOT;
                    default -> SoundEvents.BEACON_ACTIVATE;
                };
                pitch = skill == 3 ? .85F : 1.18F;
            }
            case "krookodile" -> {
                // 0.134: a throaty gator growl for the bite, wet digging for the burrow, a low
                // rumble for the quake and the loudest roar in the kit for Outrage.
                event = switch (skill) {
                    case 0 -> SoundEvents.RAVAGER_ATTACK;
                    case 1 -> SoundEvents.GRAVEL_STEP;
                    case 2 -> SoundEvents.RAVAGER_ROAR;
                    default -> SoundEvents.ENDER_DRAGON_GROWL;
                };
                pitch = switch (skill) {
                    case 0 -> .82F;
                    case 1 -> 1.1F;
                    case 2 -> .68F;
                    default -> .72F;
                };
            }
            case "armarouge" -> {
                // 0.135: an amethyst chime for the psyshock, a blaze shot for the flame charge, the
                // heaviest roar in the kit for the twin cannon and a warden pulse for the field.
                event = switch (skill) {
                    case 0 -> SoundEvents.AMETHYST_BLOCK_CHIME;
                    case 1 -> SoundEvents.BLAZE_SHOOT;
                    case 2 -> SoundEvents.RAVAGER_ROAR;
                    default -> SoundEvents.WARDEN_SONIC_BOOM;
                };
                pitch = switch (skill) {
                    case 0 -> 1.12F;
                    case 1 -> 1.04F;
                    case 2 -> .78F;
                    default -> .82F;
                };
            }
            case "hisuian_zoroark" -> {
                // 0.133: a claw whistle, a grudge moan, a shulker orb and a choral illusion split.
                event = switch (skill) {
                    case 0 -> SoundEvents.PLAYER_ATTACK_SWEEP;
                    case 1 -> SoundEvents.SOUL_ESCAPE.value();
                    case 2 -> SoundEvents.SHULKER_SHOOT;
                    default -> SoundEvents.EVOKER_CAST_SPELL;
                };
                pitch = switch (skill) {
                    case 0 -> 1.24F;
                    case 1 -> .82F;
                    case 2 -> 1.08F;
                    default -> .9F;
                };
            }
            case "meowscarada" -> {
                // 0.130: a leaf whistle, a dash whoosh, a chime for the thrown flower and a low
                // gathering hum for the petal storm, so no two buttons share a voice.
                event = switch (skill) {
                    case 0 -> SoundEvents.GRASS_STEP;
                    case 1 -> SoundEvents.PLAYER_ATTACK_SWEEP;
                    case 2 -> SoundEvents.AMETHYST_BLOCK_CHIME;
                    default -> SoundEvents.ENCHANTMENT_TABLE_USE;
                };
                pitch = skill == 3 ? .92F : 1.12F;
            }
            default -> {
                event = SoundEvents.ENCHANTMENT_TABLE_USE;
                pitch = 1F;
            }
        }
        play(level, entity, event, 1.1F, pitch);
    }

    /**
     * 0.105 (m15933): Gengar's Sludge Bomb gets its own voice instead of the shared ghost tone -
     * a wet launch, a heavy splat on landing and a bubbling pool afterwards. The sound is played at
     * the projectile / pool position with a tall volume so the 3D attenuation still tells the
     * player the direction and distance even from the god-view camera.
     */
    private static void at(ServerLevel level, Vec3 p, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, p.x, p.y, p.z, sound, net.minecraft.sounds.SoundSource.PLAYERS, volume, pitch);
    }

    public static void sludgeLaunch(ServerLevel level, Vec3 at) {
        at(level, at, SoundEvents.SLIME_BLOCK_PLACE, 2.2F, 0.72F);
        at(level, at, SoundEvents.SOUL_ESCAPE.value(), 1.2F, 0.6F);
    }

    public static void sludgeSplat(ServerLevel level, Vec3 at) {
        at(level, at, SoundEvents.SLIME_BLOCK_BREAK, 2.6F, 0.55F);
        at(level, at, SoundEvents.SLIME_SQUISH, 2.4F, 0.5F);
        at(level, at, SoundEvents.GENERIC_SPLASH, 1.8F, 0.62F);
    }

    public static void sludgeBubble(ServerLevel level, Vec3 at) {
        at(level, at, SoundEvents.SLIME_SQUISH_SMALL, 1.9F, 0.42F);
        at(level, at, SoundEvents.SOUL_ESCAPE.value(), 0.9F, 0.45F);
    }

    /** Impact sound, fired when the move's hit cue resolves. */
    public static void impact(ServerLevel level, LivingEntity entity, String species, int skill) {
        if (species == null) return;
        SoundEvent event;
        float pitch;
        switch (species) {
            case "tyranitar" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.STONE_BREAK;
                    case 1 -> SoundEvents.PLAYER_ATTACK_STRONG;
                    case 2 -> SoundEvents.SAND_BREAK;
                    default -> SoundEvents.GENERIC_EXPLODE.value();
                };
                pitch = 0.6F;
            }
            case "blaziken" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.GHAST_SHOOT;
                    case 1 -> SoundEvents.PLAYER_ATTACK_CRIT;
                    default -> SoundEvents.GENERIC_EXPLODE.value();
                };
                pitch = 0.95F;
            }
            case "gengar" -> {
                event = switch (skill) {
                    case 1 -> SoundEvents.SLIME_BLOCK_BREAK;
                    default -> SoundEvents.SOUL_ESCAPE.value();
                };
                pitch = 0.8F;
            }
            case "rock" -> {
                event = SoundEvents.STONE_BREAK;
                pitch = 0.7F;
            }
            case "greninja" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.GENERIC_SPLASH;
                    case 1 -> SoundEvents.PLAYER_ATTACK_SWEEP;
                    case 2 -> SoundEvents.SOUL_ESCAPE.value();
                    default -> SoundEvents.EVOKER_CAST_SPELL;
                };
                pitch = 1.1F;
            }
            case "ceruledge" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.AMETHYST_BLOCK_BREAK;
                    case 1 -> SoundEvents.PLAYER_ATTACK_SWEEP;
                    case 2 -> SoundEvents.PLAYER_ATTACK_CRIT;
                    default -> SoundEvents.BEACON_ACTIVATE;
                };
                pitch = skill == 2 ? 1.22F : 1.05F;
            }
            case "krookodile" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.PLAYER_ATTACK_STRONG;
                    case 1 -> SoundEvents.GENERIC_EXPLODE.value();
                    case 2 -> SoundEvents.RAVAGER_ATTACK;
                    default -> SoundEvents.RAVAGER_ATTACK;
                };
                pitch = switch (skill) {
                    case 0 -> .78F;
                    case 1 -> .92F;
                    case 2 -> .62F;
                    default -> .8F;
                };
            }
            case "armarouge" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.SHULKER_BULLET_HIT;
                    case 1 -> SoundEvents.GENERIC_EXPLODE.value();
                    case 2 -> SoundEvents.GENERIC_EXPLODE.value();
                    default -> SoundEvents.WARDEN_SONIC_CHARGE;
                };
                pitch = switch (skill) {
                    case 0 -> 1.18F;
                    case 1 -> .92F;
                    case 2 -> .72F;
                    default -> .95F;
                };
            }
            case "hisuian_zoroark" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.PLAYER_ATTACK_CRIT;
                    case 1 -> SoundEvents.SOUL_ESCAPE.value();
                    case 2 -> SoundEvents.SHULKER_BULLET_HIT;
                    default -> SoundEvents.GENERIC_EXPLODE.value();
                };
                pitch = switch (skill) {
                    case 0 -> 1.28F;
                    case 1 -> .88F;
                    case 2 -> 1.02F;
                    default -> .78F;
                };
            }
            case "meowscarada" -> {
                event = switch (skill) {
                    case 0 -> SoundEvents.GRASS_BREAK;
                    case 1 -> SoundEvents.PLAYER_ATTACK_SWEEP;
                    case 2 -> SoundEvents.PLAYER_ATTACK_CRIT;
                    default -> SoundEvents.GRASS_BREAK;
                };
                pitch = skill == 2 ? 1.18F : 1.05F;
            }
            default -> {
                event = SoundEvents.AMETHYST_BLOCK_CHIME;
                pitch = 1.15F;
            }
        }
        play(level, entity, event, 1.0F, pitch);
    }
}
