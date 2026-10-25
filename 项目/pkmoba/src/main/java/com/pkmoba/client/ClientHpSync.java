package com.pkmoba.client;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.pkmoba.combat.PokemonHp;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * 客户端兜底：把服务端下发的"设计血量上限"写进本地 {@link Pokemon} 对象。
 *
 * <p>为什么需要这一步：服务端已经把 {@code PkmobaMaxHp} 写进 {@code Pokemon} 的持久数据
 * 并由 {@code PokemonMaxHealthMixin} 生效，但持久数据只在特定时机随队伍包同步到客户端，
 * 召唤之后就不会再发了。于是出现"血条写 300，按 P 打开宝可梦界面却是 90/90"（用户 m01030）。
 *
 * <p>这里每收到一次 {@code SkillStatePayload}（服务端每 tick/每 2 tick 都会发）就本地补一次：
 * <ul>
 *   <li>实体身上的 {@code Pokemon}（实体头顶/血条相关读取路径）</li>
 *   <li>队伍里同一个 UUID 的 {@code Pokemon}（P 键宝可梦界面读取的就是这一份）</li>
 * </ul>
 * 两处都写，才能真正"实体血量 == 界面血量上限"。
 */
public final class ClientHpSync {

    private ClientHpSync() {
    }

    /** 只对当前受控宝可梦生效；实体不存在时静默返回。 */
    public static void apply(int entityId, float designMax) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || entityId < 0) {
            return;
        }
        if (!(mc.level.getEntity(entityId) instanceof PokemonEntity pokemonEntity)) {
            return;
        }
        Pokemon pokemon = pokemonEntity.getPokemon();
        if (pokemon == null) {
            return;
        }
        int want = Math.round(designMax);
        if (want <= 0) {
            return;
        }
        UUID uuid = pokemon.getUuid();

        // 1) 实体身上的 Pokemon 对象
        write(pokemon, want, pokemonEntity.getHealth());

        // 2) 队伍里的同一只（P 键界面/队伍 HUD 读的是这份拷贝）
        try {
            Pokemon partyCopy = CobblemonClient.INSTANCE.getStorage().getParty().findByUUID(uuid);
            if (partyCopy != null && partyCopy != pokemon) {
                write(partyCopy, want, pokemonEntity.getHealth());
            }
        } catch (Throwable ignored) {
            // 队伍尚未同步（刚进服/刚召唤）时 getParty() 可能为 null，忽略即可。
        }
    }

    private static void write(Pokemon pokemon, int maxHp, float entityHealth) {
        CompoundTag tag = pokemon.getPersistentData();
        if (tag == null) {
            return;
        }
        if (tag.getInt(PokemonHp.KEY) != maxHp) {
            tag.putInt(PokemonHp.KEY, maxHp);
        }
        // 上限对齐后把当前血量也拉齐，避免界面出现"上限 300 / 当前 90"。
        int wantHp = Math.max(1, Math.round(entityHealth));
        if (pokemon.getCurrentHealth() != Math.min(wantHp, maxHp)) {
            pokemon.setCurrentHealth(wantHp);
        }
    }
}
