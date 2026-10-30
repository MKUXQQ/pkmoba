package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 鼠标瞄准点缓存 + "模型常驻朝鼠标预瞄"的每 tick 转向（0.48.5 重写）。
 *
 * <p>职责分成两层：
 * <ol>
 *   <li><b>缓存</b>：客户端每 tick 发一次 {@code AimPayload}（不只是引导期间），这里只存最新瞄准点；</li>
 *   <li><b>预瞄</b>：<b>只有停下来不动的时候</b>才以 {@link #AIM_TURN_PER_TICK} 限速把模型
 *       （头 + 身体同时）转向瞄准点（0.53，用户 m03501）。走路期间朝向归移动方向
 *       （{@code MoveOrderService.faceTowards} 每 tick 写 MOVE），鼠标不参与；
 *       一旦停住（没有移动指令、寻路结束、本 tick 也没有位移），预瞄立刻接手。</li>
 * </ol>
 *
 * <p>0.48.5 修掉的两个"预瞄不生效"根因：
 * <ul>
 *   <li>原 {@code track} 在 {@code MoveOrderService.hasOrder(entity)} 时直接 return ——
 *       只要还挂着移动目的地，模型就一直朝行走方向（用户："只有引导时才看鼠标"）；</li>
 *   <li>原 {@code track} 对草系直接 return（"引导外用移动终点朝向"的旧设定）。
 *       现在统一走预瞄，移动方向不再写朝向（见 {@code MoveOrderService.faceTowards}）。</li>
 * </ul>
 *
 * <p>与 {@link CastLock} 的关系：施法锁生效期间方向在"确认施法"那一帧已钉死，
 * 这里先调 {@link CastLock#tick} 重申锁定朝向并直接返回，<b>新鼠标不会改正在引导的技能</b>；
 * 锁定到期后本类立刻接手，用限速把朝向平滑接回鼠标（不瞬移）。
 */
public final class AimTracker {

    /** 鼠标转向上限：常见 90° 转向两 tick 内完成，保留客户端插值。 */
    public static final float AIM_TURN_PER_TICK = 58.0F;

    /** 超过这么多 tick 没收到新的瞄准点，就当作光标数据过期（玩家换视角/断线），不再转向。 */
    private static final int STALE_TICKS = 40;

    private record Entry(Vec3 aim, int tick) {
    }

    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    /**
     * 上一 tick 的世界坐标（0.53）：用来判断"这一 tick 到底有没有在走"。
     *
     * <p>只靠"有没有移动指令"不够 —— 指令刚下还没起步、或者寻路已经结束但身体还在滑行时，
     * 都会让朝向在移动/鼠标之间反复横跳。这里用真实位移做主判据。
     */
    private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();

    /** 视为"在走"的最小水平速度（格/tick 的平方）：0.063 格/tick ≈ 1.26 m/s。 */
    private static final double MIN_MOTION_SQR = 4.0E-3;
    /** 视为"在走"的最小单 tick 水平位移（格）：0.0316 格/tick ≈ 0.63 m/s。 */
    private static final double MIN_STEP_SQR = 1.0E-3;

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

    /** 是否有新鲜瞄准数据（供移动服务等判断"朝向该不该交给预瞄"）。 */
    public static boolean hasFreshAim(@Nullable PokemonEntity entity) {
        return aimOf(entity) != null;
    }

    /**
     * 每 tick 调用（{@code CharizardCombat.tick} 对每只受控宝可梦执行，位于 tickEntity 之后）。
     *
     * <p>顺序很重要：先处理施法锁（它必须是本 tick 最后一个写朝向的人），再走预瞄。
     */
    public static void track(@Nullable PokemonEntity entity) {
        if (entity == null) {
            return;
        }
        // 死亡 / 收回 / 已移除：顺手把三张表清干净（死亡、撤回、取消路径的统一兜底）。
        if (!entity.isAlive() || entity.isRemoved()) {
            CastLock.release(entity);
            clear(entity);
            return;
        }
        // 施法锁：到期则释放（收招结束 → 下一行预瞄平滑接手），否则重申锁定朝向。
        CastLock.tick(entity);
        boolean locked = CastLock.active(entity);
        Vec3 aim = locked ? null : aimOf(entity);
        // 0.53（用户 m03501）：<b>走路时朝前方，停下来才朝鼠标</b>。
        // 之前只要还有新鲜瞄准数据就一直写 AIM，走路时净转向被鼠标带走，看起来"随时被强行掰向鼠标"。
        if (aim != null && !isMoving(entity)) {
            double dx = aim.x - entity.getX();
            double dz = aim.z - entity.getZ();
            // 光标正好压在脚下：方向退化，保持当前朝向，别抖。
            if (dx * dx + dz * dz >= 1.0E-6) {
                FacingService.request(entity, FacingService.Priority.AIM, new Vec3(dx, 0, dz), AIM_TURN_PER_TICK);
            }
        }
        rememberPosition(entity);
    }

    /**
     * 这一 tick 是否"正在走路"（0.53）。
     *
     * <p>三个判据任一条成立就算在走：原版寻路还在跑、当前水平速度超过阈值、本 tick 真实位移超过阈值。
     * 用真实位移兜底是必要的：直线行走不经过 A*，而"刚下指令还没起步""落地后还在滑行"这两种边界
     * 只能靠位移/速度识别出来，否则朝向会在移动方向与鼠标之间反复横跳。
     */
    private static boolean isMoving(PokemonEntity entity) {
        if (com.pkmoba.move.MoveOrderService.navigationSteering(entity)) {
            return true;
        }
        Vec3 motion = entity.getDeltaMovement();
        if (motion.x * motion.x + motion.z * motion.z > MIN_MOTION_SQR) {
            return true;
        }
        Vec3 last = LAST_POS.get(entity.getUUID());
        if (last == null) {
            return false;
        }
        double dx = entity.getX() - last.x;
        double dz = entity.getZ() - last.z;
        return dx * dx + dz * dz > MIN_STEP_SQR;
    }

    private static void rememberPosition(PokemonEntity entity) {
        LAST_POS.put(entity.getUUID(), entity.position());
    }

    public static void clear(@Nullable PokemonEntity entity) {
        if (entity != null) {
            ENTRIES.remove(entity.getUUID());
            LAST_POS.remove(entity.getUUID());
        }
    }

    /** 清空全部瞄准缓存（对局开始/结束、全员收回）。 */
    public static void clearAll() {
        ENTRIES.clear();
        LAST_POS.clear();
    }
}
