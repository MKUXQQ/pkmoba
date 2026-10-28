package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.godmode.ServerGodMode;
import com.pkmoba.move.MoveOrderService;
import com.pkmoba.network.SkillStatePayload;
import com.pkmoba.network.VenusaurEffectPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-authoritative Venusaur skills. Cooldowns/beans survive switching; transient effects do not. */
public final class VenusaurCombat {
    public static final String KIND_Q_VINE = "Q_VINE";
    public static final String KIND_W_CHARGE = "W_CHARGE";
    public static final String KIND_W_LEAF = "W_LEAF";
    public static final String KIND_E_CHARGE = "E_CHARGE";
    public static final String KIND_E_BEAM = "E_BEAM";
    public static final String KIND_R_FIELD = "R_FIELD";
    public static final String KIND_SEED_SHOT = "SEED_SHOT";
    public static final String KIND_CLEAR = "CLEAR";
    public static final double Q_RANGE = 14.0, Q_RADIUS = 1.25, W_RANGE = 16.0,
            E_RANGE = 22.0, E_RADIUS = 0.36, R_RANGE = 15.0, R_RADIUS = 4.0;
    public static final float Q_DAMAGE = 20, W_LEAF_DAMAGE = 8, E_DAMAGE = 28,
            R_BURST_DAMAGE = 20, R_POISON_DAMAGE = 4;
    public static final int Q_HIT_TICK = 20, Q_CAST_TICKS = 30,
            R_CHARGE_TICKS = 30, R_CAST_TICKS = 48, R_FIELD_TICKS = 70;
    /**
     * 0.48.5：Q 藤鞭只在"点击方向"左右各 {@code 25°} 的窄扇形内索敌（存 cos 值省掉每个候选的 atan2）。
     * 超出扇形不自动转身去打侧面/背后，宁可朝点击方向抽空。
     */
    private static final double Q_CONE_COS = Math.cos(Math.toRadians(25.0));
    private static final ResourceLocation SLOW_ID = ResourceLocation.fromNamespaceAndPath("pkmoba", "venusaur_slow");
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Map<UUID, SlowState> SLOWS = new HashMap<>();
    private static boolean initialized;

    private VenusaurCombat() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        PayloadTypeRegistry.playS2C().register(VenusaurEffectPayload.TYPE, VenusaurEffectPayload.CODEC);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> resetAll());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (State state : new ArrayList<>(STATES.values())) {
                PokemonEntity entity = state.entity;
                if (!entity.isAlive() || entity.isRemoved()) {
                    reset(entity);
                    continue;
                }
                ServerPlayer owner = server.getPlayerList().getPlayer(state.owner);
                if (owner == null) owner = com.pkmoba.duel.BotDuelService.actor(state.owner);
                if (owner == null || owner.level() != entity.level()
                        || !state.dimension.equals(entity.level().dimension())) {
                    reset(entity);
                } else if (CharizardManager.getControlled(owner) != entity || !authorizedActor(owner, entity)) {
                    clearTransient(entity);
                }
            }
            for (Iterator<SlowState> it = SLOWS.values().iterator(); it.hasNext();) {
                SlowState slow = it.next();
                slow.refresh();
                if (slow.sources.isEmpty() || !slow.target.isAlive() || slow.target.isRemoved()) {
                    removeSlowModifier(slow.target);
                    it.remove();
                }
            }
        });
    }

    public static void updateAim(PokemonEntity entity, Vec3 aim) {
        // 0.48.5：施法锁定期间不再接受新鼠标 —— 方向/落点在确认施法那一帧已定。
        if (CastLock.active(entity)) return;
        State state = STATES.get(entity.getUUID());
        if (state != null && finite(aim)) state.aim = aim;
    }

    public static void tick(ServerLevel level, PokemonEntity entity) {
        if (!Skill.isVenusaur(entity)) return;
        UUID owner = CharizardManager.getControlledOwner(entity);
        if (owner == null) { reset(entity); return; }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (player == null) player = com.pkmoba.duel.BotDuelService.actor(owner);
        State existing = STATES.get(entity.getUUID());
        if (player == null || player.level() != level
                || existing != null && (!existing.owner.equals(owner) || !existing.dimension.equals(level.dimension()))) {
            reset(entity);
            return;
        }
        State state = STATES.computeIfAbsent(entity.getUUID(), id -> new State(entity, owner));
        if (CharizardManager.getControlled(player) != entity || !authorizedActor(player, entity)) clearTransient(entity);
        state.tick(level);
    }

    public static void cast(ServerPlayer player, PokemonEntity entity, Skill skill, Vec3 aim) {
        if (!authorized(player, entity) || CharizardManager.getControlled(player) != entity) return;
        castCore(player, entity, skill, aim);
    }

    public static boolean canCastForBot(PokemonEntity entity, Skill skill) {
        if (entity == null || skill == null || !com.pkmoba.duel.BotDuelService.isBot(entity)) return false;
        ServerPlayer actor = com.pkmoba.duel.BotDuelService.actor(CharizardManager.getControlledOwner(entity));
        if (!authorized(actor, entity)) return false;
        State state = STATES.get(entity.getUUID());
        return state == null || state.channel == 0 && state.cooldowns[skill.ordinal()] <= 0
                && state.points >= Skill.cost(entity, skill.ordinal());
    }

    public static void castForBot(PokemonEntity entity, Skill skill, Vec3 aim) {
        if (entity == null || !com.pkmoba.duel.BotDuelService.isBot(entity) || aim == null) return;
        ServerPlayer actor = com.pkmoba.duel.BotDuelService.actor(CharizardManager.getControlledOwner(entity));
        if (!authorized(actor, entity)) return;
        castCore(actor, entity, skill, aim);
    }

    private static boolean authorized(ServerPlayer player, PokemonEntity entity) {
        return entity != null && Skill.isVenusaur(entity) && entity.isAlive() && player != null
                && player.isAlive() && !player.isSpectator() && authorizedActor(player, entity)
                && finiteAimOwner(player, entity);
    }

    private static boolean authorizedActor(ServerPlayer player, PokemonEntity entity) {
        if (player == null || entity == null
                || !player.getUUID().equals(CharizardManager.getControlledOwner(entity))) return false;
        if (com.pkmoba.duel.BotDuelService.isBot(entity)) {
            return player == com.pkmoba.duel.BotDuelService.actor(player.getUUID());
        }
        return ServerGodMode.isActive(player);
    }

    private static boolean finiteAimOwner(ServerPlayer player, PokemonEntity entity) {
        return player.getUUID().equals(CharizardManager.getControlledOwner(entity)) && player.level() == entity.level();
    }

    private static void castCore(ServerPlayer player, PokemonEntity entity, Skill skill, Vec3 aim) {
        if (skill == null || !finite(aim) || !(entity.level() instanceof ServerLevel level)) return;
        State state = STATES.computeIfAbsent(entity.getUUID(), id -> new State(entity, player.getUUID()));
        int index = skill.ordinal();
        if (state.channel != 0 || state.cooldowns[index] > 0 || state.points < Skill.cost(entity, index)) return;
        state.points -= Skill.cost(entity, index);
        state.age = 0;
        state.cooldowns[index] = Skill.cooldownTicks(entity, index);
        state.aim = aim;
        state.direction = CharizardCombat.aimDirection(entity, aim);
        state.channel = index + 1;
        state.elapsed = 0;
        if (skill == Skill.E) state.beamHit.clear();
        state.castTicks = switch (skill) { case Q -> Q_CAST_TICKS; case W -> 40; case E -> 40; case R -> R_CAST_TICKS; };
        state.castResolved = false;
        state.castOrigin = entity.position();
        state.destination = ground(level, entity, targeted(entity, aim, index == 1 ? W_RANGE : R_RANGE));
        if (skill == Skill.E) {
            MoveOrderService.clear(entity);
            entity.getNavigation().stop();
            stop(entity);
            sendEffect(level, entity, KIND_E_CHARGE, flower(entity), state.direction, E_RANGE, 28, 0);
        } else if (skill == Skill.Q) {
            state.vineTarget = acquire(level, entity, aim, Q_RANGE);
            state.updateVine(level);
        } else if (skill == Skill.W) {
            state.leaves.clear();
            sendEffect(level, entity, KIND_W_CHARGE, flower(entity), state.direction, 1.25, 20, 0);
        } else {
            state.projectileStart = flower(entity);
            sendEffect(level, entity, KIND_R_FIELD, state.projectileStart,
                    state.destination.subtract(state.projectileStart), R_RANGE, state.castTicks, -1);
        }
        // 0.48.5：确认施法这一帧锁死方向/落点（见 CastLock）。引导期间每 tick 重申的都是这份值，
        // 鼠标、AI、旧移动指令都改不了；藤鞭/落光/花之舞的判定点与模型朝向取自同一份。
        // 注意：Q 的藤鞭索敌只在"点击方向"的窄扇形内（见 acquireInCone），索敌结果不会反过来改这里的锁定方向。
        switch (skill) {
            case Q -> CastLock.begin(entity, CastLock.Shape.LINE, state.direction, null, state.castTicks);
            case W -> CastLock.begin(entity, CastLock.Shape.POINT, state.direction, state.destination, state.castTicks);
            // 0.48.5：E 是"蓄力 → 沿方向发射光束"（不是位移），所以锁 LINE；beam()/beamLength() 都读锁定的方向。
            case E -> CastLock.begin(entity, CastLock.Shape.LINE, state.direction, null, state.castTicks);
            case R -> CastLock.begin(entity, CastLock.Shape.POINT, state.direction, state.destination,
                    state.castTicks + R_FIELD_TICKS);
        }
        FacingService.request(entity, FacingService.Priority.CHANNEL,
                CastLock.dirOr(entity, state.direction), 360);
        syncHud(player, entity);
    }

    public static boolean isChanneling(PokemonEntity entity) { return channel(entity) != 0; }
    public static boolean blocksBasicAttack(PokemonEntity entity) { return isChanneling(entity); }
    public static boolean basicBlocked(PokemonEntity entity) { return blocksBasicAttack(entity); }
    public static boolean blocksMovement(PokemonEntity entity) { return channel(entity) == 3; }
    public static int channel(PokemonEntity entity) {
        State state = entity == null ? null : STATES.get(entity.getUUID());
        return state == null ? 0 : state.channel;
    }
    public static float charge(PokemonEntity entity) {
        State state = entity == null ? null : STATES.get(entity.getUUID());
        if (state == null) return 0;
        if (state.channel == 2) return Math.min(1, state.elapsed / 20.0F);
        if (state.channel == 3) return Math.min(1, state.elapsed / 28.0F);
        if (state.channel == 4) return Math.min(1, state.elapsed / (float) R_CHARGE_TICKS);
        return 0;
    }
    public static int cooldown(PokemonEntity entity, int index) {
        State state = entity == null ? null : STATES.get(entity.getUUID());
        return state == null || index < 0 || index > 3 ? 0 : state.cooldowns[index];
    }

    /** Fixed-speed click movement must use this in addition to the vanilla attribute modifier. */
    public static double movementSpeedMultiplier(PokemonEntity entity) {
        SlowState slow = entity == null ? null : SLOWS.get(entity.getUUID());
        return slow == null ? 1 : slow.multiplier();
    }

    public static void syncHud(ServerPlayer player, PokemonEntity entity) {
        if (player == null || player.connection == null || entity == null) return;
        UUID owner = CharizardManager.getControlledOwner(entity);
        if (owner == null) return;
        State state = STATES.computeIfAbsent(entity.getUUID(), id -> new State(entity, owner));
        if (!ServerPlayNetworking.canSend(player, SkillStatePayload.TYPE)) return;
        Vec3 center = CharizardCombat.arenaCenter();
        ServerPlayNetworking.send(player, new SkillStatePayload(entity.getHealth(), entity.getMaxHealth(), state.points,
                state.cooldowns[0], state.cooldowns[1], state.cooldowns[2], state.cooldowns[3], state.channel == 0 ? 0 : state.channel + 6, charge(entity),
                CharizardCombat.distanceToBorder(entity), (float) center.x, (float) center.y, (float) center.z,
                (float) CharizardCombat.arenaRadius()));
    }

    /** Clears cast/areas/marks/heals on switch without refunding cooldowns or beans. */
    public static void clearTransient(PokemonEntity entity) {
        if (entity == null) return;
        State state = STATES.get(entity.getUUID());
        if (state != null) state.clear();
        for (SlowState slow : SLOWS.values()) {
            slow.sources.remove(entity.getUUID());
            slow.refresh();
        }
    }
    public static void reset(PokemonEntity entity) {
        if (entity == null) return;
        clearTransient(entity);
        STATES.remove(entity.getUUID());
        SlowState slow = SLOWS.remove(entity.getUUID());
        if (slow != null) removeSlowModifier(slow.target);
    }
    public static void resetAll() {
        for (State state : new ArrayList<>(STATES.values())) state.clear();
        STATES.clear();
        for (SlowState slow : SLOWS.values()) removeSlowModifier(slow.target);
        SLOWS.clear();
        // 0.48.5：对局结束/全员收回时把施法锁与朝向仲裁一起清掉。
        CastLock.clearAll();
    }

    public static void sendEffect(ServerLevel level, PokemonEntity entity, String kind, Vec3 position,
                                  Vec3 direction, double radius, int durationTicks, float value) {
        VenusaurEffectPayload payload = new VenusaurEffectPayload(entity.getUUID(), kind, position, direction, radius, durationTicks, value);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(position) < 128 * 128 && ServerPlayNetworking.canSend(player, VenusaurEffectPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private static final class State {
        final PokemonEntity entity;
        final UUID owner;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        final int[] cooldowns = new int[4];
        final java.util.List<LeafShot> leaves = new ArrayList<>();
        LivingEntity vineTarget;
        Vec3 vineEnd;
        final Set<UUID> beamHit = new HashSet<>();
        float points = 10;
        int channel;
        int elapsed;
        int castTicks;
        boolean castResolved;
        int age;
        Vec3 aim;
        Vec3 direction;
        Vec3 destination;
        Vec3 projectileStart;
        Vec3 castOrigin;
        Vec3 flowerCenter;
        long flowerStart;
        boolean flowerPoison;

        State(PokemonEntity entity, UUID owner) {
            this.entity = entity; this.owner = owner; this.dimension = entity.level().dimension();
            this.direction = CharizardCombat.modelForward(entity);
            this.aim = entity.position().add(direction);
        }

        void tick(ServerLevel level) {
            for (int i = 0; i < 4; i++) if (cooldowns[i] > 0) cooldowns[i]--;
            if (++age % 20 == 0) points = Math.min(10, points + 1.0F);
            long now = level.getGameTime();
            if (channel != 0) {
                // 0.48.5：引导期间方向只读施法锁定值（起手那一帧已定），不再每 tick 重读鼠标。
                direction = CastLock.dirOr(entity, CharizardCombat.aimDirection(entity, aim));
                elapsed++;
                if (channel == 3) {
                    entity.getNavigation().stop();
                    stop(entity);
                    direction = CastLock.dirOr(entity, CharizardCombat.aimDirection(entity, aim));
                    FacingService.request(entity, FacingService.Priority.CHANNEL, direction, 360);
                    if (elapsed < 28) sendEffect(level, entity, KIND_E_CHARGE, flower(entity), direction, E_RANGE, 28 - elapsed, elapsed / 28.0F);
                    else if (elapsed < 40) {
                        beam(level);
                        sendEffect(level, entity, KIND_E_BEAM, flower(entity), direction,
                                beamLength(level), 40 - elapsed, 1.1F);
                    }
                    if (elapsed >= 40) finishCast();
                } else {
                    FacingService.request(entity, FacingService.Priority.CHANNEL, direction, 360);
                    if (channel == 1) {
                        if (elapsed <= Q_HIT_TICK) updateVine(level);
                        if (elapsed == Q_HIT_TICK) vine(level);
                        if (elapsed >= castTicks) finishCast();
                    } else if (channel == 2) {
                        // 0.48.5：引导期间不再重读鼠标（旧代码每 tick 用 aim 覆盖 direction，模型会跟着鼠标转）。
                        direction = CastLock.dirOr(entity, direction);
                        if (elapsed < 20) sendEffect(level, entity, KIND_W_CHARGE, flower(entity), direction, 1.25, 20 - elapsed, elapsed / 20.0F);
                        if (elapsed == 20 || elapsed == 24 || elapsed == 28) launchLeaf(level);
                        if (elapsed >= castTicks) finishCast();
                    } else if (channel == 4) {
                        // 0.48.5：落点在确认施法那一帧锁定（CastLock POINT），蓄力期间只读不回写；
                        // 旧代码每 tick 用实时 aim 重算 destination，等于"确认之后还能改落点"。
                        if (elapsed <= R_CHARGE_TICKS) {
                            projectileStart = flower(entity);
                            destination = CastLock.pointOr(entity, destination);
                            if (elapsed % 2 == 0) sendEffect(level, entity, KIND_R_FIELD, projectileStart,
                                    destination.subtract(projectileStart), R_RANGE, R_CAST_TICKS - elapsed, -1);
                        }
                        if (elapsed >= castTicks) {
                            sludgeBurst(level, now);
                            finishCast();
                        }
                    }
                }
            }
            tickLeaves(level);
            if (flowerCenter != null) {
                long age = now - flowerStart;
                if (age <= (flowerPoison ? R_FIELD_TICKS : 160)) {
                    for (LivingEntity target : enemies(level, entity, flowerCenter, flowerPoison ? R_RADIUS : 4)) {
                        if (flowerPoison) slow(entity, target, false, now + 10);
                        if (age > 0 && age % (flowerPoison ? 10 : 16) == 0)
                            damage(level, entity, target, flowerPoison ? R_POISON_DAMAGE : 3,
                                    flowerPoison ? TypeEffectiveness.MoveType.POISON : TypeEffectiveness.MoveType.GRASS);
                    }
                }
                if (age >= (flowerPoison ? R_FIELD_TICKS : 160)) flowerCenter = null;
            }
        }

        void updateVine(ServerLevel level) {
            // 0.48.5：藤鞭的朝向永远是"确认施法时的点击方向"（CastLock 的 LINE 方向），索敌结果不能反过来改它。
            // 目标只在锁定方向左右 25° 的窄扇形里找；目标失效/跑出扇形就在扇形内重找，找不到就沿点击方向直抽。
            Vec3 aimDir = CastLock.dirOr(entity, CharizardCombat.aimDirection(entity, aim));
            Vec3 flatDir = new Vec3(aimDir.x, 0.0, aimDir.z);
            if (flatDir.lengthSqr() < 1.0E-8) {
                flatDir = CharizardCombat.modelForward(entity);
            } else {
                flatDir = flatDir.normalize();
            }
            if (!validLock(level, entity, vineTarget, Q_RANGE) || !inCone(entity, vineTarget, flatDir)) {
                vineTarget = acquireInCone(level, entity, aim, Q_RANGE, flatDir);
            }
            Vec3 from = flower(entity);
            Vec3 end = from.add(flatDir.scale(Q_RANGE));
            if (vineTarget != null) {
                // 把藤鞭末端压回锁定射线上（取目标中心在射线上的投影长度）。否则偏离一点的目标会把整条藤鞭
                // 拽向自己，出现"模型朝点击方向、藤鞭却斜着抽"的视角错位；压回射线后判定与朝向同源。
                double along = vineTarget.getBoundingBox().getCenter().subtract(from).dot(flatDir);
                along = Math.max(0.0, Math.min(Q_RANGE, along));
                end = from.add(flatDir.scale(along));
            }
            HitResult hit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
            vineEnd = hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
            Vec3 delta = vineEnd.subtract(from);
            sendEffect(level, entity, KIND_Q_VINE, from, delta, delta.length(), Q_CAST_TICKS - elapsed, elapsed);
        }

        void vine(ServerLevel level) {
            Vec3 from = flower(entity);
            Vec3 end = vineEnd == null ? from.add(direction.scale(Q_RANGE)) : vineEnd;
            for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, end).inflate(Q_RADIUS),
                    candidate -> validEnemy(candidate, entity))) {
                AABB box = target.getBoundingBox().inflate(Q_RADIUS);
                if (!box.contains(from) && box.clip(from, end).isEmpty()) continue;
                if (!clearLine(level, entity, from, target.getBoundingBox().getCenter())) continue;
                if (damage(level, entity, target, Q_DAMAGE) <= 0) continue;
                slow(entity, target, true, level.getGameTime() + 24);
                Vec3 delta = target.position().subtract(entity.position()).multiply(1, 0, 1);
                if (delta.length() >= 6) pull(level, entity, target, delta.normalize().scale(-1));
            }
        }

        void launchLeaf(ServerLevel level) {
            LivingEntity target = acquire(level, entity, aim, W_RANGE);
            Vec3 from = flower(entity);
            Vec3 end = target == null ? from.add(direction.scale(W_RANGE)) : target.getBoundingBox().getCenter();
            leaves.add(new LeafShot(KIND_W_LEAF + "_" + elapsed, from, end, target));
        }

        void tickLeaves(ServerLevel level) {
            for (Iterator<LeafShot> it = leaves.iterator(); it.hasNext();) {
                LeafShot shot = it.next();
                if (validLock(level, entity, shot.target, W_RANGE + 2)) shot.end = shot.target.getBoundingBox().getCenter();
                Vec3 delta = shot.end.subtract(shot.position);
                Vec3 next = shot.position.add(delta.scale(1.0 / Math.max(1, 10 - shot.age)));
                HitResult wall = level.clip(new ClipContext(shot.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
                if (wall.getType() != HitResult.Type.MISS) next = wall.getLocation();
                sendEffect(level, entity, shot.kind, shot.position, next.subtract(shot.position), .32, 2, shot.age);
                LivingEntity struck = null;
                double closest = Double.MAX_VALUE;
                for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, new AABB(shot.position, next).inflate(.4),
                        candidate -> validEnemy(candidate, entity))) {
                    AABB box = target.getBoundingBox().inflate(.4);
                    Vec3 contact = box.contains(shot.position) ? shot.position : box.clip(shot.position, next).orElse(null);
                    if (contact != null && clearLine(level, entity, shot.position, target.getBoundingBox().getCenter())
                            && contact.distanceToSqr(shot.position) < closest) {
                        closest = contact.distanceToSqr(shot.position); struck = target;
                    }
                }
                if (struck != null) damage(level, entity, struck, W_LEAF_DAMAGE);
                shot.position = next;
                if (++shot.age >= 10 || struck != null || wall.getType() != HitResult.Type.MISS) it.remove();
            }
        }

        void beam(ServerLevel level) {
            Vec3 from = flower(entity);
            // 0.48.5：E 是"蓄力后沿确认方向发射光束"，方向读施法锁定值，不再每 tick 追鼠标。
            Vec3 beamDirection = CastLock.dirOr(entity, CharizardCombat.aimDirection(entity, aim));
            Vec3 intended = from.add(beamDirection.scale(E_RANGE));
            HitResult hit = level.clip(new ClipContext(from, intended, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
            Vec3 end = hit.getType() == HitResult.Type.MISS ? intended : hit.getLocation();
            double length = from.distanceTo(end);
            for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, end).inflate(E_RADIUS),
                    candidate -> validEnemy(candidate, entity))) {
                if (!target.getBoundingBox().inflate(E_RADIUS).contains(from) && target.getBoundingBox().inflate(E_RADIUS).clip(from, end).isEmpty()) continue;
                if (!beamHit.add(target.getUUID())) continue;
                damage(level, entity, target, E_DAMAGE);
            }
        }

        double beamLength(ServerLevel level) {
            Vec3 from = flower(entity);
            Vec3 beamDirection = CastLock.dirOr(entity, CharizardCombat.aimDirection(entity, aim));
            Vec3 intended = from.add(beamDirection.scale(E_RANGE));
            HitResult hit = level.clip(new ClipContext(from, intended, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
            return from.distanceTo(hit.getType() == HitResult.Type.MISS ? intended : hit.getLocation());
        }

        void sludgeBurst(ServerLevel level, long now) {
            Vec3 center = destination;
            for (LivingEntity target : enemies(level, entity, center, R_RADIUS)) {
                CombatDamage.apply(level, entity, target, R_BURST_DAMAGE,
                        com.pkmoba.network.EffectPayload.Element.PHYSICAL,
                        com.pkmoba.network.EffectPayload.Power.ULTIMATE, TypeEffectiveness.MoveType.POISON);
                slow(entity, target, false, now + 60);
            }
            flowerCenter = center;
            flowerStart = now;
            flowerPoison = true;
            sendEffect(level, entity, KIND_R_FIELD, center, Vec3.ZERO, R_RADIUS, R_FIELD_TICKS, 1);
        }

        void finishCast() {
            channel = 0;
            elapsed = 0;
            // 0.48.5：收招再锁 2 tick 再交还预瞄（释放瞬间不要被鼠标甩走）。
            CastLock.finish(entity, 2);
            FacingService.clear(entity);
        }
        void clear() {
            boolean active = channel != 0 || flowerCenter != null || !leaves.isEmpty();
            finishCast(); leaves.clear(); beamHit.clear(); vineTarget = null; vineEnd = null;
            flowerCenter = null; flowerPoison = false;
            // 0.48.5：取消/死亡/换人时硬释放（不能按 finish 留 2 tick）。
            CastLock.release(entity);
            if (active && entity.level() instanceof ServerLevel level) sendEffect(level, entity, KIND_CLEAR, entity.position(), Vec3.ZERO, 0, 0, 0);
        }
    }

    private static final class LeafShot {
        final String kind;
        final LivingEntity target;
        Vec3 position;
        Vec3 end;
        int age;
        LeafShot(String kind, Vec3 position, Vec3 end, LivingEntity target) {
            this.kind = kind; this.position = position; this.end = end; this.target = target;
        }
    }

    /** All-angle aim assist: cursor proximity ranks legal, visible opponents already in cast range. */
    private static LivingEntity acquire(ServerLevel level, PokemonEntity source, Vec3 aim, double range) {
        LivingEntity best = null;
        double score = Double.MAX_VALUE;
        for (LivingEntity candidate : enemies(level, source, source.position(), range)) {
            if (!validLock(level, source, candidate, range)) continue;
            double candidateScore = candidate.position().multiply(1, 0, 1).distanceToSqr(aim.multiply(1, 0, 1));
            if (candidateScore < score) { score = candidateScore; best = candidate; }
        }
        return best;
    }

    /**
     * 0.48.5：Q 藤鞭的"点击方向优先"索敌 —— 只在确认方向左右 {@link #Q_CONE_COS}（25°）的窄扇形内找目标，
     * 圈内仍按"离光标最近"排序；圈内没有目标就返回 null，藤鞭沿点击方向直抽。
     * 这样它不会自动转身/抽向侧面或背后的目标（用户要的是"我点哪儿打哪儿"）。
     */
    private static LivingEntity acquireInCone(ServerLevel level, PokemonEntity source, Vec3 aim, double range, Vec3 flatDir) {
        LivingEntity best = null;
        double score = Double.MAX_VALUE;
        for (LivingEntity candidate : enemies(level, source, source.position(), range)) {
            if (!validLock(level, source, candidate, range)) continue;
            if (!inCone(source, candidate, flatDir)) continue;
            double candidateScore = candidate.position().multiply(1, 0, 1).distanceToSqr(aim.multiply(1, 0, 1));
            if (candidateScore < score) { score = candidateScore; best = candidate; }
        }
        return best;
    }

    /** 目标是否落在 {@code flatDir} 左右 25° 的水平窄扇形内。 */
    private static boolean inCone(PokemonEntity source, LivingEntity target, Vec3 flatDir) {
        if (target == null) return false;
        Vec3 toTarget = target.position().subtract(source.position()).multiply(1, 0, 1);
        return toTarget.lengthSqr() > 1.0E-8 && toTarget.normalize().dot(flatDir) >= Q_CONE_COS;
    }
    private static boolean validEnemy(LivingEntity target, PokemonEntity source) {
        if (!BasicAttack.validTarget(target, source) || target.getUUID().equals(CharizardManager.getControlledOwner(source))) return false;
        if (target instanceof PokemonEntity pokemon) {
            UUID owner = CharizardManager.getControlledOwner(source);
            if (owner != null && owner.equals(CharizardManager.getControlledOwner(pokemon))) return false;
        }
        return true;
    }
    private static boolean validLock(ServerLevel level, PokemonEntity source, LivingEntity target, double range) {
        return target != null && target.level() == level && validEnemy(target, source)
                && inside(target, source.position(), range)
                && clearLine(level, source, flower(source), target.getBoundingBox().getCenter());
    }
    private static final class Slow { long qUntil; long rUntil; }
    private static final class SlowState {
        final LivingEntity target;
        final Map<UUID, Slow> sources = new HashMap<>();
        SlowState(LivingEntity target) { this.target = target; }
        double multiplier() {
            long now = target.level().getGameTime();
            double value = 1;
            for (Slow slow : sources.values()) value = Math.min(value, slow.qUntil > now ? 0.7 : slow.rUntil > now ? 0.8 : 1);
            return value;
        }
        void refresh() {
            long now = target.level().getGameTime();
            sources.values().removeIf(slow -> slow.qUntil <= now && slow.rUntil <= now);
            var attribute = target.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attribute == null) return;
            attribute.removeModifier(SLOW_ID);
            double multiplier = multiplier();
            if (multiplier < 1) attribute.addTransientModifier(new AttributeModifier(SLOW_ID, multiplier - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void slow(PokemonEntity source, LivingEntity target, boolean vine, long until) {
        SlowState state = SLOWS.computeIfAbsent(target.getUUID(), id -> new SlowState(target));
        Slow slow = state.sources.computeIfAbsent(source.getUUID(), id -> new Slow());
        if (vine) slow.qUntil = Math.max(slow.qUntil, until); else slow.rUntil = Math.max(slow.rUntil, until);
        state.refresh();
    }
    private static void removeSlowModifier(LivingEntity target) {
        var attribute = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null) attribute.removeModifier(SLOW_ID);
    }
    private static float damage(ServerLevel level, PokemonEntity source, LivingEntity target, float amount) {
        return damage(level, source, target, amount, TypeEffectiveness.MoveType.GRASS);
    }
    private static float damage(ServerLevel level, PokemonEntity source, LivingEntity target, float amount,
                                TypeEffectiveness.MoveType moveType) {
        float before = target.getHealth();
        return CombatDamage.apply(level, source, target, amount, moveType) ? Math.max(0, before - target.getHealth()) : 0;
    }
    private static java.util.List<LivingEntity> enemies(ServerLevel level, PokemonEntity source, Vec3 center, double radius) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(center.add(-radius, -3, -radius), center.add(radius, 3, radius)),
                target -> validEnemy(target, source) && inside(target, center, radius));
    }
    private static boolean inside(LivingEntity target, Vec3 center, double radius) {
        double x = target.getX() - center.x, z = target.getZ() - center.z;
        return x * x + z * z <= radius * radius && Math.abs(target.getY() - center.y) <= 3;
    }
    private static Vec3 targeted(PokemonEntity entity, Vec3 aim, double range) {
        Vec3 delta = aim.subtract(entity.position()).multiply(1, 0, 1);
        if (delta.lengthSqr() > range * range) delta = delta.normalize().scale(range);
        return CharizardCombat.clampToArena(entity, entity.position().add(delta));
    }
    private static Vec3 ground(ServerLevel level, PokemonEntity entity, Vec3 point) {
        HitResult hit = level.clip(new ClipContext(point.add(0, 3, 0), point.add(0, -6, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        return hit.getType() == HitResult.Type.MISS ? point : hit.getLocation().add(0, 0.03, 0);
    }
    private static boolean clearLine(ServerLevel level, PokemonEntity source, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source)).getType() == HitResult.Type.MISS;
    }
    private static void pull(ServerLevel level, PokemonEntity source, LivingEntity target, Vec3 direction) {
        Vec3 initial = target.position();
        Vec3 last = initial;
        // Swept 0.1-block steps: neither body collision nor the arena clamp may be crossed.
        for (int i = 1; i <= 10; i++) {
            Vec3 next = initial.add(direction.scale(i / 10.0));
            Vec3 bounded = CharizardCombat.clampToArena(target, next);
            if (bounded.distanceToSqr(next) > 0.0001 || !level.noCollision(target, target.getBoundingBox().move(next.subtract(initial)))
                    || !clearLine(level, source, last.add(0, target.getBbHeight() * 0.5, 0), next.add(0, target.getBbHeight() * 0.5, 0))) break;
            last = next;
        }
        if (last.distanceToSqr(initial) > 0) { target.setPos(last.x, last.y, last.z); target.hurtMarked = true; }
    }
    private static Vec3 flower(PokemonEntity entity) {
        return VenusaurAnchor.position(entity);
    }
    private static void stop(PokemonEntity entity) {
        Vec3 motion = entity.getDeltaMovement();
        entity.setDeltaMovement(0, motion.y, 0);
    }
    private static Vec3 turnTowards(Vec3 from, Vec3 to, double maxDegrees) {
        double a = Math.atan2(from.z, from.x), b = Math.atan2(to.z, to.x);
        double difference = Math.atan2(Math.sin(b - a), Math.cos(b - a));
        double limit = Math.toRadians(maxDegrees);
        double angle = a + Math.max(-limit, Math.min(limit, difference));
        return new Vec3(Math.cos(angle), 0, Math.sin(angle));
    }
    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
