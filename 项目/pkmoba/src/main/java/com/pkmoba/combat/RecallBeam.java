package com.pkmoba.combat;

import com.cobblemon.mod.common.CobblemonSounds;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 复刻原版宝可梦的「红光召回 / 红光放出」效果。
 *
 * <p>原版召回是「红色光柱 + 一圈红环 + poke_ball_recall 音效」，先收缩再消失；
 * 放出则是反过来：红光从球里散开、宝可梦由小变大。这里不做缩放（会和其他动画打架），
 * 只还原光柱、红环、音效与「先演完再移除实体」的时序，保证死亡/召唤的手感与原版一致。
 */
public final class RecallBeam {
    /** 红光总时长（tick）。20 tick = 1 秒，和原版召回时长接近。 */
    private static final int TOTAL_TICKS = 14;
    private static final DustParticleOptions RED =
            new DustParticleOptions(new Vector3f(1.0F, 0.22F, 0.18F), 1.7F);
    private static final DustParticleOptions RED_SOFT =
            new DustParticleOptions(new Vector3f(0.95F, 0.42F, 0.35F), 1.1F);

    private static final List<Beam> ACTIVE = new ArrayList<>();

    private RecallBeam() {
    }

    /** 播放召回红光，播完把实体移除。 */
    public static void recall(ServerLevel level, PokemonEntity entity) {
        if (entity == null) return;
        play(level, entity, true);
    }

    /** 播放放出红光（实体已经生成，只是补一段从地面升起的光效）。 */
    public static void sendOut(ServerLevel level, PokemonEntity entity) {
        if (entity == null) return;
        play(level, entity, false);
    }

    private static void play(ServerLevel level, PokemonEntity entity, boolean recall) {
        if (level == null || entity == null) return;
        level.playSound(null, entity.blockPosition(),
                recall ? CobblemonSounds.POKE_BALL_RECALL : CobblemonSounds.POKE_BALL_SEND_OUT,
                SoundSource.NEUTRAL, 0.9F, 1.0F);
        ACTIVE.add(new Beam(level, entity, recall));
        broadcast(level, entity, recall);
    }

    /**
     * 把这一段红光广播给最近的玩家，由客户端自绘「宝可梦 → 精灵球光点 → 玩家身体」的光束。
     *
     * <p>用户 m05076 要求召回是「一束红光、收回到球里、再融入玩家身体」，光柱式粒子做不到，
     * 所以改成客户端光束；服务端只负责发一条带实体 id 的指令。
     */
    private static void broadcast(ServerLevel level, PokemonEntity entity, boolean recall) {
        net.minecraft.server.level.ServerPlayer nearest = null;
        double best = 16.0D * 16.0D;
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            double distance = player.distanceToSqr(entity);
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        if (nearest == null) {
            return;
        }
        com.pkmoba.network.RecallFxPayload payload = new com.pkmoba.network.RecallFxPayload(
                entity.getId(), nearest.getId(), TOTAL_TICKS, recall);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(nearest, payload);
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(RecallBeam::tick);
    }

    private static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        Iterator<Beam> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Beam beam = it.next();
            beam.tick();
            if (beam.done) {
                it.remove();
            }
        }
    }

    private static final class Beam {
        final ServerLevel level;
        final PokemonEntity entity;
        final boolean recall;
        int age;
        boolean done;

        Beam(ServerLevel level, PokemonEntity entity, boolean recall) {
            this.level = level;
            this.entity = entity;
            this.recall = recall;
        }

        void tick() {
            boolean gone = entity == null || entity.isRemoved() || (!recall && !entity.isAlive());
            if (gone || age >= TOTAL_TICKS) {
                if (recall && entity != null && !entity.isRemoved()) {
                    entity.discard();
                }
                done = true;
                return;
            }
            double t = age / (double) TOTAL_TICKS;
            double progress = recall ? t : 1.0 - t;
            Vec3 base = entity.position();
            double height = entity.getBbHeight();
            double centerY = base.y + height * 0.45;

            // 竖直光柱：从脚下升到头顶，召回时向上收，放出时从上往下铺
            int column = 16;
            for (int i = 0; i < column; i++) {
                double f = i / (double) (column - 1);
                double y = base.y + height * (recall ? f * (0.15 + 0.85 * (1.0 - progress))
                        : (1.0 - f * (0.15 + 0.85 * progress)));
                level.sendParticles(i % 3 == 0 ? RED : RED_SOFT,
                        base.x, y, base.z, 1, 0.04, 0.0, 0.04, 0.0);
            }
            // 红环：绕实体旋转的光点，召回时半径收缩，放出时半径张开
            double radius = (recall ? 1.05 * (1.0 - 0.85 * progress) : 1.05 * (0.15 + 0.85 * progress))
                    * Math.max(0.5, height * 0.5);
            int ring = 18;
            for (int i = 0; i < ring; i++) {
                double angle = Math.PI * 2 * i / ring + age * 0.45;
                double x = base.x + Math.cos(angle) * radius;
                double z = base.z + Math.sin(angle) * radius;
                level.sendParticles(RED, x, centerY, z, 1, 0.01, 0.01, 0.01, 0.0);
            }
            // 脚下地面红圈（让"被回收"的位置有明确反馈）
            double groundRadius = 0.9 * (1.0 - 0.6 * progress) * Math.max(0.6, height * 0.45);
            int ground = 14;
            for (int i = 0; i < ground; i++) {
                double angle = Math.PI * 2 * i / ground;
                level.sendParticles(RED_SOFT,
                        base.x + Math.cos(angle) * groundRadius, base.y + 0.06,
                        base.z + Math.sin(angle) * groundRadius, 1, 0.0, 0.0, 0.0, 0.0);
            }
            age++;
        }
    }
}
