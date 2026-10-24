package com.pkmoba.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * 火焰团粒子的工厂：尺寸和寿命由注册的档位决定，速度由网络包传进来。
 */
public class FlamePuffProvider implements ParticleProvider<SimpleParticleType> {
    private final SpriteSet sprites;
    private final float size;
    private final int lifeTicks;

    public FlamePuffProvider(SpriteSet sprites, float size, int lifeTicks) {
        this.sprites = sprites;
        this.size = size;
        this.lifeTicks = lifeTicks;
    }

    @Override
    public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                   double x, double y, double z,
                                   double vx, double vy, double vz) {
        return new FlamePuffParticle(level, x, y, z, vx, vy, vz, sprites, size, lifeTicks);
    }
}
