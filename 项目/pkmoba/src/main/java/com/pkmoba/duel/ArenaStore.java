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
        public Vec3 redSwitch;
        public Vec3 blueSwitch;
        public double switchRadius = 3.0;
        public final List<Vec3> redSwitchPolygon = new ArrayList<>();
        public final List<Vec3> blueSwitchPolygon = new ArrayList<>();

        public List<Vec3> switchPolygon(boolean red) {
            List<Vec3> points = red ? redSwitchPolygon : blueSwitchPolygon;
            return points;
        }

        public boolean insideSwitchZone(Vec3 point, boolean red) {
            List<Vec3> points = switchPolygon(red);
            if (!finite(point) || points.size() != 4 || points.stream().anyMatch(v -> !finite(v))) return false;
            Arena shape = new Arena("zone");
            shape.boundary.addAll(points);
            return shape.hasBoundary() && Math.abs(point.y - points.stream().mapToDouble(v -> v.y).average().orElse(point.y)) <= 4
                    && shape.insideBoundary(point);
        }

        public Vec3 switchCenter(boolean red) {
            Vec3 configured = red ? redSwitch : blueSwitch;
            return finite(configured) ? configured : (red ? redPlayer : bluePlayer);
        }

        public boolean insideSwitchZone(Vec3 point) {
            return insideSwitchZone(point, true) || insideSwitchZone(point, false);
        }
        /** 边界多边形（水平投影，按顺序首尾相连）。空表示不限制。 */
        public final List<Vec3> boundary = new ArrayList<>();
        public ResourceLocation dimension;
        /** 自动成组时的组名（同一玩家反复改点位就原地更新这一组，不产生垃圾组）。 */
        public String autoName;

        public Arena(String name) {
            this.name = name;
        }

        /** 一间房由边界、红蓝玩家点和边界内的红蓝四点换宠区域组成。 */
        public boolean complete() {
            return finite(redPlayer) && finite(bluePlayer) && hasBoundary()
                    && insideBoundary(redPlayer) && insideBoundary(bluePlayer)
                    && validSwitchZone(true) && validSwitchZone(false);
        }

        public boolean validSwitchZone(boolean red) {
            List<Vec3> points = switchPolygon(red);
            if (points.size() != 4 || points.stream().anyMatch(p -> !finite(p) || !insideBoundary(p))) return false;
            Arena shape = new Arena("switch-check");
            shape.boundary.addAll(points);
            return shape.hasBoundary();
        }

        private static boolean finite(Vec3 point) {
            return point != null && Double.isFinite(point.x) && Double.isFinite(point.y)
                    && Double.isFinite(point.z);
        }

        /** 用户 m04660：「必须强制 4 个点才能生效」。 */
        public boolean hasBoundary() {
            if (boundary.size() < BOUNDARY_MIN_POINTS) return false;
            double area = 0;
            for (int i = 0; i < boundary.size(); i++) {
                Vec3 a = boundary.get(i);
                Vec3 b = boundary.get((i + 1) % boundary.size());
                if (!finite(a) || !finite(b)) return false;
                if (Math.abs(a.x - b.x) < 1.0E-6 && Math.abs(a.z - b.z) < 1.0E-6) return false;
                for (int j = i + 1; j < boundary.size(); j++) {
                    if (j == i + 1 || (i == 0 && j == boundary.size() - 1)) continue;
                    Vec3 c = boundary.get(j);
                    Vec3 d = boundary.get((j + 1) % boundary.size());
                    if (!finite(c) || !finite(d) || intersects(a, b, c, d)) return false;
                }
                area += a.x * b.z - b.x * a.z;
            }
            return Math.abs(area) > 1.0E-6;
        }

        private static double turn(Vec3 a, Vec3 b, Vec3 c) {
            return (b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x);
        }

        private static boolean intersects(Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
            if (Math.max(a.x, b.x) + 1.0E-6 < Math.min(c.x, d.x)
                    || Math.max(c.x, d.x) + 1.0E-6 < Math.min(a.x, b.x)
                    || Math.max(a.z, b.z) + 1.0E-6 < Math.min(c.z, d.z)
                    || Math.max(c.z, d.z) + 1.0E-6 < Math.min(a.z, b.z)) return false;
            return turn(a, b, c) * turn(a, b, d) <= 1.0E-6
                    && turn(c, d, a) * turn(c, d, b) <= 1.0E-6;
        }

        /** 点是否在边界多边形内（没配边界时视为不限制）。只看水平面。 */
        public boolean insideBoundary(Vec3 point) {
            if (!hasBoundary()) return true;
            boolean inside = false;
            for (int i = 0, j = boundary.size() - 1; i < boundary.size(); j = i++) {
                Vec3 a = boundary.get(i);
                Vec3 b = boundary.get(j);
                double dx = b.x - a.x, dz = b.z - a.z;
                double crossProduct = (point.x - a.x) * dz - (point.z - a.z) * dx;
                if (Math.abs(crossProduct) <= 1.0E-6
                        && point.x >= Math.min(a.x, b.x) - 1.0E-6
                        && point.x <= Math.max(a.x, b.x) + 1.0E-6
                        && point.z >= Math.min(a.z, b.z) - 1.0E-6
                        && point.z <= Math.max(a.z, b.z) + 1.0E-6) return true;
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
            // Legacy Pokémon spawn tags are accepted, but no longer required.
            arena.redPokemon = arena.redPlayer;
            arena.bluePokemon = arena.bluePlayer;
            arena.redSwitch = readVec(entry, "rs");
            arena.blueSwitch = readVec(entry, "bs");
            readPolygon(entry, "rsp", arena.redSwitchPolygon);
            readPolygon(entry, "bsp", arena.blueSwitchPolygon);
            double radius = entry.getDouble("sr");
            arena.switchRadius = Double.isFinite(radius) && radius >= 1 && radius <= 16 ? radius : 3.0;
            // Missing legacy zones remain unconfigured: match readiness is independent, but battle switching stays gated.
            ListTag boundary = entry.getList("bd", Tag.TAG_COMPOUND);
            for (int k = 0; k < boundary.size(); k++) {
                Vec3 v = readVec(boundary.getCompound(k), "p");
                if (v != null) arena.boundary.add(v);
            }
            ResourceLocation dim = ResourceLocation.tryParse(entry.getString("dim"));
            arena.dimension = dim;
            // 自动成组允许先只保存四点边界，不能重启加载时再以玩家点未齐为由丢弃。
            if (arena.name.isBlank()) continue; // Preserve incomplete saved work; only rooms require readiness.
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
            writePolygon(entry, "rsp", arena.redSwitchPolygon);
            writePolygon(entry, "bsp", arena.blueSwitchPolygon);
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


    private static void migrateSwitch(Arena arena, boolean red) {
        Vec3 center = arena.switchCenter(red);
        if (center == null) return;
        double r = arena.switchRadius;
        (red ? arena.redSwitchPolygon : arena.blueSwitchPolygon).addAll(List.of(
                center.add(-r,0,-r), center.add(r,0,-r), center.add(r,0,r), center.add(-r,0,r)));
    }

    private static void readPolygon(CompoundTag tag, String key, List<Vec3> points) {
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(4, list.size()); i++) {
            Vec3 point = readVec(list.getCompound(i), "p");
            if (point != null) points.add(point);
        }
    }

    private static void writePolygon(CompoundTag tag, String key, List<Vec3> points) {
        ListTag list = new ListTag();
        for (Vec3 point : points) {
            CompoundTag item = new CompoundTag();
            writeVec(item, "p", point);
            list.add(item);
        }
        tag.put(key, list);
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

    public static void clearDrafts() {
        DRAFTS.clear();
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
        // m05263：边界画满 4 点就立刻成组（玩家点位随后补进同一组，不再要求"四点齐"才建组）。
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
        target.dimension = draft.dimension;
        target.redSwitch = draft.redSwitch;
        target.blueSwitch = draft.blueSwitch;
        target.switchRadius = draft.switchRadius;
        target.redSwitchPolygon.clear();
        target.redSwitchPolygon.addAll(draft.redSwitchPolygon);
        target.blueSwitchPolygon.clear();
        target.blueSwitchPolygon.addAll(draft.blueSwitchPolygon);
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
        if (removed) {
            // Drop the deleted group's editing draft too; stale autoName must not resurrect it.
            DRAFTS.entrySet().removeIf(entry -> entry.getValue().autoName != null
                    && entry.getValue().autoName.equalsIgnoreCase(name.trim()));
            setDirty();
        }
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
