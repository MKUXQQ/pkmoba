package com.pkmoba.client;

import com.cobblemon.mod.common.entity.PosableEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Authoritative cast cues animate opponents as well as the locally controlled Pokemon. */
public final class RemoteSkillAnimations {
    private record Cast(int skill, int age) {}
    private static final Map<Integer, Cast> CASTS = new HashMap<>();
    private RemoteSkillAnimations() {}
    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level == null) { CASTS.clear(); return; }
            CASTS.replaceAll((id, cast) -> {
                if (mc.level.getEntity(id) instanceof PokemonEntity pokemon && pokemon.isAlive()) {
                    if (cast.age == 11 && cast.skill == 0) play(pokemon, "q_sustain");
                    if (cast.age == 12 && cast.skill == 1 || cast.age == 20 && cast.skill == 3) play(pokemon, "r_blast");
                }
                return new Cast(cast.skill, cast.age + 1);
            });
            CASTS.entrySet().removeIf(e -> e.getValue().age > (e.getValue().skill == 0 ? 64 : 40)
                    || mc.level.getEntity(e.getKey()) == null);
        });
    }
    public static boolean accept(EffectPayload payload) {
        if (!payload.effectId().startsWith("pkmoba:cast/")) return false;
        try {
            String[] parts = payload.effectId().split("/");
            int id = Integer.parseInt(parts[1]), skill = Integer.parseInt(parts[2]);
            Minecraft mc = Minecraft.getInstance();
            if (id == ClientState.getCharizardId() || mc.level == null) return true;
            if (mc.level.getEntity(id) instanceof PokemonEntity pokemon
                    && pokemon.getPokemon().getSpecies().getResourceIdentifier().getPath().equals("charizard")) {
                CASTS.put(id, new Cast(skill, 0));
                play(pokemon, skill == 3 ? "r_charge" : skill == 2 ? "q_windup" : "q_windup");
            }
        } catch (RuntimeException ignored) { }
        return true;
    }
    private static void play(PokemonEntity pokemon, String animation) {
        try { ((PosableEntity) pokemon).playAnimation("q.bedrock_primary('pkmoba_charizard', '" + animation + "', q.curve('one'))", List.of()); }
        catch (Throwable ignored) { }
    }
}
