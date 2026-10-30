package com.pkmoba.client;

import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.client.render.models.blockbench.animation.PrimaryAnimation;
import com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation.BedrockActiveAnimation;
import com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation.BedrockAnimationRepository;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.combat.Skill;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.Minecraft;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Holds a tailored cast pose only while a Venusaur Q/W/E/R channel is active. */
public final class VenusaurSkillAnimator {
    private static final String GROUP = "pkmoba_venusaur";
    private static final String ANIMATION = "animation.pkmoba_venusaur.cast";
    private static final String RAZOR_ANIMATION = "animation.pkmoba_venusaur.razor_leaf";
    private static UUID lastEntity;
    private static int lastChannel;
    private static PosableState ownedState;
    private static PrimaryAnimation ownedAnimation;
    private static boolean disabled;
    private static long castStarted;

    private VenusaurSkillAnimator() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(VenusaurSkillAnimator::tick);
    }

    private static void tick(Minecraft mc) {
        if (disabled) return;
        try {
            int id = ClientState.getCharizardId();
            Entity found = mc.level == null || id < 0 ? null : mc.level.getEntity(id);
            PokemonEntity pokemon = found instanceof PokemonEntity p && Skill.isVenusaur(p) ? p : null;
            int channel = pokemon == null ? 0 : ClientState.getChannel();
            if (pokemon == null || channel < 7 || channel > 10) {
                release();
                lastEntity = pokemon == null ? null : pokemon.getUUID();
                lastChannel = 0;
                return;
            }
            if (!pokemon.getUUID().equals(lastEntity) || channel != lastChannel) release();
            if (!(pokemon.getDelegate() instanceof PosableState state)) return;
            if (state != ownedState) {
                release();
                var clip = BedrockAnimationRepository.INSTANCE.getAnimationOrNull(GROUP, channel == 8 ? RAZOR_ANIMATION : ANIMATION);
                if (clip == null) return;
                BedrockActiveAnimation held = new BedrockActiveAnimation(clip) {
                    @Override public float getDuration() { return Float.POSITIVE_INFINITY; }
                };
                ownedState = state;
                castStarted = System.nanoTime();
                ownedAnimation = new PrimaryAnimation(held, progress -> castWeight(channel), Set.of(), false);
                state.addPrimaryAnimation(ownedAnimation);
            } else if (state.getPrimaryAnimation() != ownedAnimation) {
                state.setPrimaryAnimation(ownedAnimation);
            }
            lastEntity = pokemon.getUUID();
            lastChannel = channel;
        } catch (Throwable t) {
            release();
            disabled = true;
            org.slf4j.LoggerFactory.getLogger("pkmoba").warn("[pkmoba] 妙蛙花施法动画已停用：{}", t.toString());
        }
    }

    private static float castWeight(int channel) {
        double age = (System.nanoTime() - castStarted) / 50_000_000.0;
        double weight = Math.min(1.0, age / 4.0);
        if (channel == 10 && age > com.pkmoba.combat.VenusaurCombat.R_CHARGE_TICKS)
            weight *= Math.max(0.0, (com.pkmoba.combat.VenusaurCombat.R_CAST_TICKS - age)
                    / (com.pkmoba.combat.VenusaurCombat.R_CAST_TICKS - com.pkmoba.combat.VenusaurCombat.R_CHARGE_TICKS));
        return (float) weight;
    }

    private static void release() {
        if (ownedState != null && ownedState.getPrimaryAnimation() == ownedAnimation) {
            ownedState.setPrimaryAnimation(null);
        }
        ownedState = null;
        ownedAnimation = null;
    }
}
