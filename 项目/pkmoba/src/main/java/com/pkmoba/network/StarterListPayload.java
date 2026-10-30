package com.pkmoba.network;

import com.pkmoba.PkmobaMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;

/**
 * S2C：打开宝可梦选择界面。
 *
 * @param mode     {@code PICK} 初始选择 / {@code ADD} 添加宝可梦
 * @param chosen   当前上场物种 id（未选为空串）
 * @param species  可选物种列表
 * @param team     当前队伍
 * @param fainted  已阵亡成员
 */
public record StarterListPayload(String mode, String chosen, List<String> species,
                                 List<String> team, List<String> fainted) implements CustomPacketPayload {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "starter_list");
    public static final CustomPacketPayload.Type<StarterListPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    private static final int MAX_SPECIES = 64;
    private static final int MAX_STRING = 64;

    public boolean adding() {
        return "ADD".equals(mode);
    }

    private static void writeList(FriendlyByteBuf buf, List<String> list) {
        int size = Math.min(list == null ? 0 : list.size(), MAX_SPECIES);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) buf.writeUtf(list.get(i), MAX_STRING);
    }

    private static List<String> readList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> list = new ArrayList<>(Math.max(0, Math.min(size, MAX_SPECIES)));
        for (int i = 0; i < size && i < MAX_SPECIES; i++) list.add(buf.readUtf(MAX_STRING));
        return list;
    }

    public static final StreamCodec<FriendlyByteBuf, StarterListPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.mode == null ? "PICK" : payload.mode, MAX_STRING);
                buf.writeUtf(payload.chosen == null ? "" : payload.chosen, MAX_STRING);
                writeList(buf, payload.species);
                writeList(buf, payload.team);
                writeList(buf, payload.fainted);
            },
            buf -> {
                String mode = buf.readUtf(MAX_STRING);
                String chosen = buf.readUtf(MAX_STRING);
                List<String> species = readList(buf);
                List<String> team = readList(buf);
                List<String> fainted = readList(buf);
                return new StarterListPayload(mode, chosen, species, team, fainted);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
