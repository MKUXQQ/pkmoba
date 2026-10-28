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
    /** Match-only profiles; never serialized, and never mutate owned collection lists. */
    private final Map<UUID, PlayerProfile> battleProfiles = new HashMap<>();

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
                ListTag favorites = entry.getList("favorites", Tag.TAG_STRING);
                for (int j = 0; j < favorites.size(); j++) profile.favorites.add(favorites.getString(j));
                ListTag fainted = entry.getList("fainted", Tag.TAG_STRING);
                for (int j = 0; j < fainted.size(); j++) profile.fainted.add(fainted.getString(j));
                CompoundTag health = entry.getCompound("health");
                for (String species : health.getAllKeys()) {
                    float hp = health.getFloat(species);
                    if (profile.team.contains(species) && Float.isFinite(hp) && hp >= 0) profile.health.put(species, hp);
                }
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
            ListTag favorites = new ListTag();
            for (String id : profile.favorites) favorites.add(StringTag.valueOf(id));
            entry.put("favorites", favorites);
            ListTag fainted = new ListTag();
            for (String id : profile.fainted) fainted.add(StringTag.valueOf(id));
            entry.put("fainted", fainted);
            entry.putInt("active", profile.activeIndex);
            CompoundTag health = new CompoundTag();
            profile.health.forEach((species, hp) -> health.putFloat(species, hp));
            entry.put("health", health);
            list.add(entry);
        }
        tag.put("profiles", list);
        return tag;
    }

    public PlayerProfile profile(UUID id) {
        PlayerProfile battle = battleProfiles.get(id);
        return battle != null ? battle : collection(id);
    }

    public PlayerProfile collection(UUID id) {
        PlayerProfile profile = profiles.computeIfAbsent(id, PlayerProfile::new);
        boolean changed = false;
        for (String species : SpeciesRegistry.ids()) {
            if (!profile.team.contains(species)) {
                profile.team.add(species);
                changed = true;
            }
        }
        if (changed) setDirty();
        return profile;
    }

    public boolean beginBattle(UUID id, java.util.List<String> roster) {
        if (battleProfiles.containsKey(id) || roster.isEmpty()) return false;
        PlayerProfile battle = new PlayerProfile(id);
        battle.team.addAll(roster);
        battleProfiles.put(id, battle);
        return true;
    }

    public void endBattle(UUID id) { battleProfiles.remove(id); }
    public void clearBattles() { battleProfiles.clear(); }

    @Nullable
    public PlayerProfile peek(UUID id) {
        return profiles.get(id);
    }

    public void markDirtyAndSave() {
        setDirty();
    }
}
