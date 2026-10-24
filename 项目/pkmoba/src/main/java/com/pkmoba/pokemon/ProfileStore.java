package com.pkmoba.pokemon;

import com.pkmoba.PkmobaMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 玩家宝可梦档案的存档数据（世界级 SavedData，随存档一起保存）。 */
public final class ProfileStore extends SavedData {
    private static final String NAME = "pkmoba_profiles";

    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();

    public static ProfileStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ProfileStore::new, ProfileStore::load, null), NAME);
    }

    public static ProfileStore load(CompoundTag tag, HolderLookup.Provider registries) {
        ProfileStore store = new ProfileStore();
        ListTag list = tag.getList("profiles", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                UUID id = UUID.fromString(entry.getString("uuid"));
                PlayerProfile profile = new PlayerProfile(id);
                ListTag team = entry.getList("team", Tag.TAG_STRING);
                for (int j = 0; j < team.size(); j++) profile.team.add(team.getString(j));
                ListTag fainted = entry.getList("fainted", Tag.TAG_STRING);
                for (int j = 0; j < fainted.size(); j++) profile.fainted.add(fainted.getString(j));
                profile.activeIndex = entry.getInt("active");
                store.profiles.put(id, profile);
            } catch (IllegalArgumentException ex) {
                PkmobaMod.LOGGER.warn("[pkmoba] 读取玩家档案失败，已跳过：{}", ex.getMessage());
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (PlayerProfile profile : profiles.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("uuid", profile.playerId.toString());
            ListTag team = new ListTag();
            for (String id : profile.team) team.add(StringTag.valueOf(id));
            entry.put("team", team);
            ListTag fainted = new ListTag();
            for (String id : profile.fainted) fainted.add(StringTag.valueOf(id));
            entry.put("fainted", fainted);
            entry.putInt("active", profile.activeIndex);
            list.add(entry);
        }
        tag.put("profiles", list);
        return tag;
    }

    public PlayerProfile profile(UUID id) {
        return profiles.computeIfAbsent(id, PlayerProfile::new);
    }

    @Nullable
    public PlayerProfile peek(UUID id) {
        return profiles.get(id);
    }

    public void markDirtyAndSave() {
        setDirty();
    }
}
