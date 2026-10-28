package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 施法锁定（0.48.5）。
 *
 * <p>用户要求（m00222 转述）：<b>平时头身体跟鼠标预瞄；确认施法的一瞬间锁方向/落点；
 * 起手、释放、收招期间不能被新的鼠标、AI 或旧的移动指令覆盖；收招结束再平滑恢复预瞄。</b>
 *
 * <p>改造前的做法是"技能每 tick 重新读一次鼠标/模型朝向"：
 * <ul>
 *   <li>喷火龙 Q 用 {@code modelForward} 当火焰方向 → 模型被预瞄慢慢带着转，火焰就跟着扫；</li>
 *   <li>喷火龙 W/E/R、水箭龟 Q/旋转/喷水在引导中还会继续吃新的 {@code AimPayload}
 *       （{@code updateAim} 允许在 channel 里改瞄准点）→ 鼠标一动，正在引导的技能就改方向；</li>
 *   <li>模型朝向和真实伤害各读各的源，于是出现"头朝一边、伤害打另一边"。</li>
 * </ul>
 *
 * <p>本类把"这次施法用哪个方向、打哪个落点、位移到哪个终点"在<b>确认施法的那一帧</b>钉死，
 * 之后所有结算（射线/扇形/弹道/区域中心）与模型朝向都只读这份锁定值，
 * 并用 {@link #tick(PokemonEntity)} 每 tick 重申一次朝向 —— 它是这条链路上最后一个写者，
 * 任何 AI / 旧移动 / 新鼠标都没有机会改它。收招（{@link #finish} 的额外 tick）结束后
 * {@link #release} 把朝向交还给 {@link AimTracker} 的预瞄，由预瞄限速平滑接回鼠标方向。
 *
 * <p>线程：全部在服务端主线程（{@code ServerTickEvents}、包处理器里 {@code server.execute}）。
 */
public final class CastLock {

    /**
     * 锁定的几何语义：技能代码按它读 {@link #dir}/{@link #point}，也方便排查"这次锁了什么"。
     */
    public enum Shape {
        /** 直线 / 射线（喷火、水炮、藤鞭、十万伏特）。锁方向。 */
        LINE,
        /** 前方扇形 / 锥形（喷火龙 Q 的锥形伤害、水箭龟 R 的喷水扇形）。锁方向。 */
        CONE,
        /** 指定落点（火焰旋涡、电网、打雷、花之舞落点、弹道终点）。锁合法坐标。 */
        POINT,
        /** 位移（飞翔、冲浪、电光一闪）。锁方向 + 终点。 */
        DASH,
        /** 自身中心 AOE（旋转、水旋涡）。中心恒为施法者自身，只锁面向。 */
        SELF
    }

    /**
     * 一次施法锁定。
     *
     * @param shape     几何语义
     * @param dir       锁定方向（水平单位向量，非 null）
     * @param point     锁定落点；{@code SELF} 为 null（中心读实体当前位置），其它形状为合法世界坐标
     * @param startTick 开始 tick（实体 {@code tickCount}）
     * @param endTick   自动释放的 tick（{@code -1} = 一直锁到显式 {@link #release}）
     */
    public record Lock(Shape shape, Vec3 dir, @Nullable Vec3 point, int startTick, int endTick) {

        public boolean expired(int tick) {
            return endTick >= 0 && tick >= endTick;
        }
    }

    private static final Map<UUID, Lock> LOCKS = new HashMap<>();

    private CastLock() {
    }

    // ------------------------------------------------------------------ 建立 / 维护 / 释放

    /**
     * 确认施法：钉住方向与落点，并<b>立刻</b>把模型转到锁定方向（起手不拖泥带水）。
     *
     * @param dir       期望方向（水平；为 null 或零向量时退化为 {@code modelForward}）
     * @param point     期望落点（调用方负责先夹到合法坐标：场地内、地面上、射程内）；
     *                  {@link Shape#SELF} 传 null
     * @param holdTicks 这个锁最多保持多少 tick（含收招）；{@code <= 0} 表示一直锁到显式释放
     */
    public static void begin(PokemonEntity entity, Shape shape, @Nullable Vec3 dir, @Nullable Vec3 point,
                             int holdTicks) {
        if (entity == null) {
            return;
        }
        Vec3 flat = horizontal(dir);
        if (flat == null) {
            flat = horizontal(CharizardCombat.modelForward(entity));
        }
        if (flat == null) {
            return;
        }
        Vec3 fixedPoint = shape == Shape.SELF ? null : point;
        int tick = entity.tickCount;
        int endTick = holdTicks > 0 ? tick + holdTicks : -1;
        LOCKS.put(entity.getUUID(), new Lock(shape, flat, fixedPoint, tick, endTick));
        // 起手瞬间就朝锁定方向:用户要的"确认施法"必须有即时反馈,而且之后伤害方向 = 看到的方向。
        // 0.48.5：用 force 而不是 snap —— 技能包在包处理阶段到达，可能和上一 tick 的 CHANNEL claim
        // 撞在同一个 tickCount 上；snap 的仲裁会让这次起手转向被吃掉（模型仍朝旧方向慢慢转）。
        FacingService.force(entity, flat);
    }

    /** 施法进入下一段（前摇→释放→收招）：把锁的到期 tick 往后推。 */
    public static void extend(PokemonEntity entity, int extraTicks) {
        Lock lock = lockOf(entity);
        if (lock == null || extraTicks <= 0) {
            return;
        }
        int endTick = entity.tickCount + extraTicks;
        LOCKS.put(entity.getUUID(), new Lock(lock.shape(), lock.dir(), lock.point(),
                lock.startTick(), endTick));
    }

    /** 收招：再锁 {@code recoveryTicks} tick 后自动放开（期间仍然不能被打断朝向）。 */
    public static void finish(PokemonEntity entity, int recoveryTicks) {
        extend(entity, Math.max(1, recoveryTicks));
    }

    /** 立刻释放锁定（死亡 / 收回 / 换人 / 取消 / 引导被打断）。 */
    public static void release(@Nullable PokemonEntity entity) {
        if (entity == null) {
            return;
        }
        LOCKS.remove(entity.getUUID());
        // 连仲裁记录一起清掉,下一 tick 预瞄才能立刻接手。
        FacingService.clear(entity);
    }

    /** 清空全部锁定 + 朝向仲裁（对局开始/结束、全员收回）。 */
    public static void clearAll() {
        LOCKS.clear();
        FacingService.clear();
    }

    /**
     * 修剪"已经不在受控集合里"的残留锁定（0.48.5）。
     *
     * <p>为什么需要：收回/换宝可梦时实体还活着（只是不再受控），{@code AimTracker.track} 不再被调用，
     * 若此时残留一把 {@code endTick == -1} 的锁定，等这只宝可梦<b>下次被重新召唤</b>时会被旧锁钉住、
     * 永远不跟鼠标。这里每 tick 跟着 {@code CharizardCombat.tick} 的受控列表做一次 retain。
     */
    public static void retain(Collection<PokemonEntity> live) {
        if (LOCKS.isEmpty()) {
            return;
        }
        Set<UUID> keep = new HashSet<>();
        for (PokemonEntity entity : live) {
            if (entity != null) {
                keep.add(entity.getUUID());
            }
        }
        LOCKS.keySet().retainAll(keep);
        FacingService.retain(keep);
    }

    /**
     * 每 tick 调用（由 {@link AimTracker#track} 统一驱动）：到期释放，否则<b>重申</b>锁定朝向。
     *
     * <p>这是本 tick 对受控宝可梦的<b>最后一次</b>朝向写：{@code AimTracker.track} 在
     * {@code CharizardCombat.tick} 的 tickEntity 之后执行，所以 AI / 旧移动 / 新鼠标的写都被它盖掉。
     */
    public static void tick(@Nullable PokemonEntity entity) {
        if (entity == null) {
            return;
        }
        Lock lock = LOCKS.get(entity.getUUID());
        if (lock == null) {
            return;
        }
        if (!entity.isAlive() || entity.isRemoved()) {
            release(entity);
            return;
        }
        if (lock.expired(entity.tickCount)) {
            // 收招结束:交还预瞄。下一行起 AimTracker 会以限速平滑接回鼠标方向,不瞬移。
            release(entity);
            return;
        }
        FacingService.hold(entity, lock.dir());
    }

    // ------------------------------------------------------------------ 查询

    public static boolean active(@Nullable PokemonEntity entity) {
        return entity != null && LOCKS.containsKey(entity.getUUID());
    }

    @Nullable
    public static Lock lockOf(@Nullable PokemonEntity entity) {
        return entity == null ? null : LOCKS.get(entity.getUUID());
    }

    /** 锁定方向（水平单位向量）；没有锁返回 null。 */
    @Nullable
    public static Vec3 dir(@Nullable PokemonEntity entity) {
        Lock lock = lockOf(entity);
        return lock == null ? null : lock.dir();
    }

    /** 锁定方向；没有锁返回 {@code fallback}（方便技能代码一行替换旧的 aimDirection/modelForward）。 */
    @Nullable
    public static Vec3 dirOr(@Nullable PokemonEntity entity, @Nullable Vec3 fallback) {
        Lock lock = lockOf(entity);
        return lock == null ? fallback : lock.dir();
    }

    /** 锁定落点；没有锁 / {@link Shape#SELF} 返回 null。 */
    @Nullable
    public static Vec3 point(@Nullable PokemonEntity entity) {
        Lock lock = lockOf(entity);
        return lock == null ? null : lock.point();
    }

    /** 锁定落点；没有锁返回 {@code fallback}。 */
    @Nullable
    public static Vec3 pointOr(@Nullable PokemonEntity entity, @Nullable Vec3 fallback) {
        Lock lock = lockOf(entity);
        return lock == null || lock.point() == null ? fallback : lock.point();
    }

    /** 自中心 AOE / 位移起点这类"跟着自身"的技能应读这个中心（没有锁时就是当前位置）。 */
    public static Vec3 centerOr(PokemonEntity entity, @Nullable Vec3 fallback) {
        Lock lock = lockOf(entity);
        if (lock == null) {
            return fallback != null ? fallback : entity.position();
        }
        // SELF:中心永远是施法者自身;POINT:用锁定落点。
        return lock.shape() == Shape.SELF || lock.point() == null ? entity.position() : lock.point();
    }

    /** 水平化 + 单位化；退化向量返回 null。 */
    @Nullable
    private static Vec3 horizontal(@Nullable Vec3 v) {
        if (v == null) {
            return null;
        }
        Vec3 flat = new Vec3(v.x, 0, v.z);
        return flat.lengthSqr() < 1.0E-8 ? null : flat.normalize();
    }
}
