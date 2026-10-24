package com.pkmoba.charizard;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.pkmoba.PkmobaMod;
import com.pkmoba.network.CharizardSyncPayload;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 受控宝可梦的唯一管理者（保留历史类名）：全局只允许存在一只受控实体。
 *
 * <p>关键决策（都踩过坑）：
 * <ul>
 *   <li><b>绝不设置 entity.ownerUUID</b>：Cobblemon 的 PokemonEntity.stopSeenByPlayer() 里有
 *       {@code if (ownerUUID == player.uuid && tethering == null) remove(DISCARDED)}，
 *       一旦认主，玩家不再追踪到它就会被当成"召回出战宝可梦"直接删除。</li>
 *   <li><b>不关 AI</b>：用户要求走原生寻路，而 {@code isNoAi=true} 会让 navigation 停止 tick，
 *       连绕障都不会有。改用"保留 AI + 每 tick 覆盖 navigation + 清空攻击目标"。</li>
 *   <li><b>鲁棒性</b>：{@code setPersistenceRequired()} 挡自然 despawn；保留正常受伤与死亡。</li>
 * </ul>
 */
public final class CharizardManager {
    public static final ResourceLocation CHARIZARD_ID =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "charizard");

    public static final ResourceLocation BLASTOISE_ID =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "blastoise");

    public static boolean isBlastoise(PokemonEntity entity) {
        return entity != null && BLASTOISE_ID.equals(entity.getPokemon().getSpecies().getResourceIdentifier());
    }

    @Nullable
    private static UUID activeEntityId;
    @Nullable
    private static ResourceLocation activeDimension;
    @Nullable
    private static UUID activeOwnerId;

    // Match entities are separate from the legacy training singleton.
    private static final java.util.Map<UUID, PokemonEntity> CONTROLLED = new java.util.HashMap<>();

    @Nullable
    public static PokemonEntity getControlled(ServerPlayer player) {
        PokemonEntity entity = CONTROLLED.get(player.getUUID());
        if (entity != null) return entity.isAlive() && !entity.isRemoved() ? entity : null;
        return player.getUUID().equals(activeOwnerId) ? getActive(player.serverLevel().getServer()) : null;
    }

    @Nullable
    public static UUID getControlledOwner(PokemonEntity entity) {
        for (var entry : CONTROLLED.entrySet()) {
            if (entry.getValue() == entity) return entry.getKey();
        }
        return entity != null && entity.getUUID().equals(activeEntityId) ? activeOwnerId : null;
    }

    public static java.util.List<PokemonEntity> getControlledEntities(MinecraftServer server) {
        java.util.List<PokemonEntity> result = new java.util.ArrayList<>();
        PokemonEntity training = getActive(server);
        if (training != null) result.add(training);
        for (PokemonEntity entity : CONTROLLED.values()) {
            if (entity.level().getServer() == server && entity.isAlive() && !entity.isRemoved()
                    && !result.contains(entity)) result.add(entity);
        }
        return result;
    }

    public static void registerControlled(UUID owner, PokemonEntity entity) {
        if (CONTROLLED.containsKey(owner)) throw new IllegalStateException("Owner already controls a match entity");
        CONTROLLED.put(owner, entity);
    }

    public static void unregisterControlled(UUID owner) {
        CONTROLLED.remove(owner);
    }

    /**
     * 收回该玩家当前的受控宝可梦：从控制表摘掉并移除实体（<b>不</b>记为阵亡）。
     *
     * <p>统一从这里走，避免"只 unregister 不 discard"导致旧实体还留在世界里，
     * 再召唤时又生成一只 —— 那就是玩家反馈的"无限召唤"。
     */
    public static void discardControlled(ServerPlayer player) {
        PokemonEntity entity = CONTROLLED.remove(player.getUUID());
        if (entity == null && player.getUUID().equals(activeOwnerId)) {
            entity = getActive(player.server);
            if (entity != null) {
                entity.discard();
                reset();
            }
        }
        if (entity != null) {
            if (!entity.isRemoved()) entity.discard();
            broadcastSync(player.server);
        }
    }

    public static boolean isMatchEntity(PokemonEntity entity) {
        return CONTROLLED.containsValue(entity);
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

        if (!CHARIZARD_ID.equals(speciesId) && !BLASTOISE_ID.equals(speciesId)) return null;
        Species species = PokemonSpecies.getByIdentifier(speciesId);
        if (species == null) {
            PkmobaMod.LOGGER.error("[pkmoba] 找不到物种 {}，Cobblemon 数据是否加载完成？", speciesId);
            return null;
        }

        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(species);
        pokemon.setLevel(50);

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
        // 召唤即设定圆形场地：圆心 = 召唤者位置，半径默认 48 格（可用 /mo arena 改）
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
        entity.discard();
        reset();
        broadcastSync(server);
        return true;
    }

    @Nullable
    public static UUID getActiveOwnerId() {
        return activeOwnerId;
    }

    /** 把所有玩家客户端的"当前喷火龙"同步成最新状态。 */
    public static void broadcastSync(MinecraftServer server) {
        PokemonEntity entity = getActive(server);
        int id = entity == null ? -1 : entity.getId();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PokemonEntity personal = getControlled(player);
            int personalId = personal != null ? personal.getId() : CONTROLLED.containsKey(player.getUUID()) ? -1 : id;
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
        if (!CHARIZARD_ID.equals(speciesId) && !BLASTOISE_ID.equals(speciesId)) return null;
        Species species = PokemonSpecies.getByIdentifier(speciesId);
        if (species == null) return null;
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(species);
        pokemon.setLevel(50);
        return pokemon.sendOut(level, groundPos(level, pos), null, e -> {
            e.setPersistenceRequired();
            e.setInvulnerable(false);
            // 对局双方统一血量：Cobblemon 默认按等级/种族算的血太少，导致"打一下就死"。
            float maxHp = com.pkmoba.combat.CharizardCombat.maxHpFor(e);
            e.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                    .setBaseValue(maxHp);
            e.setHealth(maxHp);
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
