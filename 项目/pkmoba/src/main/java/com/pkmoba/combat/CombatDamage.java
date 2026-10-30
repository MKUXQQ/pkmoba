package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.PkmobaMod;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * World-entity damage, not Cobblemon turn-based battle HP. Never writes health directly.
 *
 * <p>0.39（task-15）：这里是<b>全模组唯一的伤害汇聚点</b>，所以命中反馈也只在这里触发一次：
 * 只有 {@code hurt()} 真的被接受（{@code accepted == true}）才下发
 * {@code pkmoba:hit/...} 包（带真实命中点/元素/强度），未命中/被无敌帧挡掉/被事件取消
 * 一律不发包 —— 这就是"未命中不能触发"的保证。
 *
 * <p>反馈本身只有视觉与音效：这里不改 amount、不改击退、不写 HP。
 */
public final class CombatDamage {
    private CombatDamage() { }

    /** 命中反馈广播半径（格）。超出这个距离的玩家收不到包，省带宽。 */
    private static final double FEEDBACK_RANGE = 96.0;

    /** 伤害量 → 打击强度的分档阈值（只影响表现，不影响实际伤害）。 */
    private static final float HEAVY_THRESHOLD = 10.0F;

    public static boolean apply(ServerLevel level, PokemonEntity source, LivingEntity target, float amount) {
        return apply(level, source, target, amount, null, null, null);
    }

    /** Explicit move type when an attack differs from its user's species (e.g. Charizard Fly). */
    public static boolean apply(ServerLevel level, PokemonEntity source, LivingEntity target, float amount,
                                TypeEffectiveness.MoveType moveType) {
        return apply(level, source, target, amount, null, null, moveType);
    }

    /**
     * 带显式表现分档的版本：技能自己知道这是不是大招（例如 R 大字爆），
     * 可以覆盖按伤害量自动判定的档位，但<b>不影响伤害</b>。
     */
    public static boolean apply(ServerLevel level, PokemonEntity source, LivingEntity target, float amount,
                                EffectPayload.Element element, EffectPayload.Power power) {
        return apply(level, source, target, amount, element, power, null);
    }

    public static boolean apply(ServerLevel level, PokemonEntity source, LivingEntity target, float amount,
                                 EffectPayload.Element element, EffectPayload.Power power,
                                 TypeEffectiveness.MoveType moveType) {
        if (source == null || !BasicAttack.validTarget(target, source) || target.level() != level
                || !Float.isFinite(amount) || amount <= 0
                || com.pkmoba.duel.DuelManager.isPreparing(source)
                || com.pkmoba.duel.DuelManager.isPreparing(target)) return false;
        // 0.87 timed buffs: Swords Dance raises the attacker's output, Aurora Veil
        // softens what the guarded target takes. Both default to 1.0 for everyone else.
        amount *= (float) (ExpansionControl.powerMultiplier(source)
                * ExpansionControl.damageTakenMultiplier(target));
        if (!Float.isFinite(amount) || amount <= 0) return false;
        String species = target instanceof PokemonEntity pokemon && pokemon.getPokemon() != null
                ? pokemon.getPokemon().getSpecies().getResourceIdentifier().toString() : "";
        TypeEffectiveness.MoveType actualType = moveType == null
                ? TypeEffectiveness.forAttackerSpecies(source.getPokemon() == null ? "" : source.getPokemon().getSpecies().getResourceIdentifier().toString())
                : moveType;
        amount *= (float) TypeEffectiveness.multiplier(actualType, species);
        // W 高速旋转期间受到的伤害 -20%（只减伤，不免控、不无敌）。
        if (com.pkmoba.combat.BlastoiseCombat.isSpinningPokemon(target)) {
            amount *= 1.0F - com.pkmoba.combat.BlastoiseCombat.SPIN_DAMAGE_REDUCTION;
        }
        float before = target.getHealth();
        final float damageAmount = amount;
        // Skill cadence is authoritative. Remove only the previous hit's vanilla cooldown
        // during this call, not entity invulnerability, creative protection or event cancellation.
        int previousCooldown = target.invulnerableTime;
        target.invulnerableTime = 0;
        boolean accepted;
        try {
            accepted = SkillDamagePermit.hurt(source, target,
                    () -> target.hurt(level.damageSources().mobAttack(source), damageAmount));
        } catch (RuntimeException | Error failure) {
            target.invulnerableTime = previousCooldown;
            throw failure;
        }
        if (!accepted) target.invulnerableTime = previousCooldown;
        float after = target.getHealth();
        float dealt = Math.max(0.0F, before - after);
        PkmobaMod.LOGGER.debug("[pkmoba] damage source={} target={} requested={} accepted={} hp={}->{} invulnerable={}",
                source.getUUID(), target.getUUID(), amount, accepted, before, target.getHealth(), target.isInvulnerable());
        if (accepted) {
            broadcastHit(level, source, target, dealt, element, power);
        }
        return accepted;
    }

    /** 命中反馈：真实命中点 + 元素 + 强度（+ 真实致死标记），发给附近客户端。 */
    private static void broadcastHit(ServerLevel level, PokemonEntity source, LivingEntity target,
                                     float dealt, EffectPayload.Element elementOverride,
                                     EffectPayload.Power powerOverride) {
        EffectPayload.Element element = elementOverride != null ? elementOverride : elementOf(source);
        EffectPayload.Power power = powerOverride != null ? powerOverride : powerOf(dealt);
        boolean lethal = target.getHealth() <= 0.0F;
        Vec3 dir = hitDirection(source, target);
        Vec3 point = hitPoint(target, dir);
        // 0.40：带上真实扣血量与目标实体 id —— 客户端伤害数字只显示真实数值，
        // 并按实体 id 合并 0.15s 内的多段命中（不靠坐标猜测）。
        EffectPayload payload = EffectPayload.hit(element, power, lethal, point, dir, dealt, target.getId());
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(point) > FEEDBACK_RANGE * FEEDBACK_RANGE) {
                continue;
            }
            if (ServerPlayNetworking.canSend(player, EffectPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
        playHitSound(level, point, element, power);
    }

    /** 命中点：目标包围盒中心朝攻击者方向挪一点，近似"被打中的那一面"。 */
    private static Vec3 hitPoint(LivingEntity target, Vec3 dir) {
        Vec3 center = target.getBoundingBox().getCenter();
        return center.add(dir.scale(-0.35)).add(0.0, target.getBbHeight() * 0.12, 0.0);
    }

    /** 攻击者 → 目标的单位方向（拿不到攻击者位置时为零向量）。 */
    private static Vec3 hitDirection(PokemonEntity source, LivingEntity target) {
        Vec3 from = source.getEyePosition();
        Vec3 to = target.getBoundingBox().getCenter();
        Vec3 delta = to.subtract(from);
        return delta.lengthSqr() > 1.0E-8 ? delta.normalize() : Vec3.ZERO;
    }

    /** 元素：按施法者物种推断（水箭龟=水、喷火龙=火、其他=物理）。 */
    private static EffectPayload.Element elementOf(PokemonEntity source) {
        if (source == null) {
            return EffectPayload.Element.PHYSICAL;
        }
        if (CharizardManager.isBlastoise(source)) {
            return EffectPayload.Element.WATER;
        }
        if (source.getPokemon() != null && CharizardManager.CHARIZARD_ID
                .equals(source.getPokemon().getSpecies().getResourceIdentifier())) {
            return EffectPayload.Element.FIRE;
        }
        return EffectPayload.Element.PHYSICAL;
    }

    /** 强度：只按真实伤害量分档；掉落伤害为 0（被抗性吸收）时算轻击。 */
    private static EffectPayload.Power powerOf(float dealt) {
        // Only an explicitly tagged R hit may move the camera; damage size is not a skill identity.
        if (dealt >= HEAVY_THRESHOLD) {
            return EffectPayload.Power.HEAVY;
        }
        return EffectPayload.Power.LIGHT;
    }

    /**
     * 命中音：服务端 {@code playSound} 会自动广播给范围内玩家，和命中包同一时刻出发，
     * 所以"音效与真实事件绑定"不需要额外协议；客户端只额外处理循环音（见 {@code CombatAudio}）。
     */
    private static void playHitSound(ServerLevel level, Vec3 point, EffectPayload.Element element,
                                     EffectPayload.Power power) {
        SoundEvent event = switch (element) {
            case FIRE -> SoundEvents.FIRECHARGE_USE;
            case WATER -> SoundEvents.GENERIC_SPLASH;
            case PHYSICAL -> SoundEvents.PLAYER_ATTACK_STRONG;
        };
        float volume = switch (power) {
            case LIGHT -> 0.85F;
            case HEAVY -> 1.10F;
            case ULTIMATE -> 1.35F;
        };
        float pitch = switch (power) {
            case LIGHT -> 1.10F;
            case HEAVY -> 0.95F;
            case ULTIMATE -> 0.82F;
        };
        level.playSound(null, point.x, point.y, point.z, event, SoundSource.PLAYERS, volume, pitch);
    }
}
