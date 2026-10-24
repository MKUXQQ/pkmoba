package com.pkmoba.duel;

import com.pkmoba.PkmobaMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 点位式对战场地：只记 4 个坐标（红/蓝 × 玩家点/宝可梦点），不设半径限制。
 *
 * <p>用户口径（m06221）：场地暂时无限大，只需要设置点位。
 */
public final class ArenaStore extends SavedData {
    private static final String NAME = "pkmoba_arenas";

    /** 4 个点位 + 维度。 */
    public static final class Arena {
        public final String name;
        public Vec3 redPlayer;
        public Vec3 bluePlayer;
        public Vec3 redPokemon;
        public Vec3 bluePokemon;
        public ResourceLocation dimension;

        public Arena(String name) {
            this.name = name;
        }

        public boolean complete() {
            return redPlayer != null && bluePlayer != null && redPokemon != null && bluePokemon != null;
        }
    }

    /** 每个玩家正在摆放的草稿（未提交的 4 点）。 */
    private static final Map<UUID, Arena> DRAFTS = new HashMap<>();

    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public static ArenaStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ArenaStore::new, ArenaStore::load, null), NAME);
    }

    public static ArenaStore load(CompoundTag tag, HolderLookup.Provider registries) {
        ArenaStore store = new ArenaStore();
        ListTag list = tag.getList("arenas", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            Arena arena = new Arena(entry.getString("name"));
            arena.redPlayer = readVec(entry, "rp");
            arena.bluePlayer = readVec(entry, "bp");
            arena.redPokemon = readVec(entry, "rk");
            arena.bluePokemon = readVec(entry, "bk");
            ResourceLocation dim = ResourceLocation.tryParse(entry.getString("dim"));
            arena.dimension = dim;
            if (arena.name.isBlank() || !arena.complete()) continue;
            store.arenas.put(arena.name.toLowerCase(java.util.Locale.ROOT), arena);
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Arena arena : arenas.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("name", arena.name);
            writeVec(entry, "rp", arena.redPlayer);
            writeVec(entry, "bp", arena.bluePlayer);
            writeVec(entry, "rk", arena.redPokemon);
            writeVec(entry, "bk", arena.bluePokemon);
            entry.putString("dim", arena.dimension == null ? "" : arena.dimension.toString());
            list.add(entry);
        }
        tag.put("arenas", list);
        return tag;
    }

    private static Vec3 readVec(CompoundTag tag, String key) {
        if (!tag.contains(key)) return null;
        CompoundTag v = tag.getCompound(key);
        return new Vec3(v.getDouble("x"), v.getDouble("y"), v.getDouble("z"));
    }

    private static void writeVec(CompoundTag tag, String key, @Nullable Vec3 vec) {
        if (vec == null) return;
        CompoundTag v = new CompoundTag();
        v.putDouble("x", vec.x);
        v.putDouble("y", vec.y);
        v.putDouble("z", vec.z);
        tag.put(key, v);
    }

    public static Arena draft(UUID playerId) {
        return DRAFTS.computeIfAbsent(playerId, ignored -> new Arena("draft"));
    }

    public static void clearDraft(UUID playerId) {
        DRAFTS.remove(playerId);
    }

    @Nullable
    public Arena byName(String name) {
        if (name == null) return null;
        return arenas.get(name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public List<Arena> allArenas() {
        return new ArrayList<>(arenas.values());
    }

    public List<String> names() {
        return new ArrayList<>(arenas.keySet());
    }

    public void put(Arena arena) {
        arenas.put(arena.name.toLowerCase(java.util.Locale.ROOT), arena);
        setDirty();
    }

    public boolean remove(String name) {
        boolean removed = arenas.remove(name.trim().toLowerCase(java.util.Locale.ROOT)) != null;
        if (removed) setDirty();
        return removed;
    }

    /** 找不到就记一条日志，方便排查"打了名字但对不上"。 */
    @Nullable
    public Arena require(MinecraftServer server, String name) {
        Arena arena = byName(name);
        if (arena == null) {
            PkmobaMod.LOGGER.warn("[pkmoba] 找不到场地 '{}'，现有：{}", name, names());
        }
        return arena;
    }
}
