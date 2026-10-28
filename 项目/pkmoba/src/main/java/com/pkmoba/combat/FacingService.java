package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 朝向唯一仲裁者。
 *
 * <p>为什么需要它：改造前有四处代码每 tick 各写一遍
 * {@code setYRot/setYHeadRot/setYBodyRot} 并且同时 {@code hurtMarked = true}
 * （{@code MoveOrderService.faceTowards}、{@code BasicAttack.launchWater}、
 * {@code CharizardCombat.turnToward}、{@code BlastoiseCombat.turn/face}），
 * 再叠加原版 {@code Mob.aiStep -> LookControl.tick()} 也会写 {@code YHeadRot}。
 * 同一 tick 内多个写者以不同速率改同一个角度，客户端在两次插值之间反复被重置，
 * 观感就是"模型的头在乱晃"。
 *
 * <p>本类的规则：
 * <ol>
 *   <li>同一 tick、同一只宝可梦，只有最高优先级的一个来源能写朝向；</li>
 *   <li>{@code YRot}/{@code YHeadRot}/{@code YBodyRot} 一起写成同一个值 ——
 *       头相对身体的偏角恒为 0，模型不会"头单独甩向一边"；因为是单写者，
 *       也不会像改造前那样两套值互相打架；</li>
 *   <li>每 tick 最大转角 = min(调用方上限, 优先级基础速率 MOVE 30/AIM 16/ATTACK 22/CHANNEL 45 °/tick)；
 *       调用方传 180 视为"不限速，用优先级速率"，传更小的值（例如加农水炮 6°/tick 缓慢扫）就是硬上限；
 *       只有走优先级速率时，大转角（&gt;90°）才叠缓出倍率；模型是"快速转过去"而不是瞬移；</li>
 *   <li>0.38.3 起不再用 {@code hurtMarked} 强制同步（它只发 SetEntityMotion，
 *       对朝向无益、还会重置客户端速度），改为每 tick 置 {@code hasImpulse}，
 *       让 {@code ServerEntity} 按 tick 发旋转包，客户端插值连续。</li>
 * </ol>
 */
public final class FacingService {

    /** 朝向来源优先级，序号越大越强（CHANNEL > AIM > ATTACK > MOVE）。 */
    public enum Priority {
        MOVE,
        ATTACK,
        /** 鼠标瞄准：模型的常驻朝向来源，用低速持续追上光标方向。 */
        AIM,
        CHANNEL
    }

    /**
     * 各优先级的基础转速（度/tick）。0.38.3 起从 MOVE 22/AIM 6/CHANNEL 15 提速：
     * 用户要求"转身再快一点、参考 LOL 电脑版"，原速率看起来像慢动作。
     */
    private static final float MOVE_RATE = 110.0F;
    private static final float ATTACK_RATE = 62.0F;
    private static final float AIM_RATE = 58.0F;
    private static final float CHANNEL_RATE = 78.0F;
    /** 大转角阈值：|gap| 超过它就按 gap 做缓出曲线，越远转得越快（收尾仍是限速，不会瞬移）。 */
    private static final float CATCH_UP_DEGREES = 90.0F;
    /** 大转角的额外倍率上限：180° 时 cap 约为 1.8 倍。 */
    private static final float CATCH_UP_BOOST = 0.8F;
    /** 头/身体角度与锁定方向的容差（度）。yaw 已对齐但头或身体偏离超过它时，仍要重写一次（AI 会单独改头）。 */
    private static final float FACING_TOLERANCE = 1.0F;

    private record Claim(Priority priority, int tick) {
    }

    private static final Map<UUID, Claim> CLAIMS = new HashMap<>();

    /**
     * 预瞄（AIM）最近一次真正写朝向的 tick（0.48.5）。
     *
     * <p>原实现只有"同 tick 仲裁"：{@code MoveOrderService} 的 MOVE 请求先写（110°/t），
     * AIM 后写但只有 58°/t，于是每 tick 净效果仍被拉向移动方向 ——
     * 用户看到的就是"走动/施法收尾后模型不跟鼠标，只有引导时跟"。
     *
     * <p>现在只要 AIM 在本 tick 或上一 tick 写过，MOVE/ATTACK 这种更弱的来源<b>直接不写、不占位</b>，
     * 让 AIM 成为唯一的水平朝向写者；施法期间则由 {@link CastLock} 独占（连 AIM 也让位）。
     */
    private static final Map<UUID, Integer> AIM_OWNED = new HashMap<>();

    /** AIM 抢占的宽限：上一 tick 写过就继续独占，避免"移动指令每隔一 tick 插一脚"导致的抖动。 */
    private static final int AIM_OWN_TICKS = 2;

    private FacingService() {
    }

    /**
     * 请求把这只宝可梦的水平朝向转到 {@code dir}。
     *
     * @param dir        期望的水平方向（y 分量被忽略；零向量直接忽略）
     * @param maxStepDeg 本 tick 最多转多少度
     */
    public static void request(PokemonEntity entity, Priority priority, @Nullable Vec3 dir, float maxStepDeg) {
        if (entity == null || dir == null) {
            return;
        }
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (horizontal < 1.0E-6) {
            return;
        }

        UUID id = entity.getUUID();
        int tick = entity.tickCount;

        // 0.48.5：预瞄 / 施法锁定期间，弱来源（移动、旧的攻击系统）不得抢写朝向。
        // 顺序说明：MoveOrderService 的 END tick 比 CharizardCombat 早，MOVE 每 tick 先写，
        // 单靠下面的同 tick 仲裁只能挡住"AIM 之后来的"来源，挡不住"先写的大角度"。
        if (priority == Priority.MOVE) {
            if (CastLock.active(entity)) return;
            // A current move order supersedes the short AIM ownership grace period.
            AIM_OWNED.remove(id);
        } else if (priority == Priority.ATTACK && (CastLock.active(entity) || aimOwned(entity))) {
            return;
        }
        // 施法锁定是绝对权威：期间连鼠标预瞄都不许改方向（收招结束后才放开）。
        if (priority == Priority.AIM && CastLock.active(entity)) {
            return;
        }

        Claim claim = CLAIMS.get(id);
        // 同一 tick 已经有同级或更强的来源写过朝向：让位，避免两个系统互相拉扯。
        // 用 >= 而不是 > 的原因：弱来源后到时不能把强来源刚写好的角度又拉回去。
        if (claim != null && claim.tick() == tick && claim.priority().ordinal() >= priority.ordinal()) {
            return;
        }
        CLAIMS.put(id, new Claim(priority, tick));
        if (priority == Priority.AIM) {
            // 记下"预瞄正在接管"：即使本 tick 已经对准（下面 gap≈0 直接 return），
            // 弱来源也必须在接下来的 tick 里让位。
            AIM_OWNED.put(id, tick);
        }

        float desired = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
        float current = entity.getYRot();
        float gap = Mth.wrapDegrees(desired - current);
        float base = baseRate(priority);
        // 语义（0.38.5 修正）：调用方上限是"硬上限"，优先级基础速率是"默认速率"。
        //   传 180  = 我不关心速度，按优先级给（MOVE/ATTACK 快速转、AIM 平缓追）
        //   传 6    = 我就要慢扫 6°/tick（加农水炮），以前被 max(base) 顶成 45°/tick，慢扫形同虚设
        //   传 30   = 最多 30°/tick，比 CHANNEL 基础的 45 更保守，按 30 走
        boolean limited = maxStepDeg > 0.0F && maxStepDeg < base;
        float cap = limited ? maxStepDeg : base;
        float absGap = Math.abs(gap);
        // 只有"按优先级速率转"时才吃大转角加速；显式慢速请求（如 6°/tick）必须严格尊重。
        if (!limited && absGap > CATCH_UP_DEGREES) {
            float t = Math.min(1.0F, (absGap - CATCH_UP_DEGREES) / CATCH_UP_DEGREES);
            cap *= 1.0F + CATCH_UP_BOOST * t;
        }
        float step = Mth.clamp(gap, -cap, cap);
        if (Math.abs(step) < 0.01F) {
            // 0.48.5：yaw 已对齐时也不能"什么都不做" —— 实体 AI/LookControl 会单独改 head/body 角度，
            // 让状态看起来"朝向正确但头在乱转"。偏差超过容差就按目标角重写一次，把三套角度抹平。
            if (headOrBodyOff(entity, desired)) {
                write(entity, desired);
            }
            return;
        }
        float next = current + step;
        // 三套角度写成同一个值：头相对身体的偏角恒为 0，模型不会"头单独甩向一边"。
        // （服务端 tickHeadTurn 会让 yBodyRot 以有限速度追 yRot；只写 yRot 的话
        //   yHeadRot/yBodyRot 的滞后差会通过骑乘/头部渲染路径漏出来，所以一并写齐。）
        // 逐 tick 打开发包，取代原来的 hurtMarked 暴力同步：
        //   ServerEntity.sendChanges() 的闸门是
        //   `tickCount % updateInterval != 0 && !entity.hasImpulse && !getEntityData().isDirty() -> return`
        //   （javap 实证；mob 默认 updateInterval=3），所以旋转包原本 3 tick 才发一个。
        //   客户端渲染的 entityYaw 只做单 tick 的 rotLerp(yRotO, yRot)，拿到 3 tick 一跳的
        //   大角度时表现就是"快转一下、停两下" —— 用户说的"转身顿一下"。
        //   而 hurtMarked 只会触发 ClientboundSetEntityMotionPacket -> lerpMotion，
        //   对朝向没有任何帮助，反而重置客户端速度，是顿挫的另一半来源，已删除。
        //   置 hasImpulse 后 ServerEntity 每 tick 都发 PosRot，客户端逐 tick 插值即连续。
        write(entity, next);
    }

    /** 各优先级的基础转速（度/tick）。 */
    private static float baseRate(Priority priority) {
        return switch (priority) {
            case MOVE -> MOVE_RATE;
            case ATTACK -> ATTACK_RATE;
            case AIM -> AIM_RATE;
            case CHANNEL -> CHANNEL_RATE;
        };
    }

    /**
     * 立刻转到位（施法起手瞬间用）。
     *
     * <p>0.38.5 起 {@code request(..., 180F)} 不再是"转 180 度"，而是"按优先级速率转"，
     * 所以 snap 不能再借道 request，必须自己把角度直接写成目标值。
     * 仍然遵守仲裁：同 tick 已有同级或更强的来源写过，就让位。
     */
    public static void snap(PokemonEntity entity, Priority priority, @Nullable Vec3 dir) {
        if (entity == null || dir == null) {
            return;
        }
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (horizontal < 1.0E-6) {
            return;
        }
        UUID id = entity.getUUID();
        int tick = entity.tickCount;
        Claim claim = CLAIMS.get(id);
        if (claim != null && claim.tick() == tick && claim.priority().ordinal() >= priority.ordinal()) {
            return;
        }
        CLAIMS.put(id, new Claim(priority, tick));
        float desired = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
        write(entity, desired);
    }

    /**
     * 无条件立刻转到位（0.48.5，施法确认帧的"起手即转向"专用）。
     *
     * <p>与 {@link #snap} 的唯一区别：**不看同 tick 仲裁**。原因：技能包在包处理阶段先于本 tick 的
     * Combat tick 执行，此时 {@code CLAIMS} 里可能还留着上一 tick 某个 CHANNEL 来源的 claim，
     * 而仲裁比较用的是 {@code entity.tickCount}（实体尚未 tick、数值没变），于是"同级或更强"的判断
     * 会把这次起手 snap 吃掉，表现为"按下技能后模型还朝旧方向慢慢转半个身位"。
     * 起手朝向必须绝对权威：这里强制写入，并把 claim 记成 CHANNEL 最高的那一档。
     */
    public static void force(@Nullable PokemonEntity entity, @Nullable Vec3 dir) {
        if (entity == null || dir == null) {
            return;
        }
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (horizontal < 1.0E-6) {
            return;
        }
        int tick = entity.tickCount;
        CLAIMS.put(entity.getUUID(), new Claim(Priority.CHANNEL, tick));
        // 同时记 ownership：起手这一 tick 的 MOVE/ATTACK 也必须让位（它们可能在本 tick 稍后才跑）。
        AIM_OWNED.put(entity.getUUID(), tick);
        float desired = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
        write(entity, desired);
    }

    /**
     * 施法锁定期间逐 tick 重申朝向（0.48.5，{@link CastLock} 专用）。
     *
     * <p>与 {@link #request} 的区别：不参与同 tick 仲裁，也不会被已有 claim 挡住 ——
     * 它就是本 tick 的最终写者（调用点在 {@code AimTracker.track} 里，位于 Combat tick 的末尾）。
     * 只在模型还没完全对齐锁定方向时补步，已对齐则不重复发包。
     */
    public static void hold(PokemonEntity entity, @Nullable Vec3 dir) {
        if (entity == null || dir == null) {
            return;
        }
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (horizontal < 1.0E-6) {
            return;
        }
        int tick = entity.tickCount;
        CLAIMS.put(entity.getUUID(), new Claim(Priority.CHANNEL, tick));
        float desired = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
        float gap = Mth.wrapDegrees(desired - entity.getYRot());
        float cap = CHANNEL_RATE;
        if (Math.abs(gap) > CATCH_UP_DEGREES) {
            // 起手 snap 被客户端插值抹掉半帧、或锁定方向被位移技能带偏时，2 tick 内补到位。
            cap *= 1.0F + CATCH_UP_BOOST;
        }
        float step = Mth.clamp(gap, -cap, cap);
        if (Math.abs(step) < 0.01F) {
            // 0.48.5：yaw 对齐了也不能直接退出 —— 原版 LookControl / 实体 AI 每 tick 只改 head/body 角度
            // （尤其施法引导时它们仍在跑），yaw 不变但头会歪，表现为"锁期间头乱动"。
            // 所以这里补一次头/身体偏差检查：不一致就按锁定角重写一次（write 会把三套角度一起抹平）。
            if (headOrBodyOff(entity, desired)) {
                write(entity, desired);
            }
            return;
        }
        write(entity, entity.getYRot() + step);
    }

    /** 头或身体相对目标角的偏差是否超过容差（度）。 */
    private static boolean headOrBodyOff(PokemonEntity entity, float desired) {
        return Math.abs(Mth.wrapDegrees(entity.getYHeadRot() - desired)) > FACING_TOLERANCE
                || Math.abs(Mth.wrapDegrees(entity.yBodyRot - desired)) > FACING_TOLERANCE;
    }

    /** 预瞄是否正在接管这只宝可梦的朝向（最近 {@link #AIM_OWN_TICKS} tick 内 AIM 写过）。 */
    public static boolean aimOwned(@Nullable PokemonEntity entity) {
        if (entity == null) {
            return false;
        }
        Integer tick = AIM_OWNED.get(entity.getUUID());
        return tick != null && entity.tickCount - tick <= AIM_OWN_TICKS;
    }

    /** 把三套角度写成同一个值并请求本 tick 发包（FacingService 的唯一写入点）。 */
    private static void write(PokemonEntity entity, float yaw) {
        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
        entity.setYBodyRot(yaw);
        entity.hasImpulse = true;
    }

    /** 清空仲裁记录（换人/收回/对局结束）。 */
    public static void clear() {
        CLAIMS.clear();
        AIM_OWNED.clear();
    }

    /** 清掉某一只的记录。 */
    public static void clear(PokemonEntity entity) {
        if (entity != null) {
            CLAIMS.remove(entity.getUUID());
            AIM_OWNED.remove(entity.getUUID());
        }
    }

    /**
     * 只保留仍在受控集合里的仲裁记录（0.48.5，由 {@link CastLock#retain} 每 tick 调用一次）。
     *
     * <p>收回/换宝可梦时实体还活着但不再受控，这条链路上不会再有人替它清表；
     * 不清的话 UUID 会一直堆在 {@code CLAIMS}/{@code AIM_OWNED} 里。
     */
    static void retain(Set<UUID> keep) {
        CLAIMS.keySet().retainAll(keep);
        AIM_OWNED.keySet().retainAll(keep);
    }
}
