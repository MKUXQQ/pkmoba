package com.pkmoba.network;

import com.pkmoba.PkmobaMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;

/**
 * S2C：玩家自己的队伍状态，用于 HUD 左侧头像 + 圆点 + 换人倒计时。
 *
 * @param team         队伍物种 id 列表（按出场顺序）
 * @param fainted      已阵亡物种 id 列表
 * @param active       当前上场物种 id（未选为空串）
 * @param summoned     当前是否已有实体在场
 * @param pendingTicks 换人倒计时剩余 tick，0 表示不需要换人
 * @param next         按 C 键接下来会放出/切换到的物种 id（空串表示没有可切换的）
 */
public record TeamStatePayload(List<String> team, List<String> fainted, String active,
                               boolean summoned, int pendingTicks,
                               String next) implements CustomPacketPayload {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(PkmobaMod.MOD_ID, "team_state");
    public static final CustomPacketPayload.Type<TeamStatePayload> TYPE = new CustomPacketPayload.Type<>(ID);
    private static final int MAX_LIST = 40;
    private static final int MAX_STRING = 64;

    public static final StreamCodec<FriendlyByteBuf, TeamStatePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                int teamSize = Math.min(payload.team.size(), MAX_LIST);
                buf.writeVarInt(teamSize);
                for (int i = 0; i < teamSize; i++) buf.writeUtf(payload.team.get(i), MAX_STRING);
                int faintSize = Math.min(payload.fainted.size(), MAX_LIST);
                buf.writeVarInt(faintSize);
                for (int i = 0; i < faintSize; i++) buf.writeUtf(payload.fainted.get(i), MAX_STRING);
                buf.writeUtf(payload.active == null ? "" : payload.active, MAX_STRING);
                buf.writeBoolean(payload.summoned);
                buf.writeVarInt(Math.max(0, payload.pendingTicks));
                buf.writeUtf(payload.next == null ? "" : payload.next, MAX_STRING);
            },
            buf -> {
                int teamSize = buf.readVarInt();
                List<String> team = new ArrayList<>(Math.max(0, Math.min(teamSize, MAX_LIST)));
                for (int i = 0; i < teamSize && i < MAX_LIST; i++) team.add(buf.readUtf(MAX_STRING));
                int faintSize = buf.readVarInt();
                List<String> fainted = new ArrayList<>(Math.max(0, Math.min(faintSize, MAX_LIST)));
                for (int i = 0; i < faintSize && i < MAX_LIST; i++) fainted.add(buf.readUtf(MAX_STRING));
                String active = buf.readUtf(MAX_STRING);
                boolean summoned = buf.readBoolean();
                int pending = buf.readVarInt();
                String next = buf.readUtf(MAX_STRING);
                return new TeamStatePayload(team, fainted, active, summoned, pending, next);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
