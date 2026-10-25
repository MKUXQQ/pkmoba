package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
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

    private record Claim(Priority priority, int tick) {
    }

    private static final Map<UUID, Claim> CLAIMS = new HashMap<>();

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
        Claim claim = CLAIMS.get(id);
        // 同一 tick 已经有同级或更强的来源写过朝向：让位，避免两个系统互相拉扯。
        // 用 >= 而不是 > 的原因：弱来源后到时不能把强来源刚写好的角度又拉回去。
        if (claim != null && claim.tick() == tick && claim.priority().ordinal() >= priority.ordinal()) {
            return;
        }
        CLAIMS.put(id, new Claim(priority, tick));

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
    }

    /** 清掉某一只的记录。 */
    public static void clear(PokemonEntity entity) {
        if (entity != null) {
            CLAIMS.remove(entity.getUUID());
        }
    }
}
