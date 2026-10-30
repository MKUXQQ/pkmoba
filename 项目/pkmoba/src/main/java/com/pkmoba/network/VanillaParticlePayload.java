package com.pkmoba.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * S2C：批量生成**原版**粒子。
 *
 * <p>为什么换成原版粒子：
 * <ul>
 *   <li>Cobblemon 的招式粒子依赖它自己的贴图集合，我们直接生成会**贴图错误**；</li>
 *   <li>它的方向写死在粒子文件里，不可控。</li>
 * </ul>
 *
 * <p>为什么自己打包而不是调 {@code sendParticles}：一次要几十上百个点，
 * {@code sendParticles} 每个点一个包，开销太大。这里一帧只发一个包。
 *
 * <p>kind 取值：flame / small_flame / lava / smoke / end_rod
 */
public record VanillaParticlePayload(String kind, double[] coords) implements CustomPacketPayload {
    public static final Type<VanillaParticlePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "vanilla_particle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VanillaParticlePayload> CODEC =
            StreamCodec.of((buf, p) -> {
                        buf.writeUtf(p.kind());
                        int count = p.coords().length / 3;
                        buf.writeVarInt(count);
                        for (int i = 0; i < count * 3; i++) {
                            buf.writeDouble(p.coords()[i]);
                        }
                    },
                    buf -> {
                        String kind = buf.readUtf();
                        int count = buf.readVarInt();
                        double[] data = new double[count * 3];
                        for (int i = 0; i < data.length; i++) {
                            data[i] = buf.readDouble();
                        }
                        return new VanillaParticlePayload(kind, data);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
