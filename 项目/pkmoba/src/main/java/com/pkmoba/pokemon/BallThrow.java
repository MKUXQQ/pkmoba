package com.pkmoba.pokemon;

import com.pkmoba.PkmobaMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.MinecraftServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 投球召唤 + 登场硬直（方案 §三.3 / §三.4）。
 *
 * <p>流程：从玩家眼睛位置向落点抛出一条抛物线（服务端每 tick 沿轨迹撒精灵球粒子 + 出手音效），
 * 落地那一刻才真正生成宝可梦（交给调用方传进来的 {@code spawn} 动作），并给这只宝可梦上
 * {@link #SPAWN_LOCK_TICKS} 的登场硬直 —— 期间不许移动、不许普攻、不许放技能。
 *
 * <p>用"延时触发"而不是真的生成一个投掷物实体，是因为 Cobblemon 的精灵球实体与它自己的
 * 捕捉/放出流程绑在一起，直接用会在放出时又跑一遍显形动画；这里只要"看起来是投出来的"，
 * 显形动画交给项目已有的登场动作（{@code CharizardSkillAnimator} / {@code special}）。
 */
public final class BallThrow {
    /** 登场硬直：2 秒（方案 §三.4）。 */
    public static final int SPAWN_LOCK_TICKS = 14;

    /** 一次待落地的投球。 */
    private static final class Pending {
        final ServerLevel level;
        final UUID player;
        final Vec3 from;
        final Vec3 to;
        final List<Vec3> points;
        final long flightId;
        int checked;
        final int startTick;
        final int duration;
        final Consumer<Vec3> spawn;
        final boolean shortProfile;

        Pending(ServerLevel level, UUID player, Vec3 from, Vec3 to, int startTick, int duration,
                List<Vec3> points, long flightId, Consumer<Vec3> spawn, boolean shortProfile) {
            this.level = level;
            this.player = player;
            this.from = from;
            this.to = to;
            this.points = points;
            this.flightId = flightId;
            this.startTick = startTick;
            this.duration = duration;
            this.spawn = spawn;
            this.shortProfile = shortProfile;
        }
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    /** 登场硬直到期时间（server tick）。 */
    private static final Map<UUID, Long> LOCKS = new HashMap<>();

    private BallThrow() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(BallThrow::tick);
    }

    private static long nextFlight;

    /** Shared summon path; its existing range and trajectory remain unchanged for /mo summon. */
    public static void throwBall(ServerPlayer player, Vec3 landing, Consumer<Vec3> spawn) {
        throwBallInternal(player, landing, spawn, false);
    }

    /** X-key profile with a server-derived aim point, 8-block cap, and classic 3-block minimum. */
    public static void throwBallShort(ServerPlayer player, Consumer<Vec3> spawn) {
        if (player == null || !player.isAlive()) return;
        Vec3 from = player.getEyePosition().add(0, -0.15, 0);
        Vec3 rayEnd = player.getEyePosition().add(player.getLookAngle().scale(8.0));
        var aimHit = player.serverLevel().clip(new net.minecraft.world.level.ClipContext(
                player.getEyePosition(), rayEnd, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.ANY, player));
        Vec3 aim = aimHit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                ? rayEnd : aimHit.getLocation();
        Vec3 flatAim = new Vec3(aim.x - from.x, 0, aim.z - from.z);
        if (flatAim.lengthSqr() < 1.0e-6) flatAim = new Vec3(player.getLookAngle().x, 0, player.getLookAngle().z);
        Vec3 direction = flatAim.normalize();
        Vec3 landing = aim;
        double horizontal = new Vec3(aim.x - from.x, 0, aim.z - from.z).length();
        if (horizontal < 3.0) {
            double forward = Math.sqrt(Math.max(0, 9.0 - horizontal * horizontal));
            Vec3 candidate = aim.add(direction.scale(forward));
            Vec3 candidateEnd = candidate.add(0, -16, 0);
            var ground = player.serverLevel().clip(new net.minecraft.world.level.ClipContext(candidate.add(0, 2, 0),
                    candidateEnd, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.ANY, player));
            if (ground.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                    && ground.getDirection() == net.minecraft.core.Direction.UP
                    && player.serverLevel().getFluidState(ground.getBlockPos()).isEmpty()) {
                landing = ground.getLocation();
            }
        }
        throwBallInternal(player, landing, spawn, true);
    }

    /** Aim is only a hint: the server sweeps every segment and uses its first world collision. */
    private static void throwBallInternal(ServerPlayer player, Vec3 landing, Consumer<Vec3> spawn, boolean shortProfile) {
        if (landing == null || !Double.isFinite(landing.x) || !Double.isFinite(landing.y)
                || !Double.isFinite(landing.z)) return;
        Vec3 eye = player.getEyePosition();
        double capSqr = shortProfile ? 7.9 * 7.9 : 255.0 * 255.0;
        if (eye.distanceToSqr(landing) > capSqr) {
            landing = eye.add(landing.subtract(eye).normalize().scale(shortProfile ? 7.9 : 255.0));
        }
        clearPlayer(player.getUUID());
        Vec3 from = player.getEyePosition().add(0, -0.15, 0);
        int duration = shortProfile ? 12 : (int)Math.max(8, Math.min(48, Math.round(from.distanceTo(landing)*0.9)));
        List<Vec3> points = new ArrayList<>(); points.add(from);
        Vec3 previous = from;
        boolean landed = false;
        // The long summon path keeps sweeping past the aim point until real terrain is hit.
        // The X throw must never travel beyond its aim: it stops at the end of the arc and then
        // drops straight down a short way, so "throw 5 blocks" can never summon 20 blocks away.
        int steps = shortProfile ? duration : duration + 80;
        for (int i=1;i<=steps;i++) {
            Vec3 next = parabola(from, landing, i/(double)duration);
            if (!player.serverLevel().hasChunkAt(net.minecraft.core.BlockPos.containing(next))
                    || !player.serverLevel().getWorldBorder().isWithinBounds(net.minecraft.core.BlockPos.containing(next))) break;
            var hit = player.serverLevel().clip(new net.minecraft.world.level.ClipContext(previous, next,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.ANY, player));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                Vec3 contact = hit.getLocation();
                points.add(contact);
                // A wall/ceiling is an impact, not permission to teleport through it or onto its roof.
                landed = hit.getDirection() == net.minecraft.core.Direction.UP
                        && player.serverLevel().getFluidState(hit.getBlockPos()).isEmpty();
                break;
            }
            points.add(next); previous=next;
        }
        if (shortProfile && !landed) {
            // Short throw fell through open air: look for the ground directly under the last point.
            Vec3 top = points.get(points.size() - 1);
            var drop = player.serverLevel().clip(new net.minecraft.world.level.ClipContext(
                    top, top.add(0, -5.0, 0), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.ANY, player));
            if (drop.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                    && drop.getDirection() == net.minecraft.core.Direction.UP
                    && player.serverLevel().getFluidState(drop.getBlockPos()).isEmpty()) {
                points.add(drop.getLocation());
                landed = true;
            } else {
                points.add(top);
            }
        }
        if (points.size()<2) return;
        long flightId=++nextFlight;
        Vec3 impact=points.get(points.size()-1);
        final boolean safeImpact=landed;
        final boolean minimumRange = shortProfile;
        final Vec3 launchOrigin = player.position();
        Consumer<Vec3> finish=at -> {
            if (shortProfile) {
                // X-key summon must always work: resolve the nearest legal standing spot instead of
                // cancelling. User rule: if the aimed spot is blocked, lift it one block at a time.
                spawn.accept(safeSpot(player.serverLevel(), player, at));
                return;
            }
            if (!safeImpact) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("精灵球撞到障碍或未落在安全地面，召唤已取消。"),true);
                return;
            }
            if (minimumRange && new Vec3(at.x - launchOrigin.x, 0, at.z - launchOrigin.z).lengthSqr() < 9.0) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("安全落点不足 3 格，召唤已取消。"), true);
                return;
            }
            if (minimumRange && new Vec3(at.x - launchOrigin.x, 0, at.z - launchOrigin.z).lengthSqr() > 8.4 * 8.4) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("召唤距离超过 8 格，召唤已取消。"), true);
                return;
            }
            var support=player.serverLevel().clip(new net.minecraft.world.level.ClipContext(
                    at.add(0,.02,0),at.add(0,-.08,0),net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.ANY,player));
            if(support.getType()==net.minecraft.world.phys.HitResult.Type.MISS
                    || support.getDirection()!=net.minecraft.core.Direction.UP
                    || !player.serverLevel().getFluidState(support.getBlockPos()).isEmpty()) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("落点地面已改变，召唤已取消。"),true);
                return;
            }
            spawn.accept(at);
        };
        Pending pending = new Pending(player.serverLevel(),player.getUUID(),from,impact,
                player.server.getTickCount(),points.size()-1,List.copyOf(points),flightId,finish,shortProfile);
        PENDING.add(pending);
        broadcastFlight(pending.level,flightId,pending.points);
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.SNOWBALL_THROW,
                SoundSource.PLAYERS, 0.8F, 1.1F);
    }

    /**
     * 广播一条飞行轨迹给附近玩家，由客户端自行渲染精灵球（0.41.8）。
     *
     * <p>不再生成 {@code ItemEntity}：物品实体的位置同步在客户端会卡在出手点（用户实测"球一直卡在头顶"），
     * 而客户端自己插值渲染的轨迹绝对平滑，也避免世界里残留实体。
     */
    private static void broadcastFlight(ServerLevel level, long id, List<Vec3> points) {
        var payload = new com.pkmoba.network.BallFlightPayload(id, points);
        for (ServerPlayer viewer : level.players()) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(viewer, payload);
        }
    }

    /** 给实体上台登场的硬直（重复调用取更晚的到期时间）。 */
    public static void applyLock(Entity entity, int ticks) {
        if (entity == null) {
            return;
        }
        long until = (entity.level() instanceof ServerLevel level ? level.getGameTime() : 0L) + ticks;
        LOCKS.merge(entity.getUUID(), until, Math::max);
    }

    /** State transitions may release a shorter replacement wait immediately. */
    public static void releaseLock(Entity entity) {
        if (entity != null) LOCKS.remove(entity.getUUID());
    }

    /** 是否还在登场硬直里。 */
    public static boolean locked(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (com.pkmoba.duel.DuelManager.isPreparing(entity)) {
            return true;
        }
        Long until = LOCKS.get(entity.getUUID());
        if (until == null) {
            return false;
        }
        long now = entity.level() instanceof ServerLevel level ? level.getGameTime() : 0L;
        if (now >= until) {
            LOCKS.remove(entity.getUUID());
            return false;
        }
        return true;
    }

    /** 剩余硬直 tick（音效/提示用）。 */
    public static int remaining(Entity entity) {
        if (!locked(entity)) {
            return 0;
        }
        Long until = LOCKS.get(entity.getUUID());
        long now = entity.level() instanceof ServerLevel level ? level.getGameTime() : 0L;
        return until == null ? 0 : (int) Math.max(0, until - now);
    }

    private static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            ServerPlayer owner=server.getPlayerList().getPlayer(p.player);
            if (owner==null || owner.hasDisconnected() || owner.serverLevel()!=p.level || !owner.isAlive()) {
                broadcastFlight(p.level,p.flightId,List.of()); it.remove(); continue;
            }
            int reached=Math.min(p.duration,Math.max(0,now-p.startTick));
            boolean obstructed=false;
            for (int step=p.checked+1;step<=reached;step++) {
                Vec3 a=p.points.get(step-1), b=p.points.get(step);
                if (!p.level.hasChunkAt(net.minecraft.core.BlockPos.containing(b))) { obstructed=true; break; }
                var hit=p.level.clip(new net.minecraft.world.level.ClipContext(a,b,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.ANY,owner));
                if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.MISS
                        && (step!=p.duration || hit.getLocation().distanceToSqr(p.to)>.0001)) { obstructed=true; break; }
            }
            p.checked=reached;
            if(obstructed) {
                broadcastFlight(p.level,p.flightId,List.of()); it.remove();
                if (p.shortProfile) {
                    // X-key throws never fail on an obstructed arc: fall back to the legal spot.
                    try {
                        p.spawn.accept(safeSpot(p.level, owner, p.to));
                    } catch (Throwable error) {
                        PkmobaMod.LOGGER.error("[pkmoba] X 投球受阻后的兜底召唤失败", error);
                    }
                    continue;
                }
                owner.displayClientMessage(net.minecraft.network.chat.Component.literal("飞行路线被阻挡，召唤已取消。"),true);
                continue;
            }
            double t = (now - p.startTick) / (double) Math.max(1, p.duration);
            if (t >= 1.0D) {
                it.remove();
                broadcastFlight(p.level,p.flightId,List.of());
                try {
                    p.spawn.accept(p.to);
                } catch (Throwable error) {
                    PkmobaMod.LOGGER.error("[pkmoba] 投球落地生成宝可梦失败，已吞掉以免崩服", error);
                }
                p.level.playSound(null, net.minecraft.core.BlockPos.containing(p.to),
                        SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0F, 1.0F);
                continue;
            }
            // 投球模型由客户端绘制；召唤不再附带粒子拖尾。
        }
    }

    private static final net.minecraft.world.phys.AABB SUMMON_PROBE =
            new net.minecraft.world.phys.AABB(-0.36, 0, -0.36, 0.36, 1.9, 0.36);

    /**
     * X 键召唤的落点兜底：永不返回失败。
     *
     * <p>顺序为「命中点本身（需有地面支撑）→ 向下最多 8 格找地面 → 从命中点逐格抬升（用户要求
     * 默认上升一格）→ 保底命中点上方一格」。这样瞄准水面、树叶、台阶、斜坡甚至半空墙边时
     * 都不会再弹“该位置不能召唤”。
     */
    private static Vec3 safeSpot(ServerLevel level, ServerPlayer owner, Vec3 target) {
        if (spotFree(level, owner, target) && !spotFree(level, owner, target.add(0, -0.35, 0))) return target;
        // User rule: when the aimed spot is blocked, the X summon rises one block instead of failing.
        // Walk upward before falling back to the ground below, so a thrown ball never silently
        // relocates away from where the player pointed.
        for (int up = 1; up <= 5; up++) {
            Vec3 at = target.add(0, up, 0);
            if (spotFree(level, owner, at)) return at;
        }
        for (int down = 1; down <= 8; down++) {
            Vec3 at = target.add(0, -down, 0);
            if (spotFree(level, owner, at) && !spotFree(level, owner, at.add(0, -0.35, 0))) return at;
        }
        return target.add(0, 1, 0);
    }

    private static boolean spotFree(ServerLevel level, ServerPlayer owner, Vec3 at) {
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(at);
        if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) return false;
        return level.noCollision(owner, SUMMON_PROBE.move(at));
    }

    /** 抛物线：线性插值 + 向上拱起，落点越远拱得越高。 */
    private static Vec3 parabola(Vec3 from, Vec3 to, double t) {        Vec3 flat = from.lerp(to, t);
        double peak = 0.85 + from.distanceTo(to) * 0.10;
        double lift = 4.0 * peak * t * (1.0 - t);
        return flat.add(0, lift, 0);
    }

    /** X 再按一次时取消仍在飞行中的召唤，避免落地后重新出现。 */
    public static boolean hasPending(UUID player) {
        return PENDING.stream().anyMatch(p -> p.player.equals(player));
    }

    /** 清掉某玩家的待落地投球（退出/换维度时用）。 */
    public static void clearPlayer(UUID player) {
        PENDING.removeIf(p -> {
            if (!p.player.equals(player)) return false;
            broadcastFlight(p.level,p.flightId,List.of());
            return true;
        });
    }

    /** 清掉所有登记（服务器停止时用）。 */
    public static void reset() {
        for (Pending pending : PENDING) broadcastFlight(pending.level, pending.flightId, List.of());
        PENDING.clear();
        LOCKS.clear();
    }

    /** 物种定位符（日志用，避免调用方再引入依赖）。 */
    public static String describe(ResourceLocation id) {
        return id == null ? "unknown" : id.toString();
    }
}
