package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;

/**
 * 把"设计血量"写进 Cobblemon {@link Pokemon} 的持久数据，让血量上限只有一个真值。
 *
 * <p>0.38.2 之前的问题：实体属性 {@code MAX_HEALTH} 被我们强行改成 280/340，但 Cobblemon 的
 * {@code Pokemon} 对象仍用种族/等级曲线算血量上限（通常 90 左右）。于是：
 * 我们的血条写 300，按 P 打开宝可梦界面却是 90/90，两边对不上（用户 m01030 反馈）。
 *
 * <p>做法：服务端每 tick 同步时把设计上限写进 {@code Pokemon.getPersistentData()}，
 * 再由 {@code mixin/PokemonMaxHealthMixin} 让 {@code Pokemon.getMaxHealth()} 直接返回这个值。
 * 这样实体属性、`Pokemon` 对象、宝可梦界面、对战结算全部看到同一个上限。
 */
public final class PokemonHp {

    /** 持久数据键：设计血量上限。 */
    public static final String KEY = "PkmobaMaxHp";

    private PokemonHp() {
    }

    /**
     * 同步一次设计上限。值没变就直接返回，避免每 tick 触碰 NBT。
     *
     * @param entity    受控宝可梦实体
     * @param designMax 设计血量上限（280 喷火龙 / 340 水箭龟）
     */
    public static void apply(LivingEntity entity, float designMax) {
        if (!(entity instanceof PokemonEntity pokemonEntity)) {
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
        CompoundTag tag = pokemon.getPersistentData();
        if (tag == null) {
            return;
        }
        boolean changed = false;
        if (tag.getInt(KEY) != want) {
            tag.putInt(KEY, want);
            changed = true;
        }
        // 上限生效后再把"当前血量"镜像过去：Pokemon.getMaxHealth() 现在是 300/340，
        // setCurrentHealth 内部按新上限裁剪，所以先写 KEY 再写血量。
        // 这样按 P 打开界面看到的当前血量也跟实体血条一致，不会停在满血。
        int cur = entity.getHealth() <= 0.0F ? 0 : Math.max(1, Math.round(entity.getHealth()));
        if (changed || pokemon.getCurrentHealth() != cur) {
            pokemon.setCurrentHealth(cur);
        }
    }

    /** Write a design max and current HP to both the entity and Cobblemon's Pokemon mirror. */
    public static void setHealth(PokemonEntity entity, float designMax, float health) {
        if (entity == null || !Float.isFinite(designMax) || designMax <= 0 || !Float.isFinite(health)) return;
        // Publish the max first, then update the Pokemon and entity with the same HP units.
        var pokemon = entity.getPokemon();
        pokemon.getPersistentData().putInt(KEY, Math.round(designMax));
        var attribute = entity.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (attribute != null) attribute.setBaseValue(designMax);
        float wanted = Math.max(0.0F, Math.min(health, designMax));
        pokemon.setCurrentHealth(Math.round(wanted));
        entity.setHealth(wanted);
        apply(entity, designMax);
    }

    /** Set a deployed entity to its species' designed full health. */
    public static void healToFull(PokemonEntity entity, float designMax) {
        setHealth(entity, designMax, designMax);
    }

    /** 读设计上限；未写过返回 0。 */
    public static int maxHealthOf(Pokemon pokemon) {
        if (pokemon == null) {
            return 0;
        }
        CompoundTag tag = pokemon.getPersistentData();
        return tag == null ? 0 : tag.getInt(KEY);
    }
}
