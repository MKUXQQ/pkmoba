package com.pkmoba.pokemon;

import com.pkmoba.PkmobaMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

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
    public static final int SPAWN_LOCK_TICKS = 40;

    /** 一次待落地的投球。 */
    private static final class Pending {
        final ServerLevel level;
        final UUID player;
        final Vec3 from;
        final Vec3 to;
        final int startTick;
        final int duration;
        final Consumer<Vec3> spawn;

        Pending(ServerLevel level, UUID player, Vec3 from, Vec3 to, int startTick, int duration,
                Consumer<Vec3> spawn) {
            this.level = level;
            this.player = player;
            this.from = from;
            this.to = to;
            this.startTick = startTick;
            this.duration = duration;
            this.spawn = spawn;
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

    /** 抛一颗精灵球到 {@code landing}，落地后执行 {@code spawn}。 */
    public static void throwBall(ServerPlayer player, Vec3 landing, Consumer<Vec3> spawn) {
        Vec3 from = player.getEyePosition().add(0, -0.15, 0);
        double dist = from.distanceTo(landing);
        int duration = (int) Math.max(8, Math.min(24, Math.round(dist * 0.9)));
        Pending pending = new Pending(player.serverLevel(), player.getUUID(), from, landing,
                player.server.getTickCount(), duration, spawn);
        PENDING.add(pending);
        broadcastFlight(player, from, landing, duration);
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.SNOWBALL_THROW,
                SoundSource.PLAYERS, 0.8F, 1.1F);
    }

    /**
     * 广播一条飞行轨迹给附近玩家，由客户端自行渲染精灵球（0.41.8）。
     *
     * <p>不再生成 {@code ItemEntity}：物品实体的位置同步在客户端会卡在出手点（用户实测"球一直卡在头顶"），
     * 而客户端自己插值渲染的轨迹绝对平滑，也避免世界里残留实体。
     */
    private static void broadcastFlight(ServerPlayer player, Vec3 from, Vec3 to, int duration) {
        com.pkmoba.network.BallFlightPayload payload = new com.pkmoba.network.BallFlightPayload(
                from.x, from.y, from.z, to.x, to.y, to.z, duration, "cobblemon:poke_ball",
                0L);
        for (ServerPlayer viewer : player.serverLevel().players()) {
            if (viewer.distanceToSqr(from) > 96.0D * 96.0D) {
                continue;
            }
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

    /** 是否还在登场硬直里。 */
    public static boolean locked(Entity entity) {
        if (entity == null) {
            return false;
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
            if (p.level.getServer() == null) {
                it.remove();
                continue;
            }
            double t = (now - p.startTick) / (double) Math.max(1, p.duration);
            if (t >= 1.0D) {
                it.remove();
                try {
                    p.spawn.accept(p.to);
                } catch (Throwable error) {
                    PkmobaMod.LOGGER.error("[pkmoba] 投球落地生成宝可梦失败，已吞掉以免崩服", error);
                }
                p.level.sendParticles(ParticleTypes.CLOUD, p.to.x, p.to.y + 0.2, p.to.z,
                        14, 0.35, 0.15, 0.35, 0.02);
                p.level.playSound(null, net.minecraft.core.BlockPos.containing(p.to),
                        SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0F, 1.0F);
                continue;
            }
            Vec3 pos = parabola(p.from, p.to, t);
            // 保留一点拖尾光点，让高速飞行时轨迹看得清。
            p.level.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** 抛物线：线性插值 + 向上拱起，落点越远拱得越高。 */
    private static Vec3 parabola(Vec3 from, Vec3 to, double t) {
        Vec3 flat = from.lerp(to, t);
        double peak = 0.85 + from.distanceTo(to) * 0.10;
        double lift = 4.0 * peak * t * (1.0 - t);
        return flat.add(0, lift, 0);
    }

    /** 清掉某玩家的待落地投球（退出/换维度时用）。 */
    public static void clearPlayer(UUID player) {
        PENDING.removeIf(p -> p.player.equals(player));
    }

    /** 清掉所有登记（服务器停止时用）。 */
    public static void reset() {
        PENDING.clear();
        LOCKS.clear();
    }

    /** 物种定位符（日志用，避免调用方再引入依赖）。 */
    public static String describe(ResourceLocation id) {
        return id == null ? "unknown" : id.toString();
    }
}
