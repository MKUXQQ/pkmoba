package com.pkmoba.duel;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.charizard.CharizardManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * m05418：边界硬限制。
 *
 * <p>以前只在移动指令（寻路/直线走）里夹边界，冲刺、被击退、召唤、坐骑漂移都绕过去了，
 * 所以玩家看到的是"边界根本没用"。这里改成<b>每个服务端 tick 扫一遍所有被操控的宝可梦</b>：
 * 只要它在某个已配置边界之外，就把它拉回边界内并清掉水平速度 —— 不管它是怎么跑出去的。</p>
 */
public final class ArenaBounds {

    /** 每只宝可梦上一次确认所在的点位组名：跑出边界后靠它继续管。 */
    private static final java.util.Map<java.util.UUID, String> LAST_ARENA = new java.util.HashMap<>();

    private ArenaBounds() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(ArenaBounds::tick);
    }

    private static void tick(MinecraftServer server) {
        for (PokemonEntity entity : CharizardManager.getControlledEntities(server)) {
            if (entity == null || entity.isRemoved() || !(entity.level() instanceof ServerLevel level)) {
                continue;
            }
            ArenaStore.Arena arena = resolve(level, entity);
            if (arena == null) {
                arena = remembered(level, entity);
            }
            if (arena == null || !arena.hasBoundary()) {
                continue;
            }
            Vec3 pos = entity.position();
            if (arena.insideBoundary(pos)) {
                LAST_ARENA.put(entity.getUUID(), arena.name);
                continue;
            }
            Vec3 target = pullInside(arena, pos);
            entity.teleportTo(target.x, target.y, target.z);
            entity.setDeltaMovement(0.0, entity.getDeltaMovement().y, 0.0);
            entity.hurtMarked = true;
            // 走到边界上就停下手头的地面移动，否则下一 tick 又往外推。
            entity.getNavigation().stop();
        }
    }

    /** 上一次记录的点位组：出界以后没有"在界内"的兜底可用了，靠这条继续把宝可梦拉回去。 */
    private static ArenaStore.Arena remembered(ServerLevel level, PokemonEntity entity) {
        String name = LAST_ARENA.get(entity.getUUID());
        if (name == null) return null;
        ArenaStore.Arena arena = ArenaStore.get(level.getServer()).byName(name);
        if (arena == null) LAST_ARENA.remove(entity.getUUID());
        return arena;
    }

    /** 先按"房间/对战 → 点位组"找，再退回"它正站在哪个已配置的边界里"。 */
    @Nullable
    private static ArenaStore.Arena resolve(ServerLevel level, PokemonEntity entity) {
        ArenaStore.Arena arena = DuelManager.arenaForEntity(entity);
        if (arena != null) {
            return arena;
        }
            java.util.UUID owner = CharizardManager.getControlledOwner(entity);
            if (owner != null) {
                RoomManager.Room room = RoomManager.roomOf(owner);
                if (room != null) {
                    ArenaStore.Arena byRoom = ArenaStore.get(level.getServer()).byName(room.arenaName);
                    if (byRoom != null) {
                        return byRoom;
                    }
                }
            }
            Vec3 pos = entity.position();
            for (ArenaStore.Arena candidate : ArenaStore.get(level.getServer()).allArenas()) {
                if (candidate.hasBoundary() && candidate.insideBoundary(pos)) {
                    return candidate;
                }
            }
        return null;
    }

    /** 沿"当前点 → 多边形质心"的直线，找第一个在边界内的点。 */
    private static Vec3 pullInside(ArenaStore.Arena arena, Vec3 pos) {
        double cx = 0, cz = 0;
        for (Vec3 p : arena.boundary) {
            cx += p.x;
            cz += p.z;
        }
        cx /= arena.boundary.size();
        cz /= arena.boundary.size();
        Vec3 centroid = new Vec3(cx, pos.y, cz);
        Vec3 candidate = pos;
        for (int step = 0; step < 160; step++) {
            candidate = candidate.lerp(centroid, 0.05);
            if (arena.insideBoundary(candidate)) {
                return candidate;
            }
        }
        return centroid;
    }
}
