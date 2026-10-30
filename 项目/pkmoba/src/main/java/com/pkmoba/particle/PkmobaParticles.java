package com.pkmoba.particle;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 自定义粒子类型（四档，按用途区分尺寸和寿命）。
 *
 * <p>粒子尺寸/寿命是渲染时定的、网络包带不过去，所以按用途注册不同档位，
 * 由 {@code FlamePayload.tier} 选择。
 */
public final class PkmobaParticles {
    /** W 龙卷：极小 + 短命，靠位置表现旋转。 */
    public static final SimpleParticleType FLAME_PUFF_SMALL = new CustomSimpleParticleType(false);
    /** Q 火柱：中等。 */
    public static final SimpleParticleType FLAME_PUFF = new CustomSimpleParticleType(false);
    /** R 大字：小一点 + 很短命，避免拖尾糊字。 */
    public static final SimpleParticleType FLAME_PUFF_GLYPH = new CustomSimpleParticleType(false);
    /** 爆炸余焰：大一点。 */
    public static final SimpleParticleType FLAME_PUFF_BIG = new CustomSimpleParticleType(false);

    private PkmobaParticles() {
    }

    public static void register() {
        reg("flame_puff_small", FLAME_PUFF_SMALL);
        reg("flame_puff", FLAME_PUFF);
        reg("flame_puff_glyph", FLAME_PUFF_GLYPH);
        reg("flame_puff_big", FLAME_PUFF_BIG);
    }

    private static void reg(String path, SimpleParticleType type) {
        Registry.register(BuiltInRegistries.PARTICLE_TYPE,
                ResourceLocation.fromNamespaceAndPath("pkmoba", path), type);
    }

    private static final class CustomSimpleParticleType extends SimpleParticleType {
        CustomSimpleParticleType(boolean overrideLimiter) {
            super(overrideLimiter);
        }
    }
}
