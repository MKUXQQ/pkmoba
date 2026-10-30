package com.pkmoba.combat;

import java.util.Collection;
import java.util.UUID;

import javax.annotation.Nullable;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;

/**
 * 战斗状态统一重置入口（0.48.5）。
 *
 * <p><b>对局开局请用 {@link #resetParticipants(Collection)} 或 {@link #reset(PokemonEntity)}，不要用无参的
 * {@link #resetAll()}：</b>本模组的战斗状态表（{@code CharizardCombat.STATES} / {@code BlastoiseCombat.STATES} /
 * {@code PikachuCombat.STATES} / {@code VenusaurCombat.STATES}、冷却、点数、施法锁 {@link CastLock}、朝向仲裁
 * {@link FacingService}、预瞄缓存 {@link AimTracker}）都是<b>全服共享</b>的，而同一台服务器可以同时跑多场对局。
 * 无参的 {@code resetAll()} 会把<b>别人的那场</b>的引导、冷却、点数、锁向一起清掉（严重并发事故），
 * 所以它只保留给"服务器生命周期"级别的清理（{@code SERVER_STOPPED}、整服重开、调试命令）。
 *
 * <p>约定（已与 room-server-v49 对齐）：
 * <ul>
 *   <li>对局开局：在传送与召唤<b>之前</b>，把本场已存在的参战实体收集成列表，调
 *       {@link #resetParticipants(Collection)}；本场还没召唤的新实体天然是干净 UUID，不需要任何全局清理；</li>
 *   <li>单个实体死亡 / 收回 / 取消 / 换人：调 {@link #reset(PokemonEntity)}（combat 内部也已在这些路径自动清，
 *       外壳代码只在"提前离场/被替换"这类外壳自己知道的情况下补一刀）；</li>
 *   <li>必须在<b>服务端主线程</b>调用（{@code ServerTickEvents} 或包处理器里 {@code server.execute}）；</li>
 *   <li>只重置战斗状态，不动场地边界（{@code CharizardCombat.setArena/clearArena} 由房间逻辑自己管）、
 *       不动实体归属与队伍数据。</li>
 * </ul>
 */
public final class BattleStateReset {

    private BattleStateReset() {
    }

    /**
     * 只清一只宝可梦的战斗状态：该实体的职业状态（引导/冷却/点数/锁敌/区域/缓速）+
     * 它自己的施法锁、朝向仲裁与预瞄缓存。
     *
     * <p>内部复用 {@link CharizardCombat#reset(PokemonEntity)}，它会按物种分派到其余三个职业的
     * {@code reset(entity)}，所以调用方不用自己判断是喷火龙还是水箭龟。不会影响其它实体、其它对局。
     */
    public static void reset(PokemonEntity entity) {
        if (entity == null) {
            return;
        }
        CharizardCombat.reset(entity);
    }

    /**
     * 只清"本场参战实体"的战斗状态（对局开局标准入口）。逐个实体走 {@link #reset(PokemonEntity)}，
     * 不会触碰其它同时进行的对局。
     *
     * <p>用法：房间 {@code startRoom} 里、传送玩家与召唤宝可梦之前，
     * {@code BattleStateReset.resetParticipants(本场参战实体列表)}。
     */
    public static void resetParticipants(Collection<? extends PokemonEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        for (PokemonEntity entity : entities) {
            reset(entity);
        }
    }

    /**
     * 丢掉该玩家所有"收回时暂存"的扩展战斗资源（技能点数/冷却，见
     * {@link com.pkmoba.combat.ExpansionCombat#rememberRecall}）。
     *
     * <p>调用点（资源生命周期的"终态"）：登出（{@code TeamService.init} 的 DISCONNECT 清理）、
     * 删除宝可梦、复活、1v1 结算（{@code DuelManager#end}）、人机结算（{@code BotDuelService#finish}）。
     * <b>切换（recall → 再召唤）不要调用</b>：那条路径就是要靠 {@code rememberRecall} 原样保留资源。
     */
    public static void releaseReserves(@Nullable UUID player) {
        ExpansionCombat.clearReserves(player);
    }

    /**
     * 全服重置：四职业状态表（含引导、冷却、点数、锁敌、区域）+ 施法锁 + 朝向仲裁 + 预瞄缓存。
     *
     * <p><b>仅限服务器生命周期清理</b>（{@code SERVER_STOPPED}、整服重开、调试命令）。
     * 对局开局<b>禁止</b>调用：会打断同一服务器上其它正在进行的对局。对局开局用
     * {@link #resetParticipants(Collection)}。
     */
    public static void resetAll() {
        CharizardCombat.resetAll();
        BlastoiseCombat.resetAll();
        PikachuCombat.resetAll();
        VenusaurCombat.resetAll();
        CastLock.clearAll();
        AimTracker.clearAll();
    }

    /**
     * 全服清"瞬时"朝向链路：施法锁定、朝向仲裁、预瞄缓存。不洗冷却与技能点数。
     *
     * <p><b>同样仅限服务器生命周期清理</b>；对局内清单只实体请用 {@link #reset(PokemonEntity)}
     * （它会连带清该实体自己的锁与预瞄缓存）。各职业的引导/锁敌状态本身就挂在实体上，
     * 实体在失去控制权或死亡时由它们自己的 {@code clearTransient(entity)} 清理。
     */
    public static void resetTransient() {
        CastLock.clearAll();
        AimTracker.clearAll();
    }
}
