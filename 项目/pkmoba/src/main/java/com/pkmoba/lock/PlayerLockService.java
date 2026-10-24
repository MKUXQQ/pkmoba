package com.pkmoba.lock;

import com.pkmoba.godmode.ServerGodMode;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 保存进入前状态，并在服务端锁住升空后的 XYZ；生命周期由 ServerGodMode 统一退出。 */
public final class PlayerLockService {
    private record Lock(ResourceKey<Level> dimension, Vec3 origin, Vec3 hover,
                        float yaw, float pitch, boolean noGravity, boolean invulnerable,
                        boolean flying, boolean mayfly, float flyingSpeed) { }
    // 实体 tags 会随玩家 NBT 保存；异常关闭后也能取回本模组改动前的状态。
    private static final String SNAPSHOT_PREFIX = "pkmoba.godlock.v1;";
    private static final Map<UUID, Lock> LOCKS = new HashMap<>();
    private static boolean initialized;
    private PlayerLockService() { }

    public static void init() {
        if (initialized) return;
        initialized = true;
        ServerTickEvents.END_SERVER_TICK.register(PlayerLockService::onServerTick);
        // 不在这里提前 remove：断线也必须先恢复，再由统一退出流程移除记录。
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                isLocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) -> isLocked(player)
                ? net.minecraft.world.InteractionResultHolder.fail(player.getItemInHand(hand))
                : net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand)));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                isLocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                isLocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !isLocked(player));
    }

    /** 精确在原位上方 12 格检查整个玩家碰撞箱；失败时不修改任何状态。 */
    public static boolean lock(ServerPlayer player) {
        if (isLocked(player)) return true;
        // 先清理上一次异常结束留下的 NBT；不能把旧 NoGravity=true 再保存为正常状态。
        recoverInactive(player, false);
        Vec3 origin = player.position();
        Vec3 hover = origin.add(0, 12, 0);
        if (!safe(player.serverLevel(), player, hover, false)) return false;
        Lock lock = new Lock(player.level().dimension(), origin, hover, player.getYRot(), player.getXRot(),
                player.isNoGravity(), player.isInvulnerable(), player.getAbilities().flying,
                player.getAbilities().mayfly, player.getAbilities().getFlyingSpeed());
        LOCKS.put(player.getUUID(), lock);
        player.addTag(SNAPSHOT_PREFIX + lock.dimension().location() + ";"
                + origin.x + ";" + origin.y + ";" + origin.z + ";"
                + lock.yaw() + ";" + lock.pitch() + ";" + lock.noGravity() + ";"
                + lock.invulnerable() + ";" + lock.flying() + ";" + lock.mayfly() + ";" + lock.flyingSpeed());
        player.setNoGravity(true);
        player.setInvulnerable(true);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        player.teleportTo(player.serverLevel(), hover.x, hover.y, hover.z, lock.yaw(), lock.pitch());
        stopMotion(player);
        return true;
    }

    public static void unlock(ServerPlayer player) { unlock(player, true); }

    /** returnPosition=false 用于死亡、重生、换维度，绝不把新实体拉回旧世界。 */
    public static void unlock(ServerPlayer player, boolean returnPosition) {
        Lock lock = LOCKS.remove(player.getUUID());
        if (lock == null) lock = readSnapshot(player);
        if (lock == null) return;
        if (returnPosition && player.isAlive() && player.level().dimension().equals(lock.dimension())) {
            Vec3 destination = findReturn(player, lock);
            if (destination != null) {
                player.teleportTo(player.serverLevel(), destination.x, destination.y, destination.z,
                        lock.yaw(), lock.pitch());
            } else {
                // 极端情况下原点及附近均被填满：使用本维度出生点附近的安全地面。
                BlockPos spawn = player.serverLevel().getSharedSpawnPos();
                Vec3 fallback = findNearby(player, Vec3.atBottomCenterOf(spawn), 8, false);
                if (fallback != null) player.teleportTo(player.serverLevel(), fallback.x, fallback.y, fallback.z,
                        lock.yaw(), lock.pitch());
                else player.displayClientMessage(net.minecraft.network.chat.Component.literal("未找到安全返回位置，已解除锁定。"), false);
            }
        }
        player.setNoGravity(lock.noGravity());
        player.setInvulnerable(lock.invulnerable());
        player.getAbilities().flying = lock.flying();
        player.getAbilities().mayfly = lock.mayfly();
        player.getAbilities().setFlyingSpeed(lock.flyingSpeed());
        player.onUpdateAbilities();
        stopMotion(player);
        clearSnapshot(player);
    }

    /** 仅本模组持久标记自动修复；无标记旧存档只在用户明确退出时纠错。 */
    public static void recoverInactive(ServerPlayer player, boolean explicitExit) {
        if (isLocked(player) || readSnapshot(player) != null) {
            unlock(player, true);
        } else if (explicitExit && !player.isCreative() && !player.isSpectator()
                && !player.getAbilities().mayfly && player.isNoGravity()) {
            player.setNoGravity(false);
            player.setInvulnerable(false);
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            stopMotion(player);
            com.pkmoba.PkmobaMod.LOGGER.warn("[pkmoba] 显式退出纠正无旧快照的生存/冒险玩家 NoGravity: {}", player.getUUID());
        }
    }

    private static void clearSnapshot(ServerPlayer player) {
        for (String tag : new java.util.HashSet<>(player.getTags())) {
            if (tag.startsWith(SNAPSHOT_PREFIX)) player.removeTag(tag);
        }
    }

    private static Lock readSnapshot(ServerPlayer player) {
        for (String tag : player.getTags()) {
            if (!tag.startsWith(SNAPSHOT_PREFIX)) continue;
            try {
                String[] v = tag.substring(SNAPSHOT_PREFIX.length()).split(";", -1);
                if (v.length != 11) throw new IllegalArgumentException("field count");
                ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(v[0]));
                Vec3 origin = new Vec3(Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3]));
                float yaw = Float.parseFloat(v[4]), pitch = Float.parseFloat(v[5]), speed = Float.parseFloat(v[10]);
                if (!Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(origin.z)
                        || !Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(speed))
                    throw new IllegalArgumentException("non-finite snapshot");
                return new Lock(dimension, origin, origin.add(0, 12, 0), yaw, pitch,
                        Boolean.parseBoolean(v[6]), Boolean.parseBoolean(v[7]), Boolean.parseBoolean(v[8]),
                        Boolean.parseBoolean(v[9]), speed);
            } catch (RuntimeException error) {
                com.pkmoba.PkmobaMod.LOGGER.warn("[pkmoba] 无效上帝视角恢复标记: {}", player.getUUID(), error);
            }
        }
        return null;
    }

    private static Vec3 findReturn(ServerPlayer player, Lock lock) {
        boolean allowAir = lock.flying() || lock.noGravity();
        if (safe(player.serverLevel(), player, lock.origin(), !allowAir)) return lock.origin();
        return findNearby(player, lock.origin(), 8, allowAir);
    }

    private static Vec3 findNearby(ServerPlayer player, Vec3 center, int radius, boolean allowAir) {
        for (int ring = 0; ring <= radius; ring++) {
            for (int dy = 0; dy <= 16; dy++) {
                for (int sign : new int[]{1, -1}) {
                    if (dy == 0 && sign == -1) continue;
                    for (int dx = -ring; dx <= ring; dx++) {
                        for (int dz = -ring; dz <= ring; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                            Vec3 candidate = center.add(dx, dy * sign, dz);
                            if (safe(player.serverLevel(), player, candidate, !allowAir)) return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean safe(ServerLevel level, ServerPlayer player, Vec3 position, boolean requireSupport) {
        AABB box = player.getBoundingBox().move(position.subtract(player.position()));
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(box)) return false;
        // 禁止为了试探位置同步生成未加载区块。
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        if (!level.hasChunksAt(min, max) || !level.noCollision(player, box) || level.containsAnyLiquid(box)) return false;
        return !requireSupport || !level.noCollision(player, box.move(0, -0.1, 0));
    }

    public static boolean isLocked(Player player) { return LOCKS.containsKey(player.getUUID()); }
    private static void stopMotion(ServerPlayer player) {
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        player.hurtMarked = true;
    }
    private static void onServerTick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Lock lock = LOCKS.get(player.getUUID());
            if (!ServerGodMode.isActive(player)) {
                if (lock != null) ServerGodMode.forceOff(player);
                continue;
            }
            if (lock == null) {
                ServerGodMode.forceOff(player);
                continue;
            }
            if (!player.isAlive() || !player.level().dimension().equals(lock.dimension())) {
                ServerGodMode.forceOffWithoutReturn(player);
                continue;
            }
            player.setNoGravity(true);
            player.setPos(lock.hover().x, lock.hover().y, lock.hover().z);
            stopMotion(player);
        }
    }
}
