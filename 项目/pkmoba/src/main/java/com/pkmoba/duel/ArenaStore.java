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
 * 竞技场点位组：房间系统使用的「地图模板」。
 *
 * <p>0.40 起从「4 个点」扩展为完整点位组：
 * <ul>
 *   <li>红/蓝双方玩家站位（{@code redPlayer}/{@code bluePlayer}）</li>
 *   <li>红/蓝双方宝可梦出场点（{@code redPokemon}/{@code bluePokemon}）</li>
 *   <li>红/蓝双方换宠区域（{@code redSwitch}/{@code blueSwitch} + {@code switchRadius}）</li>
 *   <li>地图边界（{@code boundary}，封闭多边形；空表示无边界）</li>
 * </ul>
 *
 * <p>点位组是持久保存的地图配置，房间（{@link RoomManager}）是临时对战实例，两者分离。
 */
public final class ArenaStore extends SavedData {
    private static final String NAME = "pkmoba_arenas";

    /** 边界至少围成封闭图形所需的点数（用户 m04660：强制 4 点）。 */
    public static final int BOUNDARY_MIN_POINTS = 4;

    /** 点位组。 */
    public static final class Arena {
        public final String name;
        public Vec3 redPlayer;
        public Vec3 bluePlayer;
        public Vec3 redPokemon;
        public Vec3 bluePokemon;
        /** 换宠区中心（空表示该方没配换宠区）。 */
        public Vec3 redSwitch;
        public Vec3 blueSwitch;
        /** m05326：换宠区改成四个点围成的多边形（每方各一套）。 */
        public final List<Vec3> redSwitchQuad = new ArrayList<>();
        public final List<Vec3> blueSwitchQuad = new ArrayList<>();
        /** m05418：换宠区不分红蓝，全场共用一块四点区域。 */
        public final List<Vec3> switchQuad = new ArrayList<>();
        /** 换宠区半径（格）。 */
        public double switchRadius = 6.0;
        /** 边界多边形（水平投影，按顺序首尾相连）。空表示不限制。 */
        public final List<Vec3> boundary = new ArrayList<>();
        public ResourceLocation dimension;
        /** 自动成组时的组名（同一玩家反复改点位就原地更新这一组，不产生垃圾组）。 */
        public String autoName;

        public Arena(String name) {
            this.name = name;
        }

        /** 四类点位（玩家 ×2 + 宝可梦 ×2）全部配好才允许开房对战。 */
        public boolean complete() {
            return redPlayer != null && bluePlayer != null && redPokemon != null && bluePokemon != null;
        }

        public boolean hasSwitchZone() {
            return switchQuad.size() >= 4 || (redSwitch != null && blueSwitch != null);
        }

        /** 取某一方换宠区的多边形（不足 4 点视为没配）。 */
        public List<Vec3> switchQuad(boolean red) {
            List<Vec3> quad = red ? redSwitchQuad : blueSwitchQuad;
            return quad.size() >= 4 ? quad : java.util.Collections.emptyList();
        }

        /** 用户 m04660：「必须强制 4 个点才能生效」。 */
        public boolean hasBoundary() {
            return boundary.size() >= BOUNDARY_MIN_POINTS;
        }

        /** 点是否在该方的换宠区里（没配换宠区时视为不限制，返回 true）。 */
        public boolean insideSwitchZone(boolean red, Vec3 point) {
            if (switchQuad.size() >= 4) {
                return polygonContains(switchQuad, point);
            }
            List<Vec3> quad = switchQuad(red);
            if (!quad.isEmpty()) {
                return polygonContains(quad, point);
            }
            // 兼容旧存档：只有中心 + 半径时按圆判定。
            Vec3 center = red ? redSwitch : blueSwitch;
            if (center == null) return true;
            return center.distanceToSqr(point) <= switchRadius * switchRadius;
        }

        /** 水平面多边形包含判定。 */
        private static boolean polygonContains(List<Vec3> poly, Vec3 point) {
            boolean inside = false;
            for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
                Vec3 a = poly.get(i);
                Vec3 b = poly.get(j);
                boolean cross = (a.z > point.z) != (b.z > point.z);
                if (cross && point.x < (b.x - a.x) * (point.z - a.z) / (b.z - a.z) + a.x) {
                    inside = !inside;
                }
            }
            return inside;
        }

        /** 点是否在边界多边形内（没配边界时视为不限制）。只看水平面。 */
        public boolean insideBoundary(Vec3 point) {
            if (!hasBoundary()) return true;
            boolean inside = false;
            for (int i = 0, j = boundary.size() - 1; i < boundary.size(); j = i++) {
                Vec3 a = boundary.get(i);
                Vec3 b = boundary.get(j);
                boolean cross = (a.z > point.z) != (b.z > point.z);
                if (cross && point.x < (b.x - a.x) * (point.z - a.z) / (b.z - a.z) + a.x) {
                    inside = !inside;
                }
            }
            return inside;
        }
    }

    /** 每个玩家正在摆放的草稿（未提交的点位组）。 */
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
            arena.redSwitch = readVec(entry, "rs");
            arena.blueSwitch = readVec(entry, "bs");
            if (entry.contains("sr")) arena.switchRadius = entry.getDouble("sr");
            readQuads(entry, "rq", arena.redSwitchQuad);
            readQuads(entry, "bq", arena.blueSwitchQuad);
            readQuads(entry, "sq", arena.switchQuad);
            ListTag boundary = entry.getList("bd", Tag.TAG_COMPOUND);
            for (int k = 0; k < boundary.size(); k++) {
                Vec3 v = readVec(boundary.getCompound(k), "p");
                if (v != null) arena.boundary.add(v);
            }
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
            writeVec(entry, "rs", arena.redSwitch);
            writeVec(entry, "bs", arena.blueSwitch);
            entry.putDouble("sr", arena.switchRadius);
            writeQuads(entry, "rq", arena.redSwitchQuad);
            writeQuads(entry, "bq", arena.blueSwitchQuad);
            writeQuads(entry, "sq", arena.switchQuad);
            ListTag boundary = new ListTag();
            for (Vec3 v : arena.boundary) {
                CompoundTag point = new CompoundTag();
                writeVec(point, "p", v);
                boundary.add(point);
            }
            entry.put("bd", boundary);
            entry.putString("dim", arena.dimension == null ? "" : arena.dimension.toString());
            list.add(entry);
        }
        tag.put("arenas", list);
        return tag;
    }


    private static void readQuads(CompoundTag entry, String key, List<Vec3> out) {
        ListTag list = entry.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Vec3 v = readVec(list.getCompound(i), "p");
            if (v != null) out.add(v);
        }
    }

    private static void writeQuads(CompoundTag entry, String key, List<Vec3> quad) {
        ListTag list = new ListTag();
        for (Vec3 v : quad) {
            CompoundTag point = new CompoundTag();
            writeVec(point, "p", v);
            list.add(point);
        }
        entry.put(key, list);
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

    /**
     * 把当前草稿存成"自动点位组"：四个点一凑齐就调用，玩家不需要再敲 /mo。
     * 同一玩家后续补画边界、改点位时，原地更新同一个组（{@link Arena#autoName} 记着名字）。
     *
     * @return 组名；四个点还没齐时返回 null
     */
    public String saveDraftAuto(MinecraftServer server, UUID playerId) {
        Arena draft = DRAFTS.get(playerId);
        if (draft == null) {
            return null;
        }
        // m05263：边界画满 4 点就立刻成组（点位/换宠区随后补进同一组，不再要求"四点齐"才建组）。
        boolean boundaryReady = draft.boundary.size() >= BOUNDARY_MIN_POINTS;
        if (draft.autoName == null) {
            if (!draft.complete() && !boundaryReady) {
                return null;
            }
            draft.autoName = nextGroupName();
        }
        Arena target = arenas.computeIfAbsent(draft.autoName.toLowerCase(java.util.Locale.ROOT),
                ignored -> new Arena(draft.autoName));
        target.redPlayer = draft.redPlayer;
        target.bluePlayer = draft.bluePlayer;
        // m05418：宝可梦放置点已删除，出场点直接复用玩家点。
        target.redPokemon = draft.redPlayer;
        target.bluePokemon = draft.bluePlayer;
        target.redSwitch = draft.redSwitch;
        target.blueSwitch = draft.blueSwitch;
        target.redSwitchQuad.clear();
        target.redSwitchQuad.addAll(draft.redSwitchQuad);
        target.blueSwitchQuad.clear();
        target.blueSwitchQuad.addAll(draft.blueSwitchQuad);
        target.switchQuad.clear();
        target.switchQuad.addAll(draft.switchQuad);
        target.switchRadius = draft.switchRadius;
        target.dimension = draft.dimension;
        target.boundary.clear();
        target.boundary.addAll(draft.boundary);
        setDirty();
        return target.name;
    }

    /** 自动生成下一个英文房间组名（`arena1`、`arena2`…），避免中文名带空格导致命令解析困难。 */
    public String nextGroupName() {
        int n = 1;
        while (arenas.containsKey(("arena" + n).toLowerCase(java.util.Locale.ROOT))) {
            n++;
        }
        return "arena" + n;
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
            PkmobaMod.LOGGER.warn("[pkmoba] 找不到点位组 '{}'，现有：{}", name, names());
        }
        return arena;
    }
}
