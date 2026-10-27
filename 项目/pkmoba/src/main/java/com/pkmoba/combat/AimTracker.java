package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 鼠标瞄准点缓存 + "模型常驻朝鼠标"的每 tick 转向。
 *
 * <p>为什么需要它：0.38 只在引导（channel != 0）时才让模型转向光标，站着不动时模型还是
 * 朝向移动方向。用户 m00858 要求「模型时时刻刻看着鼠标点击的方向」，所以：
 * <ol>
 *   <li>客户端每 tick 发一次 {@code AimPayload}（不只是引导期间），服务端存下最新瞄准点；</li>
 *   <li>服务端每 tick 以约 6°/tick 的低速把模型转向瞄准点 —— 转得慢，视觉上是"慢慢转过去"
 *       而不是瞬间扭头；技能方向统一取 {@code CharizardCombat.modelForward(entity)}，
 *       于是喷火/水炮就自然跟着模型缓慢扫动，而不是被光标拽着瞬移。</li>
 * </ol>
 *
 * <p>与 {@link FacingService} 的关系：这里不直接写旋转角度，只以 {@code Priority.AIM}
 * 提交请求，由 FacingService 统一仲裁（引导中的 CHANNEL 优先级更高，会压过本类）。
 */
public final class AimTracker {

    /** 鼠标转向上限：常见 90° 转向两 tick 内完成，保留客户端插值。 */
    public static final float AIM_TURN_PER_TICK = 58.0F;

    /** 超过这么多 tick 没收到新的瞄准点，就当作光标数据过期（玩家换视角/断线），不再转向。 */
    private static final int STALE_TICKS = 40;

    private record Entry(Vec3 aim, int tick) {
    }

    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    private AimTracker() {
    }

    /** 记录某个玩家（其受控宝可梦）的最新瞄准点。 */
    public static void set(@Nullable PokemonEntity entity, @Nullable Vec3 aim) {
        if (entity == null || aim == null) {
            return;
        }
        ENTRIES.put(entity.getUUID(), new Entry(aim, entity.tickCount));
    }

    /** 取最新瞄准点；过期或没有则返回 null。 */
    @Nullable
    public static Vec3 aimOf(@Nullable PokemonEntity entity) {
        if (entity == null) {
            return null;
        }
        Entry entry = ENTRIES.get(entity.getUUID());
        if (entry == null || entity.tickCount - entry.tick() > STALE_TICKS) {
            return null;
        }
        return entry.aim();
    }

    /**
     * 每 tick 调用：让模型低速转向最新的鼠标瞄准点。
     *
     * <p>引导中的 CHANNEL 优先级更高，同一 tick 里会先占位，本请求会被 FacingService 忽略。
     */
    public static void track(@Nullable PokemonEntity entity) {
        // Venusaur faces the move destination outside casts; casts claim CHANNEL priority.
        if (entity != null && Skill.isVenusaur(entity)) return;
        // 移动目标到达前，移动方向持续优先；静止后恢复常驻鼠标瞄准。
        if (entity != null && com.pkmoba.move.MoveOrderService.hasOrder(entity)) {
            return;
        }
        Vec3 aim = aimOf(entity);
        if (aim == null) {
            return;
        }
        double dx = aim.x - entity.getX();
        double dz = aim.z - entity.getZ();
        if (dx * dx + dz * dz < 1.0E-6) {
            // 光标正好压在脚下：方向退化，保持当前朝向，别抖。
            return;
        }
        FacingService.request(entity, FacingService.Priority.AIM, new Vec3(dx, 0, dz), AIM_TURN_PER_TICK);
    }

    public static void clear(@Nullable PokemonEntity entity) {
        if (entity != null) {
            ENTRIES.remove(entity.getUUID());
        }
    }

    public static void clearAll() {
        ENTRIES.clear();
    }
}
