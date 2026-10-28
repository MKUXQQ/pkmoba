package com.pkmoba.charizard;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.pkmoba.PkmobaMod;
import com.pkmoba.network.CharizardSyncPayload;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 受控宝可梦的管理者（保留历史类名）。
 *
 * <p>0.38 起一名玩家可以同时拥有<b>多只</b>在场受控宝可梦（{@link #getActiveList}），
 * 但同一时刻只有一只处于"选中"状态（{@link #getControlled}）：
 * <ul>
 *   <li>技能、普攻指令、移动指令、上帝视角跟随都作用在<b>选中</b>的那只上；</li>
 *   <li>场上其它实体各自保留自己的寻路/攻击状态（那些状态本来就按实体 UUID 存），
 *       互不覆盖，也不会因为召唤新的一只而被收回；</li>
 *   <li>C 键收回/切换只作用于选中的那只；多只在场时再次 C 会轮换选中。</li>
 * </ul>
 *
 * <p>关键决策（都踩过坑）：
 * <ul>
 *   <li><b>绝不设置 entity.ownerUUID</b>：Cobblemon 的 PokemonEntity.stopSeenByPlayer() 里有
 *       {@code if (ownerUUID == player.uuid && tethering == null) remove(DISCARDED)}，
 *       一旦认主，玩家不再追踪到它就会被当成"召回出战宝可梦"直接删除。</li>
 *   <li><b>不关 AI</b>：用户要求走原生寻路，而 {@code isNoAi=true} 会让 navigation 停止 tick，
 *       连绕障都不会有。改用"保留 AI + 每 tick 覆盖 navigation + 清空攻击目标"。</li>
 *   <li><b>鲁棒性</b>：{@code setPersistenceRequired()} 挡自然 despawn；保留正常受伤与死亡。</li>
 *   <li><b>旧版单实体训练场保留</b>：{@link #spawn} / {@link #getActive} 仍是"唯一训练实体"，
 *       只为兼容 {@code move/PlacementService} 的选点召唤；新的多实体流程走
 *       {@link #spawnMatchEntity} + 每玩家列表。</li>
 * </ul>
 */
public final class CharizardManager {
    public static final ResourceLocation CHARIZARD_ID =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "charizard");

    public static final ResourceLocation BLASTOISE_ID =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "blastoise");

    public static final ResourceLocation VENUSAUR_ID =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "venusaur");

    public static final ResourceLocation PIKACHU_ID =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "pikachu");

    public static boolean isPikachu(PokemonEntity entity) {
        return entity != null && entity.getPokemon() != null
                && PIKACHU_ID.equals(entity.getPokemon().getSpecies().getResourceIdentifier());
    }

    public static boolean isVenusaur(PokemonEntity entity) {
        return entity != null && VENUSAUR_ID.equals(entity.getPokemon().getSpecies().getResourceIdentifier());
    }

    public static boolean isBlastoise(PokemonEntity entity) {
        return entity != null && BLASTOISE_ID.equals(entity.getPokemon().getSpecies().getResourceIdentifier());
    }

    @Nullable
    private static UUID activeEntityId;
    @Nullable
    private static ResourceLocation activeDimension;
    @Nullable
    private static UUID activeOwnerId;

    /**
     * 每名玩家当前在场的受控宝可梦（按召唤顺序）。
     *
     * <p>用 {@link LinkedHashMap} 保证遍历顺序稳定；列表里只存实体引用，
     * 死亡/被移除的实体在 {@link #prune(UUID)} 里清理。
     */
    private static final Map<UUID, List<PokemonEntity>> OWNED = new LinkedHashMap<>();
    /** 每名玩家当前"选中"（HUD/技能/上帝视角跟随）的那只受控实体。 */
    private static final Map<UUID, UUID> SELECTED = new LinkedHashMap<>();

    static {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, damage) -> {
            if (!(entity instanceof PokemonEntity pokemon)) return;
            UUID owner = getControlledOwner(pokemon);
            if (owner == null || entity.level().getServer() == null) return;
            rememberFainted(owner, pokemon);
            ServerPlayer player = entity.level().getServer().getPlayerList().getPlayer(owner);
            if (player != null) com.pkmoba.pokemon.TeamService.markDead(player, pokemon);
        });
        // 离线清理：玩家退出时收回其所有在场宝可梦，避免实体永远留在世界里没人管。
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            discardAllControlled(handler.player);
            // 房间系统：玩家掉线时释放他占的房位，避免空房间永远挂着。
            com.pkmoba.duel.RoomManager.onQuit(handler.player);
        });
        // 服务器停止时清空静态登记，避免换世界/重启后引用到旧实体。
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            OWNED.clear();
            SELECTED.clear();
            reset();
        });
    }

    /**
     * 该玩家当前"选中"的那只受控宝可梦；没有则退回旧版单实体（训练场）兼容路径。
     *
     * <p>语义保持不变：所有旧调用点拿到的都是"玩家正在操控的那一只"，
     * 多只同时在场时由 {@link #select} / {@link #selectNext} 决定是谁。
     */
    @Nullable
    public static PokemonEntity getControlled(ServerPlayer player) {
        if (player == null) return null;
        PokemonEntity selected = selectedOf(player.getUUID());
        if (selected != null) return selected;
        return player.getUUID().equals(activeOwnerId) ? getActive(player.serverLevel().getServer()) : null;
    }

    /** 语义同 {@link #getControlled}：当前选中的那只。 */
    @Nullable
    public static PokemonEntity getSelected(ServerPlayer player) {
        return getControlled(player);
    }

    /** 该玩家场上所有受控实体（按召唤顺序，已剔除死亡/被移除的）。 */
    public static List<PokemonEntity> getActiveList(ServerPlayer player) {
        return player == null ? List.of() : prune(player.getUUID());
    }

    /** {@link #getActiveList} 的别名，方便调用点按"activeList"命名。 */
    public static List<PokemonEntity> activeList(ServerPlayer player) {
        return getActiveList(player);
    }

    /** 该玩家场上指定物种的那只（活着才返回）；用于"重复召唤同一只 → 挪位置而不是再生成"。 */
    @Nullable
    public static PokemonEntity findControlled(ServerPlayer player, @Nullable ResourceLocation speciesId) {
        if (player == null || speciesId == null) return null;
        for (PokemonEntity entity : getActiveList(player)) {
            if (speciesId.equals(entity.getPokemon().getSpecies().getResourceIdentifier())) return entity;
        }
        return null;
    }

    /** 把"选中"切到指定实体（必须已经登记在该玩家名下且活着）。 */
    public static boolean select(ServerPlayer player, PokemonEntity entity) {
        if (player == null || entity == null) return false;
        if (!getActiveList(player).contains(entity)) return false;
        PokemonEntity previous = selectedOf(player.getUUID());
        if (previous != null && previous != entity) com.pkmoba.combat.VenusaurCombat.clearTransient(previous);
        SELECTED.put(player.getUUID(), entity.getUUID());
        broadcastSync(player.server);
        return true;
    }

    /**
     * 多只在场时按召唤顺序轮换"选中"（C 键切换用，不收回任何实体）。
     *
     * @return 新的选中实体；场上没有实体时返回 null
     */
    @Nullable
    public static PokemonEntity selectNext(ServerPlayer player) {
        List<PokemonEntity> list = getActiveList(player);
        if (list.isEmpty()) return null;
        PokemonEntity current = selectedOf(player.getUUID());
        int index = current == null ? -1 : list.indexOf(current);
        PokemonEntity next = list.get((index + 1) % list.size());
        if (current != null && current != next) com.pkmoba.combat.VenusaurCombat.clearTransient(current);
        SELECTED.put(player.getUUID(), next.getUUID());
        broadcastSync(player.server);
        return next;
    }

    @Nullable
    public static UUID getControlledOwner(PokemonEntity entity) {
        if (entity == null) return null;
        UUID targetId = entity.getUUID();
        // 旧版单实体优先（训练场那条路径不进 OWNED 表）
        if (targetId.equals(activeEntityId)) return activeOwnerId;
        for (UUID owner : new ArrayList<>(OWNED.keySet())) {
            for (PokemonEntity owned : OWNED.getOrDefault(owner, List.of())) {
                if (owned != null && owned.getUUID().equals(targetId)) return owner;
            }
        }
        return null;
    }

    /** 服务器上所有在场受控实体（所有玩家 + 旧版训练实体），战斗/寻路 tick 遍历用。 */
    public static List<PokemonEntity> getControlledEntities(MinecraftServer server) {
        List<PokemonEntity> result = new ArrayList<>();
        PokemonEntity training = getActive(server);
        if (training != null) result.add(training);
        for (UUID owner : new ArrayList<>(OWNED.keySet())) {
            for (PokemonEntity entity : prune(owner)) {
                if (entity.level().getServer() == server && !result.contains(entity)) result.add(entity);
            }
        }
        return result;
    }

    /**
     * 登记一只受控实体并把它设为"选中"。
     *
     * <p>0.38 起不再"每个玩家只能有一只"：同玩家的多只并存，按登记顺序排列。
     */
    public static void registerControlled(UUID owner, PokemonEntity entity) {
        if (owner == null || entity == null) return;
        List<PokemonEntity> list = OWNED.computeIfAbsent(owner, id -> new ArrayList<>());
        list.removeIf(e -> {
            boolean removed = e == null || e.isRemoved() || !e.isAlive();
            if (removed && e != null) {
                rememberFainted(owner, e);
                com.pkmoba.combat.VenusaurCombat.reset(e);
            }
            return removed;
        });
        boolean exists = false;
        for (PokemonEntity owned : list) {
            if (owned.getUUID().equals(entity.getUUID())) {
                exists = true;
                break;
            }
        }
        if (!exists) list.add(entity);
        SELECTED.put(owner, entity.getUUID());
    }

    /** 清空该玩家的全部受控登记（对局结束用；实体由调用方负责 discard）。 */
    public static void unregisterControlled(UUID owner) {
        if (owner == null) return;
        OWNED.remove(owner);
        SELECTED.remove(owner);
    }

    /** 只摘掉某一只受控登记（死亡/单独删除用；同玩家其它在场实体不受影响）。 */
    public static void unregisterControlled(ServerPlayer player, PokemonEntity entity) {
        if (player == null || entity == null) return;
        UUID ownerId = player.getUUID();
        List<PokemonEntity> list = OWNED.get(ownerId);
        if (list == null) return;
        list.removeIf(e -> e == null || e.getUUID().equals(entity.getUUID()));
        if (list.isEmpty()) {
            OWNED.remove(ownerId);
            SELECTED.remove(ownerId);
        } else if (entity.getUUID().equals(SELECTED.get(ownerId))) {
            // 选中的被摘掉了：交给 prune 重新选一只（默认最近召唤的）
            SELECTED.remove(ownerId);
        }
    }

    /**
     * 收回该玩家<b>当前选中</b>的那只（不记为阵亡）。
     *
     * <p>0.38 起只收选中那一只；同玩家其它在场实体继续保留、各自独立行动。
     * 收回后选中会落到剩下的某一只上（没有则空）。
     *
     * @return 被收回的实体；场上没有返回 null
     */
    @Nullable
    public static PokemonEntity discardControlled(ServerPlayer player) {
        if (player == null) return null;
        PokemonEntity selected = getControlled(player);
        if (selected == null) return null;
        discardControlled(player, selected);
        return selected;
    }

    /** 收回该玩家场上的指定一只（不记为阵亡）。 */
    public static void discardControlled(ServerPlayer player, PokemonEntity entity) {
        if (player == null || entity == null) return;
        com.pkmoba.pokemon.TeamService.rememberHealth(player, entity);
        com.pkmoba.combat.VenusaurCombat.reset(entity);
        unregisterControlled(player, entity);
        if (!entity.isRemoved()) entity.discard();
        broadcastSync(player.server);
    }

    /**
     * 收回该玩家<b>所有</b>在场宝可梦（删除全部 / 离线 / 对局入场清场用）。
     *
     * <p>旧版训练场单实体（{@link #spawn} 生成的）在 owner 匹配时一并清掉。
     */
    public static void discardAllControlled(ServerPlayer player) {
        if (player == null) return;
        List<PokemonEntity> list = new ArrayList<>(prune(player.getUUID()));
        OWNED.remove(player.getUUID());
        SELECTED.remove(player.getUUID());
        boolean changed = false;
        for (PokemonEntity entity : list) {
            com.pkmoba.pokemon.TeamService.rememberHealth(player, entity);
            com.pkmoba.combat.VenusaurCombat.reset(entity);
            if (!entity.isRemoved()) {
                entity.discard();
                changed = true;
            }
        }
        if (player.getUUID().equals(activeOwnerId)) {
            PokemonEntity training = getActive(player.server);
            if (training != null && !training.isRemoved()) {
                com.pkmoba.combat.VenusaurCombat.reset(training);
                training.discard();
                changed = true;
            }
            reset();
        }
        if (changed) broadcastSync(player.server);
    }

    public static boolean isMatchEntity(PokemonEntity entity) {
        if (entity == null) return false;
        if (activeEntityId != null && activeEntityId.equals(entity.getUUID())) return true;
        for (List<PokemonEntity> list : OWNED.values()) {
            for (PokemonEntity owned : list) {
                if (owned == entity || owned.getUUID().equals(entity.getUUID())) return true;
            }
        }
        return false;
    }

    /**
     * 清理某玩家的列表：剔除死亡/被移除的实体，并在选中失效时重新选一只。
     *
     * @return 清理后的列表（可能为空但不会是 null）
     */
    private static void rememberFainted(UUID ownerId, PokemonEntity entity) {
        if (entity.getHealth() > 0 && entity.getPokemon().getCurrentHealth() > 0) return;
        MinecraftServer server = entity.level().getServer();
        if (server == null) return;
        com.pkmoba.pokemon.PlayerProfile profile = com.pkmoba.pokemon.TeamService.profile(server, ownerId);
        String species = com.pkmoba.pokemon.TeamService.speciesOf(entity);
        if (profile.team.contains(species)) {
            profile.markFainted(species);
            com.pkmoba.pokemon.ProfileStore.get(server).setDirty();
        }
    }

    private static List<PokemonEntity> prune(UUID ownerId) {
        List<PokemonEntity> list = OWNED.get(ownerId);
        if (list == null) return List.of();
        list.removeIf(e -> {
            boolean removed = e == null || e.isRemoved() || !e.isAlive();
            if (removed && e != null) {
                rememberFainted(ownerId, e);
                com.pkmoba.combat.VenusaurCombat.reset(e);
            }
            return removed;
        });
        if (list.isEmpty()) {
            OWNED.remove(ownerId);
            SELECTED.remove(ownerId);
            return List.of();
        }
        UUID selectedId = SELECTED.get(ownerId);
        boolean found = false;
        if (selectedId != null) {
            for (PokemonEntity entity : list) {
                if (entity.getUUID().equals(selectedId)) {
                    found = true;
                    break;
                }
            }
        }
        // 选中的死了/被移除了：默认落到"最近召唤的那只"，保证玩家永远在操作一只存在的宝可梦。
        if (!found) SELECTED.put(ownerId, list.get(list.size() - 1).getUUID());
        return list;
    }

    @Nullable
    private static PokemonEntity selectedOf(UUID ownerId) {
        List<PokemonEntity> list = prune(ownerId);
        if (list.isEmpty()) return null;
        UUID selectedId = SELECTED.get(ownerId);
        if (selectedId != null) {
            for (PokemonEntity entity : list) {
                if (entity.getUUID().equals(selectedId)) return entity;
            }
        }
        return list.get(list.size() - 1);
    }

    private CharizardManager() {
    }

    /**
     * 在指定位置生成喷火龙（会自动贴到最近地面）。
     *
     * @return 成功返回实体，已存在/物种缺失/被事件取消时返回 null
     */
    @Nullable
    public static PokemonEntity spawn(ServerLevel level, Vec3 pos, @Nullable ServerPlayer owner) {
        return spawn(level, pos, owner, CHARIZARD_ID);
    }

    @Nullable
    public static PokemonEntity spawn(ServerLevel level, Vec3 pos, @Nullable ServerPlayer owner,
                                      ResourceLocation speciesId) {
        if (getActive(level.getServer()) != null) {
            return null;
        }

        if (!com.pkmoba.pokemon.SpeciesRegistry.contains(speciesId)) return null;
        Species species = PokemonSpecies.getByIdentifier(speciesId);
        if (species == null) {
            PkmobaMod.LOGGER.error("[pkmoba] 找不到物种 {}，Cobblemon 数据是否加载完成？", speciesId);
            return null;
        }

        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(species);
        pokemon.setLevel(50);
        if ("pikachu".equals(speciesId.getPath())) pokemon.setScaleModifier(2.0F);

        // 注意：Cobblemon 是 Kotlin，回调类型是 Function1<PokemonEntity, Unit>，
        // invoke 有返回值，所以必须写成显式匿名类并 return Unit.INSTANCE。
        PokemonEntity entity = pokemon.sendOut(level, groundPos(level, pos), null, new Function1<PokemonEntity, Unit>() {
            @Override
            public Unit invoke(PokemonEntity e) {
                // 不设 ownerUUID，原因见类注释
                e.setPersistenceRequired();
                e.setInvulnerable(false);
                return Unit.INSTANCE;
            }
        });

        if (entity == null) {
            PkmobaMod.LOGGER.warn("[pkmoba] sendOut 返回 null（POKEMON_SENT_PRE 被取消）");
            return null;
        }

        activeEntityId = entity.getUUID();
        activeDimension = level.dimension().location();
        activeOwnerId = owner == null ? null : owner.getUUID();
        // 召唤即记录圆形场地（圆心 = 召唤者位置，半径默认 48 格，可用 /mo arena 改）。
        // 0.38：这只作为 HUD/对局信息 —— CharizardCombat.arenaEnforced 默认 false，不会拉回宝可梦。
        com.pkmoba.combat.CharizardCombat.setArena(
                owner == null ? pos : owner.position(),
                com.pkmoba.combat.CharizardCombat.MAX_DISTANCE_FROM_PLAYER);
        PkmobaMod.LOGGER.info("[pkmoba] 召唤宝可梦成功 species={} uuid={} pos={} level={}",
                speciesId, entity.getUUID(), entity.position(), level.dimension().location());
        broadcastSync(level.getServer());
        return entity;
    }

    @Nullable
    public static PokemonEntity getActive(MinecraftServer server) {
        if (activeEntityId == null) {
            return null;
        }
        ServerLevel level = resolveLevel(server);
        if (level == null) {
            reset();
            return null;
        }
        Entity entity = level.getEntity(activeEntityId);
        if (!(entity instanceof PokemonEntity pokemonEntity) || !pokemonEntity.isAlive()) {
            reset();
            broadcastSync(server);
            return null;
        }
        return pokemonEntity;
    }

    @Nullable
    private static ServerLevel resolveLevel(MinecraftServer server) {
        if (activeDimension == null) {
            return server.overworld();
        }
        return server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, activeDimension));
    }

    public static boolean remove(MinecraftServer server) {
        PokemonEntity entity = getActive(server);
        if (entity == null) {
            return false;
        }
        PkmobaMod.LOGGER.info("[pkmoba] 移除受控宝可梦 uuid={}", entity.getUUID());
        com.pkmoba.combat.VenusaurCombat.reset(entity);
        entity.discard();
        reset();
        broadcastSync(server);
        return true;
    }

    @Nullable
    public static UUID getActiveOwnerId() {
        return activeOwnerId;
    }

    /** 把所有玩家客户端的"当前喷火龙"同步成最新状态（每名玩家发自己选中的那只）。 */
    public static void broadcastSync(MinecraftServer server) {
        PokemonEntity entity = getActive(server);
        int id = entity == null ? -1 : entity.getId();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PokemonEntity personal = getControlled(player);
            int personalId;
            if (personal != null) {
                personalId = personal.getId();
            } else if (OWNED.containsKey(player.getUUID())) {
                // 有受控记录但都失效了：明确告诉客户端"现在没有"，别让它继续跟旧实体
                personalId = -1;
            } else {
                personalId = id;
            }
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, CharizardSyncPayload.TYPE)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new CharizardSyncPayload(personalId));
            }
        }
    }

    private static void reset() {
        activeEntityId = null;
        activeDimension = null;
        activeOwnerId = null;
    }

    /** Spawn a match participant without replacing the training singleton. Caller owns rollback/cleanup. */
    @Nullable
    public static PokemonEntity spawnMatchEntity(ServerLevel level, Vec3 pos, ResourceLocation speciesId) {
        return spawnMatchEntityExact(level, groundPos(level, pos), speciesId);
    }

    /** Does not search another floor. The caller has validated the authoritative requested position. */
    @Nullable
    public static PokemonEntity spawnMatchEntityExact(ServerLevel level, Vec3 pos, ResourceLocation speciesId) {
        if (!com.pkmoba.pokemon.SpeciesRegistry.contains(speciesId)) return null;
        Species species = PokemonSpecies.getByIdentifier(speciesId);
        if (species == null) return null;
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(species);
        pokemon.setLevel(50);
        if ("pikachu".equals(speciesId.getPath())) pokemon.setScaleModifier(2.0F);
        return pokemon.sendOut(level, pos, null, e -> {
            e.setPos(pos.x, pos.y, pos.z);
            if (level.getBlockCollisions(e, e.getBoundingBox().deflate(.001)).iterator().hasNext()) {
                e.discard();
                return Unit.INSTANCE;
            }
            e.setPersistenceRequired();
            e.setInvulnerable(false);
            // 对局双方统一血量：Cobblemon 默认按等级/种族算的血太少，导致"打一下就死"。
            float maxHp = com.pkmoba.combat.CharizardCombat.maxHpFor(e);
            e.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                    .setBaseValue(maxHp);
            com.pkmoba.combat.PokemonHp.apply(e, maxHp);
            e.setHealth(maxHp);
            com.pkmoba.combat.PokemonHp.apply(e, maxHp);
            return Unit.INSTANCE;
        });
    }

    /** 从给定位置向下找第一个有碰撞的方块，站在它正上方一格。 */
    private static Vec3 groundPos(ServerLevel level, Vec3 pos) {
        BlockPos.MutableBlockPos cursor = BlockPos.containing(pos.x, pos.y + 0.5, pos.z).mutable();
        int minY = level.getMinBuildHeight();
        while (cursor.getY() > minY) {
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
                return new Vec3(pos.x, cursor.getY() + 1.0, pos.z);
            }
            cursor.move(0, -1, 0);
        }
        return pos;
    }
}
