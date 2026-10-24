package com.pkmoba.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.util.Mth;

/**
 * 火焰团粒子：Cobblemon 的 10 帧火焰贴图（白色）+ 我们自己做的热力染色。
 *
 * <p>尺寸和寿命由注册时的档位决定（见 {@code PkmobaParticles}）：
 * <ul>
 *   <li>small —— W 龙卷：极小、短命，靠"位置"表现旋转；</li>
 *   <li>normal —— Q 火柱；</li>
 *   <li>glyph —— R 大字：短命（避免拖尾糊字）；</li>
 *   <li>big —— 爆炸余焰。</li>
 * </ul>
 */
public class FlamePuffParticle extends TextureSheetParticle {
    /** 染色从"金黄"起步 —— 不要白色，否则出生瞬间像白色小方块。 */
    private static final float[][] HEAT = {
            {1.00F, 0.82F, 0.28F},
            {1.00F, 0.72F, 0.18F},
            {1.00F, 0.55F, 0.12F},
            {0.90F, 0.32F, 0.07F},
            {0.55F, 0.16F, 0.04F},
            {0.30F, 0.08F, 0.02F},
    };

    private final SpriteSet sprites;

    protected FlamePuffParticle(ClientLevel level, double x, double y, double z,
                                double vx, double vy, double vz, SpriteSet sprites,
                                float size, int lifeTicks) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.lifetime = Math.max(3, lifeTicks + this.random.nextInt(2));
        this.quadSize = size * (0.9F + this.random.nextFloat() * 0.2F);
        this.gravity = 0.0F;
        this.friction = 0.90F;
        this.hasPhysics = false;
        this.roll = this.random.nextInt(4) * 1.5708F;
        // 从第 3 帧开始：前两帧几乎是空的，看起来像白点
        this.setSprite(this.sprites.get(3, 10));
    }

    @Override
    public void tick() {
        super.tick();

        // 帧推进比寿命慢：每 2 tick 一帧，短命粒子就停在中间帧
        int frame = Math.min(9, 3 + this.age / 2);
        this.setSprite(this.sprites.get(frame, 10));

        float t = (float) this.age / (float) this.lifetime;
        float seg = t * (HEAT.length - 1);
        int i = Math.min(HEAT.length - 2, (int) seg);
        float f = seg - i;
        this.setColor(
                Mth.lerp(f, HEAT[i][0], HEAT[i + 1][0]),
                Mth.lerp(f, HEAT[i][1], HEAT[i + 1][1]),
                Mth.lerp(f, HEAT[i][2], HEAT[i + 1][2]));
        this.alpha = t < 0.5F ? 1.0F : 1.0F - (t - 0.5F) / 0.5F;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }
}
