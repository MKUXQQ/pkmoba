package com.pkmoba.network;

import com.pkmoba.PkmobaMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：玩家在宝可梦选择界面里选定了某只宝可梦。
 *
 * @param mode    {@code PICK} 选择上场 / {@code ADD} 添加进队伍 /
 *                {@code CONFIRM} 服务端原子确认：当前已出战成员收回，否则选择并召唤（不释放技能）
 * @param species 物种 id
 */
public record StarterChoosePayload(String mode, String species) implements CustomPacketPayload {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "starter_choose");
    public static final CustomPacketPayload.Type<StarterChoosePayload> TYPE = new CustomPacketPayload.Type<>(ID);

    private static final int MAX = 64;

    public boolean adding() {
        return "ADD".equals(mode);
    }

    public static final StreamCodec<FriendlyByteBuf, StarterChoosePayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.mode == null ? "PICK" : p.mode, MAX);
                buf.writeUtf(p.species == null ? "" : p.species, MAX);
            },
            buf -> new StarterChoosePayload(buf.readUtf(MAX), buf.readUtf(MAX)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
