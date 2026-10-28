package com.pkmoba.duel;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/** Persistent room rules only; active occupants and matches remain transient. */
public final class RoomSettingsStore extends SavedData {
    private static final String NAME = "pkmoba_room_settings";
    private final Map<String, Settings> byArena = new HashMap<>();

    public static final class Settings {
        public String displayName = "";
        public int capacity = 3;
        /** 0 public, 1 reveal entire roster at battle start, 2 reveal on deployment. */
        public int visibility;
        public String passwordSalt = "";
        public String passwordHash = "";

        public boolean passwordProtected() { return !passwordHash.isEmpty(); }
    }

    public static RoomSettingsStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RoomSettingsStore::new, RoomSettingsStore::load, null), NAME);
    }

    private static String key(String arena) { return arena.toLowerCase(java.util.Locale.ROOT); }

    public Settings settings(String arena) {
        return byArena.computeIfAbsent(key(arena), ignored -> new Settings());
    }

    public static RoomSettingsStore load(CompoundTag tag, HolderLookup.Provider registries) {
        RoomSettingsStore store = new RoomSettingsStore();
        ListTag list = tag.getList("rooms", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            String arena = row.getString("arena");
            if (arena.isBlank()) continue;
            Settings settings = new Settings();
            settings.displayName = row.getString("name");
            int capacity = row.getInt("capacity");
            settings.capacity = capacity >= 1 && capacity <= com.pkmoba.pokemon.SpeciesRegistry.ids().size() ? capacity : 3;
            int visibility = row.getInt("visibility");
            settings.visibility = visibility >= 0 && visibility <= 2 ? visibility : 0;
            settings.passwordSalt = row.getString("salt");
            settings.passwordHash = row.getString("hash");
            store.byArena.put(key(arena), settings);
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Settings> entry : byArena.entrySet()) {
            Settings settings = entry.getValue();
            CompoundTag row = new CompoundTag();
            row.putString("arena", entry.getKey());
            row.putString("name", settings.displayName);
            row.putInt("capacity", settings.capacity);
            row.putInt("visibility", settings.visibility);
            row.putString("salt", settings.passwordSalt);
            row.putString("hash", settings.passwordHash);
            list.add(row);
        }
        tag.put("rooms", list);
        return tag;
    }

    public void markDirtyAndSave() { setDirty(); }
}
