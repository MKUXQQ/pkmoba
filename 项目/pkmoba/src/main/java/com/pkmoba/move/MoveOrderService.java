package com.pkmoba.move;

import com.pkmoba.PkmobaMod;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.godmode.ServerGodMode;
import com.pkmoba.network.MoveAckPayload;
import com.pkmoba.network.MoveOrderPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

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
    /** 水平距离小于这个值算到达。0.5 格是"看得准"和"不抖动"的折中。 */
    private static final double ARRIVE_DISTANCE = 0.5;
    private static final double NAV_SPEED = 1.2;
    private static final double MAX_ORDER_DISTANCE = 96.0;
    private static final int REPATH_INTERVAL = 10;

    /** 每只受控宝可梦一份移动指令。key = 实体 UUID。 */
    private static final Map<UUID, Order> ORDERS = new HashMap<>();

    private static final class Order {
        @Nullable Vec3 target;
        int repathCooldown;
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
        // A legitimate owner's ground order cancels pursuit even if the destination is rejected.
        com.pkmoba.combat.BasicAttack.cancel(entity);
        if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) {
            ack(player, entity.position(), false);
            return;
        }
        double dist = Math.sqrt(entity.distanceToSqr(pos));
        if (dist > MAX_ORDER_DISTANCE) {
            PkmobaMod.LOGGER.warn("[pkmoba] 移动指令超出范围 {} 格，已忽略", String.format("%.1f", dist));
            ack(player, pos, false);
            return;
        }

        // B-3：目标点截断到场地圆内（不再按"离玩家 48 格"硬拦）
        Vec3 clamped = com.pkmoba.combat.CharizardCombat.clampToArena(pos);
        if (clamped.distanceTo(pos) > 0.01) {
            PkmobaMod.LOGGER.info("[pkmoba] 移动目标在场地外，已截断到边界");
            pos = clamped;
        }
        order(entity).target = pos;
        order(entity).repathCooldown = 0;
        PkmobaMod.LOGGER.info("[pkmoba] 移动指令 entity={} -> ({}, {}, {})", entity.getUUID(),
                String.format("%.2f", pos.x), String.format("%.2f", pos.y), String.format("%.2f", pos.z));
        ack(player, pos, true);
    }

    private static void ack(ServerPlayer player, Vec3 pos, boolean accepted) {
        if (ServerPlayNetworking.canSend(player, MoveAckPayload.TYPE)) {
            ServerPlayNetworking.send(player, new MoveAckPayload(pos.x, pos.y, pos.z, accepted));
        }
    }

    private static Order order(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        return ORDERS.computeIfAbsent(entity.getUUID(), id -> new Order());
    }

    private static void tick(MinecraftServer server) {
        // Exactly one arbiter owns navigation. Validate attack lifecycle even after removal.
        com.pkmoba.combat.BasicAttack.tick(server);
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
        if (com.pkmoba.combat.BasicAttack.hasTarget(entity)) return;
        // Skill channel/dash owns locomotion. Keep queued ground destination for later.
        if (com.pkmoba.combat.CharizardCombat.blocksMovement(entity)) {
            entity.getNavigation().stop();
            return;
        }

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
            entity.getNavigation().stop();
            entity.setDeltaMovement(0.0, entity.getDeltaMovement().y, 0.0);
            return;
        }

        if (order.repathCooldown > 0) {
            order.repathCooldown--;
            return;
        }

        entity.getNavigation().moveTo(order.target.x, order.target.y, order.target.z, NAV_SPEED);
        order.repathCooldown = REPATH_INTERVAL;
    }

    /** 用户切出上帝视角 / 宝可梦消失时清空所有指令。 */
    public static void clear() {
        ORDERS.clear();
    }

    /** 只清某一只宝可梦的移动指令（1v1 里另一只不受影响）。 */
    public static void clear(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        if (entity != null) ORDERS.remove(entity.getUUID());
    }

    @Nullable
    public static Vec3 getTarget(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        Order order = entity == null ? null : ORDERS.get(entity.getUUID());
        return order == null ? null : order.target;
    }
}
