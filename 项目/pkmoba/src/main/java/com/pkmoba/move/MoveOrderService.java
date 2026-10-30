package com.pkmoba.move;

import com.pkmoba.PkmobaMod;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.godmode.ServerGodMode;
import com.pkmoba.network.MoveAckPayload;
import com.pkmoba.network.MoveOrderPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 右键点地 → 受控宝可梦寻路走过去。
 *
 * <p>本版关键改动：
 * <ul>
 *   <li>目标从 BlockPos 改成精确命中的 {@link Vec3}（用户选 D2-B），落点不再偏半格；</li>
 *   <li>到达阈值收紧到 0.5 格（原本 1.2 格，是"停早了"的主因）；</li>
 *   <li>服务端把**实际接受的目标点**回发客户端（{@link MoveAckPayload}），
 *       供 HUD 和客户端点做对照 —— 这是诊断偏移的主要手段；</li>
 *   <li>到达后立即待机（用户选 D6-A），不做补走；</li>
 *   <li><b>1v1 改造</b>：移动指令从"全局唯一一份"改成按受体宝可梦 UUID 保存，
 *       两只宝可梦各自有自己的目的地和重寻路计时，互不覆盖。</li>
 * </ul>
 */
public final class MoveOrderService {
    /** 水平距离小于这个值算到达。0.6 格是"看得准"和"不抖动"的折中。 */
    private static final double ARRIVE_DISTANCE = 0.35;
    private static final double NAV_SPEED = 1.2;
    /** 点地移动的极限距离：上帝视角能拉很远，96 格会被静默拒收（用户："不朝标点方向前进"）。 */
    private static final double MAX_ORDER_DISTANCE = 256.0;
    private static final int REPATH_INTERVAL = 10;
    /**
     * 目标行走速度（格/tick）。0.23 ≈ 4.6 格/秒，介于玩家走路 4.3 与疾跑 5.6 之间。
     *
     * <p>关键点：这个速度由 {@code entity.move()} 直接驱动，**不再乘** Cobblemon 的
     * {@code MOVEMENT_SPEED} 属性 —— 普通宝可梦的属性值普遍很低，那正是"移动太慢"的根因。
     */
    private static final double WALK_SPEED = 0.31; // 0.23 * 1.35: responsive dodging speed
    /** 按住疾跑 / 距离较远时的速度（5.6 格/秒）。 */
    private static final double SPRINT_SPEED = 0.38; // 0.28 * 1.35
    /** 单 tick 最大水平位移，防止瞬移（被地形卡住时也不会跳）。 */
    private static final double STEP_CLAMP = 0.5;
    /** 逐 tick 检测前方是否可走：抬升上限（能上一格台阶）。 */
    private static final double STEP_UP = 1.05;
    /**
     * 转向：每 tick 最多调整的角度。
     *
     * <p>0.39.2（用户 m03117 "宝可梦还是不够灵活"）：15° 时转 90° 要 6 tick（0.3s），
     * 点一下斜后方要先"滑"一段再拐，手感笨重。45°/tick 时 90° 只要 2 tick，
     * 和英雄联盟英雄的转身反馈接近；再快就会盖掉走路的姿态过渡，所以停在这里。
     */
    private static final float TURN_PER_TICK = 62.0F;

    /** 每只受控宝可梦一份移动指令。key = 实体 UUID。 */
    private static final Map<UUID, Order> ORDERS = new HashMap<>();

    private static final class Order {
        @Nullable Vec3 target;
        int repathCooldown;
        /** 上一次交给 A* 的目标；只有目标变化时才重新寻路，杜绝"每 10 tick 重算导致锯齿"。 */
        @Nullable Vec3 lastNavGoal;
    }

    private MoveOrderService() {
    }

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(MoveOrderPayload.TYPE, (payload, context) ->
                context.server().execute(() -> handleOrder(context.player(), payload.pos())));

        ServerTickEvents.END_SERVER_TICK.register(MoveOrderService::tick);
    }

    private static void handleOrder(ServerPlayer player, Vec3 pos) {
        if (!player.isAlive() || player.isSpectator() || !ServerGodMode.isActive(player)) {
            ack(player, pos, false);
            return;
        }
        var entity = CharizardManager.getControlled(player);
        // 只允许操作"自己那只"：owner 必须真的是这个玩家。
        if (entity == null || !player.getUUID().equals(CharizardManager.getControlledOwner(entity))) {
            ack(player, pos, false);
            return;
        }
        if (!entity.level().dimension().equals(player.level().dimension())) {
            ack(player, pos, false);
            return;
        }
        // 0.39：大招 / 定身施法期间不接受新的地面指令。
        // 用户反馈"释放大招期间仍然会响应其他移动指令、结束后还朝旧点位走过去"——根因是
        // 指令被收下后存在 Order 里，引导结束（blocksMovement 变 false）的下一 tick 就被执行了。
        // 这里直接拒收，客户端 ack(false) 会给出红叉反馈，不会让人以为点了没反应。
        if (playerOrderLocked(entity)) {
            ack(player, pos, false);
            return;
        }
        if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) {
            ack(player, entity.position(), false);
            return;
        }
        double dist = Math.sqrt(entity.distanceToSqr(pos));
        if (dist > MAX_ORDER_DISTANCE) {
            // 0.38.2：玩家连续右键会让这条刷满日志，降到 debug。
            PkmobaMod.LOGGER.debug("[pkmoba] 移动指令超出范围 {} 格，已忽略", String.format("%.1f", dist));
            ack(player, pos, false);
            return;
        }

        // B-3：目标点截断到场地圆内（不再按"离玩家 48 格"硬拦）
        Vec3 clamped = com.pkmoba.combat.CharizardCombat.clampToArena(entity, pos);
        if (clamped.distanceTo(pos) > 0.01) {
            PkmobaMod.LOGGER.debug("[pkmoba] 移动目标在场地外，已截断到边界");
            pos = clamped;
        }
        order(entity).target = pos;
        order(entity).repathCooldown = 0;
        order(entity).lastNavGoal = null;
        PkmobaMod.LOGGER.debug("[pkmoba] 移动指令 entity={} -> ({}, {}, {})", entity.getUUID(),
                String.format("%.2f", pos.x), String.format("%.2f", pos.y), String.format("%.2f", pos.z));
        ack(player, pos, true);
    }

    /** Blocks ground orders without disabling displacement implemented by the skill itself. */
    public static boolean playerOrderLocked(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        return com.pkmoba.combat.CharizardCombat.blocksMovement(entity)
                || com.pkmoba.pokemon.BallThrow.locked(entity);
    }

    private static void ack(ServerPlayer player, Vec3 pos, boolean accepted) {
        if (ServerPlayNetworking.canSend(player, MoveAckPayload.TYPE)) {
            ServerPlayNetworking.send(player, new MoveAckPayload(pos.x, pos.y, pos.z, accepted));
        }
    }

    private static Order order(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        return ORDERS.computeIfAbsent(entity.getUUID(), id -> new Order());
    }

    /**
     * 丢弃排队中的地面指令并立刻刹车（0.39）。
     *
     * <p>用于"定身型大招起手"：玩家先右键点了地面、再按 R，如果不在这里清掉，
     * 引导结束的下一 tick 就会朝那个旧点位继续走——用户看到的就是"放完大招自己跑走了"。
     * 大招结束后玩家下发的新指令不受影响（照常被接收）。
     */
    public static void cancelPending(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        if (entity == null) return;
        Order order = ORDERS.get(entity.getUUID());
        if (order != null) {
            order.target = null;
            order.lastNavGoal = null;
            order.repathCooldown = 0;
        }
        // Clearing a stale destination must not cancel skill-owned displacement or ordinary movement.
    }

    private static void tick(MinecraftServer server) {
        var entities = CharizardManager.getControlledEntities(server);
        // 清理已经不存在的实体指令，避免 UUID 表无限增长
        ORDERS.keySet().removeIf(id -> entities.stream().noneMatch(e -> e.getUUID().equals(id)));
        for (var entity : entities) {
            tickEntity(entity);
        }
    }

    private static void tickEntity(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        Order order = order(entity);
        // 保留 AI 的副作用控制：不许它自己去打人
        entity.setTarget(null);
        // 0.41.2：边界硬约束 —— 击退/位移技能/斜坡都可能把宝可梦推出地图，
        // 这里每 tick 兜一次底，出界就拉回最近的内点（用户 m04462："边界没有把宝可梦限制"）。
        {
            Vec3 now = entity.position();
            Vec3 inside = com.pkmoba.combat.CharizardCombat.clampToArena(entity, now);
            if (inside.distanceToSqr(now) > 1.0E-4) {
                entity.teleportTo(inside.x, inside.y, inside.z);
                order.lastNavGoal = null;
            }
        }
        // 施法中由技能接管位移：原地施法（用户 m02984/m03603："释放技能的时候不能乱动"）。
        // 0.39.5：连排队中的落点也一起丢掉，否则引导结束的下一 tick 会突然继续走，
        // 看起来就像"释放技能的时候宝可梦在乱动"。
        if (playerOrderLocked(entity)) {
            order.target = null;
            order.lastNavGoal = null;
            order.repathCooldown = 0;
            entity.getNavigation().stop();
            // Skill-driven dash/surf velocity belongs to combat, not the ground-order service.
            return;
        }

        // BotDuelService owns bot navigation; keep its path between AI decisions.
        if (com.pkmoba.duel.BotDuelService.isBot(entity)) return;

        if (order.target == null) {
            if (!entity.getNavigation().isDone()) {
                entity.getNavigation().stop();
            }
            return;
        }

        double dx = order.target.x - entity.getX();
        double dz = order.target.z - entity.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        if (horizontal < ARRIVE_DISTANCE) {
            order.target = null;
            order.lastNavGoal = null;
            entity.getNavigation().stop();
            entity.setDeltaMovement(0.0, entity.getDeltaMovement().y, 0.0);
            return;
        }

        // 先试"直线走"：视线通畅且前方地面可站立时完全脱离 A*，
        // 这样点哪就走哪，不会出现"绕一小段再拐回来"的迂回。
        Vec3 flatDir = new Vec3(dx / horizontal, 0, dz / horizontal);
        faceTowards(entity, flatDir);
        if (straightWalk(entity, order, flatDir, horizontal)) {
            order.lastNavGoal = null;
            return;
        }

        // 直线被挡：退回寻路，但只在目标变化/路径失效时重算一次。
        // 旧实现每 10 tick 无脑重算，每次 A* 都返回"方块中心节点链"，
        // 导致路线在两条近似等长路径之间来回切 —— 玩家看到的就是身体左右摆动的迂回感。
        if (order.repathCooldown > 0) {
            order.repathCooldown--;
            return;
        }
        boolean needsPath = order.lastNavGoal == null
                || order.lastNavGoal.distanceToSqr(order.target) > 1.0
                || entity.getNavigation().isDone();
        if (needsPath) {
            // A* 只是兜底：速度倍率按属性反算，保证"绕路时"与"直线时"看起来一样快，
            // 而不是一遇障碍就掉回 Cobblemon 的原生慢速。
            entity.getNavigation().moveTo(order.target.x, order.target.y, order.target.z,
                    navigationSpeed(entity));
            order.lastNavGoal = order.target;
            order.repathCooldown = REPATH_INTERVAL;
        }
    }

    /**
     * 把"目标格/秒"换算成原版寻路的 speedModifier。
     *
     * <p>原版 {@code PathNavigation} 的实际速度 = {@code MOVEMENT_SPEED} 属性 × modifier，
     * 而 Cobblemon 是按种族速度值给宝可梦设属性的，普通宝可梦可能低到 0.1。
     * 这里反算出一个能让实际速度达到 {@link #WALK_SPEED} 的 modifier，并夹在合理区间内
     * —— 属性缺失/异常时退回 1.2，与旧行为一致。
     */
    private static double navigationSpeed(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        double attr = entity.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (attr <= 1.0E-4) {
            return NAV_SPEED * com.pkmoba.combat.VenusaurCombat.movementSpeedMultiplier(entity) * com.pkmoba.combat.PikachuCombat.movementSpeedMultiplier(entity) * com.pkmoba.combat.ExpansionCombat.movementMultiplier(entity);
        }
        // 属性单位是"格/秒"，乘 20 得"格/tick"；目标速度除以它即得 modifier。
        double modifier = WALK_SPEED * 20.0 / attr;
        return net.minecraft.util.Mth.clamp(modifier, 0.8, 3.0)
                * com.pkmoba.combat.VenusaurCombat.movementSpeedMultiplier(entity) * com.pkmoba.combat.PikachuCombat.movementSpeedMultiplier(entity) * com.pkmoba.combat.ExpansionCombat.movementMultiplier(entity);
    }

    /**
     * 直线前进一帧。可行时返回 true（已处理移动），被挡时返回 false 交给寻路。
     */
    private static boolean straightWalk(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity,
                                        Order order, Vec3 flatDir, double horizontal) {
        // 距离远时用疾跑速度：既不拖沓，也不会因为"一直点远处"而显得比近处慢。
        double speed = (horizontal > 6.0 ? SPRINT_SPEED : WALK_SPEED)
                * com.pkmoba.combat.VenusaurCombat.movementSpeedMultiplier(entity) * com.pkmoba.combat.PikachuCombat.movementSpeedMultiplier(entity) * com.pkmoba.combat.ExpansionCombat.movementMultiplier(entity);
        double step = Math.min(horizontal, speed);
        double width = Math.max(0.35, entity.getBbWidth() * 0.5);
        Vec3 probe = new Vec3(flatDir.x * Math.max(step, 0.4), 0, flatDir.z * Math.max(step, 0.4));
        if (!canStep(entity, probe, width)) {
            // 直线被挡：交给 A*，这样绕障碍/跳台阶都有原生逻辑兜底
            entity.getNavigation().stop();
            return false;
        }

        Vec3 before = entity.position();
        Vec3 wanted = before.add(flatDir.scale(step));
        Vec3 after = com.pkmoba.combat.CharizardCombat.clampToArena(entity, wanted);
        if (after.distanceToSqr(wanted) > 1.0E-6) {
            // 到了场地边界：贴边停，不再硬推
            order.target = null;
            order.lastNavGoal = null;
            entity.getNavigation().stop();
            entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
            return true;
        }
        Vec3 delta = after.subtract(before);
        if (delta.lengthSqr() > STEP_CLAMP * STEP_CLAMP) {
            delta = delta.normalize().scale(STEP_CLAMP);
        }
        // 只喂水平位移：纵向交给原版重力/台阶逻辑，避免"悬空漂浮"或"陷进地板"
        Vec3 motion = new Vec3(delta.x, 0, delta.z);
        if (motion.lengthSqr() < 1.0E-8) {
            return false;
        }
        entity.getNavigation().stop();
        entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
        entity.move(net.minecraft.world.entity.MoverType.SELF, motion);
        keepStepUp(entity, motion);
        faceTowards(entity, flatDir);
        return true;
    }

    /**
     * 探测 {@code probe} 处能否站立：前方没有高墙、脚下有可站面、落差不超过一格。
     *
     * <p>修 Bug 记录：旧版探测盒写成 {@code new AABB(-w, -0.2, -w, w, 1.4, w).move(position())}，
     * 而 {@code position()} 是**脚底**，-0.2 直接埋进地面方块 → {@code noCollision} 恒为 false
     * → 直线行走从未生效，全程退化到 A* 寻路，这就是"普通宝可梦移动太慢"的根因。
     * 现在探测盒从脚底 +0.02 起算，另用竖直 clip 单独判断"脚下是否有可站地面"。
     */
    private static boolean canStep(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity,
                                   Vec3 probe, double width) {
        var level = entity.level();
        Vec3 at = entity.position().add(probe);
        // 目标位置脚下必须有可站面（允许上下不超过一格台阶）
        double ground = com.pkmoba.combat.CharizardCombat.groundBelow(
                (net.minecraft.server.level.ServerLevel) level, entity, at);
        if (!Double.isFinite(ground) || ground - entity.getY() > STEP_UP || entity.getY() - ground > STEP_UP) {
            return false;
        }
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(at);
        var shape = level.getBlockState(pos).getCollisionShape(level, pos);
        // 目标格有实心碰撞箱 = 前面是墙/台阶，需要寻路处理抬升
        if (!shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) > STEP_UP) {
            return false;
        }
        // 身体盒：从脚底 +0.02 起算，绝不埋进地面（这是旧版恒 false 的那个 Bug）
        net.minecraft.world.phys.AABB feet = new net.minecraft.world.phys.AABB(
                -width, 0.02, -width, width, 1.4, width).move(at);
        return level.noCollision(entity, feet) && !com.pkmoba.combat.ExpansionWalls.blocked(entity, feet);
    }

    /** 台阶抬升后避免身体卡在地面里。 */
    private static void keepStepUp(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity, Vec3 motion) {
        double ground = com.pkmoba.combat.CharizardCombat.groundBelow(
                (net.minecraft.server.level.ServerLevel) entity.level(), entity, entity.position());
        if (!Double.isFinite(ground)) {
            return;
        }
        double dy = ground - entity.getY();
        if (dy > 0.0 && dy <= STEP_UP) {
            entity.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0, dy, 0));
        }
    }

    /**
     * 转向到位：模型是"转过去"的，而不是瞬间把 YRot 拍过去。
     *
     * <p>0.38 起不再自己写 {@code setYRot/setYHeadRot/setYBodyRot/hurtMarked}，
     * 统一交给 {@link com.pkmoba.combat.FacingService}（MOVE 优先级，最低），
     * 避免与普攻/引导技能在同 tick 抢写朝向 —— 那正是"模型头乱晃"的根因。
     *
     * <p>0.48.5：预瞄（{@link com.pkmoba.combat.AimTracker}）与施法锁（{@link com.pkmoba.combat.CastLock}）
     * 生效时这里直接不写朝向，移动照旧 —— "边走边跟鼠标"由预瞄负责。
     */
    private static void faceTowards(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity, Vec3 flatDir) {
        // Active path movement owns facing; cast lock remains absolute during a skill animation.
        if (com.pkmoba.combat.CastLock.active(entity)) return;
        com.pkmoba.combat.FacingService.request(entity,
                com.pkmoba.combat.FacingService.Priority.MOVE, flatDir, TURN_PER_TICK);
    }

    /** 用户切出上帝视角 / 宝可梦消失时清空所有指令。 */
    public static void clear() {
        ORDERS.clear();
        com.pkmoba.combat.FacingService.clear();
    }

    /**
     * 0.48.5：这只宝可梦当前是否由<b>原版寻路</b>在驱动移动（直线被挡后的 A* 兜底）。
     *
     * <p>原版 {@code PathNavigation} 是"先转身、再朝身体正前方走"（{@code MoveControl} 改 yRot，
     * {@code travel} 沿 yRot 位移）。如果这时候还强行把朝向锁到鼠标，它会一边往鼠标方向斜着走、
     * 一边被寻路往回拧，表现为蹭墙/走不动。所以这种兜底路线里朝向让给寻路；
     * 直线行走（绝大多数情况）依旧"边走边跟鼠标"。
     */
    public static boolean navigationSteering(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        if (entity == null) return false;
        Order order = ORDERS.get(entity.getUUID());
        if (order == null || order.target == null) return false;
        return !entity.getNavigation().isDone();
    }

    /** Whether this entity currently has a pending ground-move destination. */
    public static boolean hasOrder(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        if (entity == null) return false;
        Order order = ORDERS.get(entity.getUUID());
        return order != null && order.target != null;
    }

    /** 只清某一只宝可梦的移动指令（1v1 里另一只不受影响）。 */
    public static void clear(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        if (entity != null) {
            ORDERS.remove(entity.getUUID());
            com.pkmoba.combat.FacingService.clear(entity);
        }
    }

    @Nullable
    public static Vec3 getTarget(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        Order order = entity == null ? null : ORDERS.get(entity.getUUID());
        return order == null ? null : order.target;
    }
}
