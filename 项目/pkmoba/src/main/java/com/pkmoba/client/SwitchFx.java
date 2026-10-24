package com.pkmoba.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 换宝可梦的轻量客户端表现：收回/登场的粒子、头像淡入，以及"下一只"预览的高亮脉冲。
 *
 * <p>这里<b>只做表现</b>：不锁输入、不阻塞主线程、不参与任何胜负或存在性判定。
 * 服务端的实体清理与队伍状态仍由 {@link com.pkmoba.pokemon.TeamService} 负责，
 * 所以动画慢了/丢了也不会导致状态错乱。
 */
public final class SwitchFx {
    /** 按 C 之后预览模块高亮的时长（毫秒）。 */
    private static final long PRESS_HIGHLIGHT_MS = 320L;
    /** 新宝可梦登场后头像淡入的时长（毫秒）。 */
    private static final long ENTRANCE_MS = 260L;

    private static long pressAt = 0L;
    private static long entranceAt = 0L;
    private static int lastEntityId = -1;
    private static Vec3 lastEntityPos = null;

    private SwitchFx() {
    }

    /** 玩家按下 C（或服务端要求换人）时调用：触发预告高亮与头像刷新。 */
    public static void onPress() {
        pressAt = System.currentTimeMillis();
        entranceAt = pressAt;
    }

    /** 队伍状态变化（换人成功、删除、阵亡等）时调用。 */
    public static void onTeamStateChanged() {
        if (pressAt == 0L) pressAt = System.currentTimeMillis();
    }

    /** 预览模块的高亮强度 0..1，用于描边/缩放脉冲。 */
    public static float previewPulse() {
        if (pressAt == 0L) return 0.0F;
        long elapsed = System.currentTimeMillis() - pressAt;
        if (elapsed >= PRESS_HIGHLIGHT_MS) return 0.0F;
        // 先冲上去再回落，避免生硬的开关感
        float t = elapsed / (float) PRESS_HIGHLIGHT_MS;
        return (float) Math.sin(Math.PI * Math.min(1.0F, t * 1.15F));
    }

    /** 新上场宝可梦头像的淡入系数 0.35..1。 */
    public static float entranceAlpha() {
        if (entranceAt == 0L) return 1.0F;
        long elapsed = System.currentTimeMillis() - entranceAt;
        if (elapsed >= ENTRANCE_MS) return 1.0F;
        float t = elapsed / (float) ENTRANCE_MS;
        return 0.35F + 0.65F * t;
    }

    /** 新上场头像的缩放系数 0.82..1。 */
    public static float entranceScale() {
        if (entranceAt == 0L) return 1.0F;
        long elapsed = System.currentTimeMillis() - entranceAt;
        if (elapsed >= ENTRANCE_MS) return 1.0F;
        float t = elapsed / (float) ENTRANCE_MS;
        return 1.0F - 0.18F * (1.0F - t);
    }

    /**
     * 每客户端 tick 调用：观察受控实体 id 的变化，在"收回"与"登场"两侧各撒一圈轻量粒子。
     *
     * <p>用 id 变化当信号即可，不需要服务端额外发包。
     */
    public static void tick(Minecraft mc) {
        if (mc.level == null) {
            lastEntityId = -1;
            lastEntityPos = null;
            return;
        }
        int id = ClientState.getCharizardId();
        if (id >= 0) {
            Entity entity = mc.level.getEntity(id);
            if (entity != null) {
                if (id != lastEntityId) {
                    summonBurst(mc, entity);
                    entranceAt = System.currentTimeMillis();
                }
                lastEntityPos = entity.position().add(0, entity.getBbHeight() * 0.5, 0);
            }
        } else if (lastEntityId >= 0 && lastEntityPos != null) {
            recallBurst(mc, lastEntityPos);
            lastEntityPos = null;
        }
        lastEntityId = id;
    }

    private static void summonBurst(Minecraft mc, Entity entity) {
        Vec3 base = entity.position().add(0, entity.getBbHeight() * 0.5, 0);
        boolean water = isWater(entity);
        for (int i = 0; i < 14; i++) {
            double angle = i * (Math.PI * 2 / 14);
            double radius = 0.55;
            double x = base.x + Math.cos(angle) * radius;
            double z = base.z + Math.sin(angle) * radius;
            double y = base.y - entity.getBbHeight() * 0.3 + (i % 3) * 0.15;
            mc.level.addParticle(water ? ParticleTypes.SPLASH : ParticleTypes.FLAME, x, y, z,
                    -Math.cos(angle) * 0.08, 0.06, -Math.sin(angle) * 0.08);
        }
        mc.level.addParticle(ParticleTypes.CLOUD, base.x, base.y, base.z, 0, 0.04, 0);
    }

    private static void recallBurst(Minecraft mc, Vec3 base) {
        for (int i = 0; i < 12; i++) {
            double angle = i * (Math.PI * 2 / 12);
            double radius = 0.7;
            double x = base.x + Math.cos(angle) * radius;
            double z = base.z + Math.sin(angle) * radius;
            mc.level.addParticle(ParticleTypes.CLOUD, x, base.y, z,
                    -Math.cos(angle) * 0.12, 0.05, -Math.sin(angle) * 0.12);
        }
    }

    private static boolean isWater(Entity entity) {
        if (!(entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon)) {
            return false;
        }
        String path = pokemon.getPokemon().getSpecies().getResourceIdentifier().getPath();
        return "blastoise".equals(path);
    }

    /** 断线/退出对局时清掉临时状态，避免残留高亮。 */
    public static void reset() {
        pressAt = 0L;
        entranceAt = 0L;
        lastEntityId = -1;
        lastEntityPos = null;
    }
}
