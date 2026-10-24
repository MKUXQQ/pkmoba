package com.pkmoba.client;

import com.cobblemon.mod.common.entity.PosableEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * 战败动画：服务端判到血量归零后先广播 {@code FaintPayload}，等动画播完再移除实体。
 *
 * <p>水箭龟用 Cobblemon 自带的 {@code faint} 动画；喷火龙在 1.8.2 没有 faint 动作，
 * 因此用本项目自制动画组 {@code pkmoba_faint}（见 assets/pkmoba/bedrock/pokemon/animations）。
 */
final class FaintEffects {
    private FaintEffects() {
    }

    static void play(int entityId, int ticks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity entity = mc.level.getEntity(entityId);
        if (!(entity instanceof PokemonEntity pokemon)) return;

        String species = pokemon.getPokemon().getSpecies().getResourceIdentifier().getPath();
        // 水箭龟在 Cobblemon 里自带 faint 动画，实体死亡时引擎自己会播，不重复播；
        // 喷火龙 1.8.2 没有 faint，用本项目自制的 pkmoba_faint 补上。
        if (!"blastoise".equals(species)) {
            try {
                ((PosableEntity) pokemon)
                        .playAnimation("q.bedrock_primary('pkmoba_faint', 'faint', q.curve('one'))", List.of());
            } catch (Throwable ignored) {
                // 动画组缺失时退化为无动画，服务端仍会按时移除实体
            }
        }
        // 倒下时飘一点烟，让"战败"更有反馈
        var rnd = mc.level.random;
        double cx = pokemon.getX();
        double cy = pokemon.getY() + pokemon.getBbHeight() * 0.4;
        double cz = pokemon.getZ();
        for (int i = 0; i < 6; i++) {
            mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
                    cx + (rnd.nextDouble() - 0.5) * 0.6,
                    cy + (rnd.nextDouble() - 0.5) * 0.4,
                    cz + (rnd.nextDouble() - 0.5) * 0.6,
                    0.0, 0.02, 0.0);
        }
    }
}
