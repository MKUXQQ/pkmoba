package com.pkmoba.network;

import com.pkmoba.PkmobaMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;

/** C2S：玩家手动按键换下一只宝可梦上场。 */
public record SwitchPokemonPayload() implements CustomPacketPayload {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "switch_pokemon");
    public static final CustomPacketPayload.Type<SwitchPokemonPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, SwitchPokemonPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
            },
            buf -> new SwitchPokemonPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
