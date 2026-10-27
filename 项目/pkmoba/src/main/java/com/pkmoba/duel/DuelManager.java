package com.pkmoba.duel;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.godmode.ServerGodMode;
import com.pkmoba.network.DuelStatePayload;
import com.pkmoba.network.FaintPayload;
import com.pkmoba.network.MatchResultPayload;
import com.pkmoba.network.SwitchPokemonPayload;
import com.pkmoba.pokemon.PlayerProfile;
import com.pkmoba.pokemon.ProfileStore;
import com.pkmoba.pokemon.SpeciesRegistry;
import com.pkmoba.pokemon.TeamService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端 1v1 对局登记与推进。
 *
 * <p>0.31 口径：双方各用自己的宝可梦；同一时刻只有一只在场；阵亡后按 C 手动换下一只，
 * 20 秒（400 tick）内不换自动判负；队伍打光立即判负。场地只提供 4 个点位，不设半径限制。
 */
public final class DuelManager {
    private static final Map<UUID, Invite> INVITES = new HashMap<>();
    private static final Map<UUID, Match> MATCHES = new HashMap<>();
    /** 界面挑战（道具发起）的有效期：10 秒，服务端 tick 计时。 */
    private static final int CHALLENGE_TICKS = 20 * 10;
    private static final int INVITE_TICKS = 20 * 30;
    /** 战败动画播放时长，之后才真正移除实体。 */
    private static final int FAINT_TICKS = 60;
    /** 阵亡后必须在这个时间内换人，否则判负。 */
    private static final int SWITCH_TICKS = 20 * 20;
    /** 队伍状态（含倒计时）推送间隔。 */
    private static final int TEAM_STATE_INTERVAL = 5;

    private static MinecraftServer server;
    private static long lastTeamStatePush;

    private DuelManager() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(DuelManager::tick);
        BotDuelService.init();
        ServerPlayNetworking.registerGlobalReceiver(SwitchPokemonPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    switchPokemon(context.player());
                }));
    }

    private static void tick(MinecraftServer running) {
        server = running;
        long now = running.getTickCount();
        if (now % 20 == 0) pushSwitchZones(running);
        INVITES.entrySet().removeIf(entry -> tickInvite(running, entry.getKey(), entry.getValue()));
        Iterator<Map.Entry<UUID, Match>> iterator = MATCHES.entrySet().iterator();
        while (iterator.hasNext()) {
            Match match = iterator.next().getValue();
            if (tickMatch(running, match)) {
                iterator.remove();
            }
        }
        for (Match match : MATCHES.values()) {
            pushOpponentHud(running, match);
        }
        if (now - lastTeamStatePush >= TEAM_STATE_INTERVAL) {
            lastTeamStatePush = now;
            for (Match match : MATCHES.values()) {
                ServerPlayer a = running.getPlayerList().getPlayer(match.a);
                ServerPlayer b = running.getPlayerList().getPlayer(match.b);
                if (a != null) TeamService.sendTeamState(a);
                if (b != null) TeamService.sendTeamState(b);
            }
        }
    }

    /** Keep visible zone geometry identical to the authoritative recall test. */
    private static void pushSwitchZones(MinecraftServer running) {
        for (ServerPlayer player : running.getPlayerList().getPlayers()) {
            if (!inMatch(player)) continue;
            var pokemon = CharizardManager.getControlled(player);
            ArenaStore.Arena arena = pokemon == null ? null : arenaForEntity(pokemon);
            if (arena == null) {
                RoomManager.Room room = RoomManager.roomOf(player.getUUID());
                arena = room == null ? null : ArenaStore.get(running).byName(room.arenaName);
                Match match = matchOf(player);
                if (arena == null && match != null) arena = match.arena;
            }
            if (arena != null) {
                sendSwitchZone(player, arena.switchPolygon(true), true);
                sendSwitchZone(player, arena.switchPolygon(false), false);
            }
        }
    }

    private static void sendSwitchZone(ServerPlayer player, java.util.List<Vec3> points, boolean red) {
        if (points.size() != 4) return;
        for (int i = 0; i < 4; i++) {
            Vec3 point = points.get(i);
            ServerPlayNetworking.send(player, new com.pkmoba.network.EffectPayload(
                    "pkmoba:switch_zone/" + (red ? "red" : "blue") + "/" + i,
                    point.x, point.y, point.z, 0, 0, 0));
        }
    }

    private static void pushOpponentHud(MinecraftServer running, Match match) {
        ServerPlayer a = running.getPlayerList().getPlayer(match.a);
        ServerPlayer b = running.getPlayerList().getPlayer(match.b);
        PokemonEntity ea = find(running, match.aEntity);
        PokemonEntity eb = find(running, match.bEntity);
        if (a != null) {
            if (eb != null) {
                sendOpponent(a, b, eb);
            } else if (b != null && TeamService.isActiveFainted(b)) {
                // 对手那只已经阵亡并被清场：明确推 0 血，别让血条停在旧数值
                sendOpponentFainted(a, b);
            }
        }
        if (b != null) {
            if (ea != null) {
                sendOpponent(b, a, ea);
            } else if (a != null && TeamService.isActiveFainted(a)) {
                sendOpponentFainted(b, a);
            }
        }
    }

    /** 对手那只阵亡后推一帧 0 血，保留玩家名/宝可梦名，便于 HUD 继续显示"已倒下"。 */
    private static void sendOpponentFainted(ServerPlayer viewer, ServerPlayer owner) {
        if (!ServerPlayNetworking.canSend(viewer, DuelStatePayload.TYPE)) return;
        PlayerProfile profile = ProfileStore.get(owner.server).profile(owner.getUUID());
        String active = profile.activeSpecies();
        int remaining = 0;
        for (String id : profile.team) if (!profile.fainted.contains(id)) remaining++;
        String name = active == null ? "" : com.pkmoba.pokemon.SpeciesRegistry.displayName(
                net.minecraft.resources.ResourceLocation.tryParse(active));
        String path = active == null ? "" : active.replace("cobblemon:", "");
        ServerPlayNetworking.send(viewer, new DuelStatePayload(true, 0.0F,
                com.pkmoba.combat.CharizardCombat.maxHpFor(active), owner.getUUID(),
                owner.getGameProfile().getName(), name, path, remaining));
    }

    private static void sendOpponent(ServerPlayer viewer, @Nullable ServerPlayer owner, PokemonEntity opponent) {
        if (!ServerPlayNetworking.canSend(viewer, DuelStatePayload.TYPE)) return;
        UUID ownerId = owner == null ? null : owner.getUUID();
        String ownerName = owner == null ? "" : owner.getGameProfile().getName();
        int remaining = 0;
        if (owner != null) {
            PlayerProfile profile = ProfileStore.get(owner.server).profile(owner.getUUID());
            for (String id : profile.team) if (!profile.fainted.contains(id)) remaining++;
        }
        ServerPlayNetworking.send(viewer, new DuelStatePayload(true,
                opponent.getHealth(), opponent.getMaxHealth(), ownerId, ownerName,
                opponent.getDisplayName().getString(),
                opponent.getPokemon().getSpecies().getResourceIdentifier().getPath(),
                remaining));
    }

    private static void clearOpponentHud(ServerPlayer player) {
        if (player == null) return;
        if (!ServerPlayNetworking.canSend(player, DuelStatePayload.TYPE)) return;
        ServerPlayNetworking.send(player, new DuelStatePayload(false, 0.0F, 1.0F,
                null, "", "", "", 0));
    }

    /** @return true 表示这局已经结束、应从表中移除 */
    private static boolean tickMatch(MinecraftServer running, Match match) {
        ServerPlayer a = running.getPlayerList().getPlayer(match.a);
        ServerPlayer b = running.getPlayerList().getPlayer(match.b);
        if (a == null || b == null) {
            if (a != null) a.sendSystemMessage(Component.literal("对手离线，对局结束。"));
            if (b != null) b.sendSystemMessage(Component.literal("对手离线，对局结束。"));
            end(match, a, b, find(running, match.aEntity), find(running, match.bEntity));
            return true;
        }

        long now = running.getTickCount();
        if (match.awaitingInitialSummon) {
            if (now < match.readyAt) {
                if (match.aEntity != null) { var entity = find(running, match.aEntity); setPreparation(entity, true); }
                if (match.bEntity != null) { var entity = find(running, match.bEntity); setPreparation(entity, true); }
                int seconds = (int) Math.ceil((match.readyAt - now) / 20.0);
                if ((match.readyAt - now) % 20 == 0) {
                    showTitle(a, Integer.toString(seconds), "已传送入场 · 请准备召唤");
                    showTitle(b, Integer.toString(seconds), "已传送入场 · 请准备召唤");
                    a.playNotifySound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), net.minecraft.sounds.SoundSource.PLAYERS, 0.65F, 1.0F);
                    b.playNotifySound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), net.minecraft.sounds.SoundSource.PLAYERS, 0.65F, 1.0F);
                }
                return false;
            }
            match.awaitingInitialSummon = false;
            showTitle(a, "开始召唤", "C 选择出战 · X 召唤");
            showTitle(b, "开始召唤", "C 选择出战 · X 召唤");
        }

        // 战败动画播完再真正移除实体
        match.aDyingEntity = tickDying(running, match.aDyingEntity, match.aDyingUntil, now);
        match.bDyingEntity = tickDying(running, match.bDyingEntity, match.bDyingUntil, now);

        if (tickSide(running, match, a, b, true, now)) return true;
        if (tickSide(running, match, b, a, false, now)) return true;
        boolean preparing = match.awaitingInitialSummon || match.aSwitchDeadline > now || match.bSwitchDeadline > now;
        PokemonEntity aPokemon = find(running, match.aEntity);
        PokemonEntity bPokemon = find(running, match.bEntity);
        setPreparation(aPokemon, preparing);
        setPreparation(bPokemon, preparing);
        return false;
    }

    private static void setPreparation(@Nullable PokemonEntity entity, boolean preparing) {
        if (entity == null) return;
        entity.setInvulnerable(preparing);
        com.pkmoba.pokemon.BallThrow.releaseLock(entity);
    }

    @Nullable
    private static UUID tickDying(MinecraftServer running, @Nullable UUID dyingId, long until, long now) {
        if (dyingId == null) return null;
        if (now >= until) {
            PokemonEntity entity = find(running, dyingId);
            if (entity != null) entity.discard();
            return null;
        }
        return dyingId;
    }

    /**
     * 推进某一方：检测阵亡 → 播战败动画 → 判负或启动换人倒计时。
     *
     * @return true 表示对局已结束
     */
    private static boolean tickSide(MinecraftServer running, Match match, ServerPlayer self,
                                    ServerPlayer opponent, boolean isA, long now) {
        UUID entityId = isA ? match.aEntity : match.bEntity;
        long deadline = isA ? match.aSwitchDeadline : match.bSwitchDeadline;

        if (entityId != null) {
            PokemonEntity entity = find(running, entityId);
            if (entity == null || !entity.isAlive()) {
                if (entity != null) {
                    TeamService.markDead(self, entity);
                    // 广播战败动画标记：客户端据 species 播放原生 faint 或自制动画
                    sendFaint(self, entity);
                    sendFaint(opponent, entity);
                }
                if (isA) {
                    match.aEntity = null;
                    match.aDyingEntity = entity == null ? null : entity.getUUID();
                    match.aDyingUntil = now + FAINT_TICKS;
                } else {
                    match.bEntity = null;
                    match.bDyingEntity = entity == null ? null : entity.getUUID();
                    match.bDyingUntil = now + FAINT_TICKS;
                }
                PokemonEntity surviving = CharizardManager.getControlled(opponent);
                if (surviving != null) {
                    surviving.setInvulnerable(true);
                    com.pkmoba.pokemon.BallThrow.releaseLock(surviving);
                }
                PlayerProfile profile = ProfileStore.get(running).profile(self.getUUID());
                if (!profile.hasUsableLeft()) {
                    boolean aWins = !isA;
                    scheduleFinish(match, now + FAINT_TICKS, aWins);
                    match.finishAt = now + FAINT_TICKS;
                    match.finishWinnerIsA = aWins;
                } else {
                    if (isA) {
                        match.aSwitchDeadline = now + SWITCH_TICKS;
                    } else {
                        match.bSwitchDeadline = now + SWITCH_TICKS;
                    }
                    PokemonEntity survivor = find(running, isA ? match.bEntity : match.aEntity);
                    if (survivor != null) setPreparation(survivor, true);
                    showTitle(self, "宝可梦倒下 · 请换宠", "按 C 选下一只，再按 X 召唤 · 限时 20 秒");
                    self.sendSystemMessage(Component.literal(
                            "你的宝可梦倒下了！按 C 选下一只，再按 X 召唤，剩余 " + usable(profile)
                                    + " 只，20 秒内不换判负。"));
                    opponent.sendSystemMessage(Component.literal(
                            self.getGameProfile().getName() + " 的宝可梦倒下了。"));
                }
                return false;
            }
        } else if (deadline > 0) {
            // 服务端按整秒持续提示，避免聊天提示被忽略。
            int seconds = (int) Math.max(0, (deadline - now + 19) / 20);
            if ((deadline - now) % 20 == 0) {
                self.displayClientMessage(Component.literal("换宠倒计时 " + seconds + " 秒 · C 选下一只 · X 召唤"), true);
                if (seconds <= 5) showTitle(self, "请换下一只 · " + seconds, "按 C 选择并确认出战");
            }
            if (now >= deadline) {
                match.finishReason = "换宠超时（20 秒）";
                boolean aWins = !isA;
                scheduleFinish(match, now, aWins);
                match.finishAt = now;
                match.finishWinnerIsA = aWins;
                return finish(running, match, isA ? self : opponent, isA ? opponent : self, aWins);
            }
        }

        // 已经排定结束时间：到点收尾
        if (match.finishAt > 0 && now >= match.finishAt) {
            return finish(running, match, isA ? self : opponent, isA ? opponent : self, match.finishWinnerIsA);
        }
        return false;
    }

    private static void scheduleFinish(Match match, long at, boolean aWins) {
        if (match.finishAt > 0) return;
        match.finishAt = at;
        match.finishWinnerIsA = aWins;
    }

    private static void showTitle(ServerPlayer player, String main, String subtitle) {
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, 35, 10));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(main)));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
    }

    private static void sendFaint(@Nullable ServerPlayer player, PokemonEntity entity) {
        if (player == null) return;
        if (!ServerPlayNetworking.canSend(player, FaintPayload.TYPE)) return;
        ServerPlayNetworking.send(player, new FaintPayload(entity.getId(), FAINT_TICKS));
    }

    private static int usable(PlayerProfile profile) {
        int count = 0;
        for (String id : profile.team) if (!profile.fainted.contains(id)) count++;
        return count;
    }

    private static boolean finish(MinecraftServer running, Match match, ServerPlayer a, ServerPlayer b, boolean aWins) {
        ServerPlayer winnerPlayer = aWins ? a : b;
        ServerPlayer loserPlayer = aWins ? b : a;
        String winner = winnerPlayer.getGameProfile().getName();
        String loser = loserPlayer.getGameProfile().getName();
        for (ServerPlayer player : List.of(a, b)) {
            player.sendSystemMessage(Component.literal(
                    "对战结束：" + winner + " 获胜（" + loser + "：" + match.finishReason + "）。"));
            if (ServerPlayNetworking.canSend(player, MatchResultPayload.TYPE)) {
                ServerPlayNetworking.send(player, new MatchResultPayload(winner,
                        player.getUUID().equals(winnerPlayer.getUUID()), match.finishReason));
            }
        }
        end(match, a, b, find(running, match.aEntity), find(running, match.bEntity));
        return true;
    }

    /** 管理员强制结束指定场地或全部对局。 */
    public static void stop(ServerPlayer admin, String target) {
        if (!admin.hasPermissions(2)) {
            admin.sendSystemMessage(Component.literal("只有管理员可以强制结束对局。"));
            return;
        }
        List<Match> targets = new ArrayList<>();
        if ("all".equalsIgnoreCase(target)) {
            targets.addAll(MATCHES.values());
        } else {
            for (Match match : MATCHES.values()) {
                if (match.arena != null && match.arena.name.equalsIgnoreCase(target)) {
                    targets.add(match);
                }
            }
        }
        if (targets.isEmpty()) {
            admin.sendSystemMessage(Component.literal("没有找到匹配的对局。"));
            return;
        }
        for (Match match : targets) {
            ServerPlayer a = admin.server.getPlayerList().getPlayer(match.a);
            ServerPlayer b = admin.server.getPlayerList().getPlayer(match.b);
            for (ServerPlayer player : List.of(a, b)) {
                if (player == null) continue;
                player.sendSystemMessage(Component.literal("管理员强制结束了本场对局。"));
                if (ServerPlayNetworking.canSend(player, MatchResultPayload.TYPE)) {
                    ServerPlayNetworking.send(player, new MatchResultPayload("",
                            false, "管理员强制结束"));
                }
            }
            end(match, a, b, find(admin.server, match.aEntity), find(admin.server, match.bEntity));
            MATCHES.remove(match.id);
        }
        admin.sendSystemMessage(Component.literal("已强制结束 " + targets.size() + " 场对局。"));
    }

    /** Arena removal/invalidation must not leave an active duel behind its deleted room. */
    public static void cancelArena(MinecraftServer running, String arenaName) {
        BotDuelService.cancelArena(running, arenaName);
        for (Match match : new ArrayList<>(MATCHES.values())) {
            if (match.arena == null || !match.arena.name.equalsIgnoreCase(arenaName)) continue;
            ServerPlayer a = running.getPlayerList().getPlayer(match.a);
            ServerPlayer b = running.getPlayerList().getPlayer(match.b);
            if (a != null) a.sendSystemMessage(Component.literal("场地配置已变更，本场对战已结束。"));
            if (b != null) b.sendSystemMessage(Component.literal("场地配置已变更，本场对战已结束。"));
            end(match, a, b, find(running, match.aEntity), find(running, match.bEntity));
            MATCHES.remove(match.id);
        }
    }

    private static void end(Match match, @Nullable ServerPlayer a, @Nullable ServerPlayer b,
                            @Nullable PokemonEntity ea, @Nullable PokemonEntity eb) {
        if (a != null && ea != null) TeamService.rememberHealth(a, ea);
        if (b != null && eb != null) TeamService.rememberHealth(b, eb);
        if (a != null) {
            clearOpponentHud(a);
            ServerGodMode.setActive(a, false);
            CharizardManager.unregisterControlled(match.a);
            TeamService.sendTeamState(a);
        }
        if (b != null) {
            clearOpponentHud(b);
            ServerGodMode.setActive(b, false);
            CharizardManager.unregisterControlled(match.b);
            TeamService.sendTeamState(b);
        }
        if (ea != null) ea.discard();
        if (eb != null) eb.discard();
        match.aDyingEntity = null;
        match.bDyingEntity = null;
        match.aSwitchDeadline = 0;
        match.bSwitchDeadline = 0;
    }

    @Nullable
    private static PokemonEntity find(MinecraftServer running, @Nullable UUID id) {
        if (id == null) return null;
        for (ServerLevel level : running.getAllLevels()) {
            var entity = level.getEntity(id);
            if (entity instanceof PokemonEntity pokemon) return pokemon;
        }
        return null;
    }

    /** 队伍面板确认出战。物种、拥有权、倒下和收回锁全部在服务器验证。 */
    public static void selectPartyPokemon(ServerPlayer player, String rawSpecies) {
        ResourceLocation id = SpeciesRegistry.parse(rawSpecies);
        if (!partyActionAllowed(player) || id == null || !canUseSwitchZone(player, null)) return;
        String species = id.toString();
        PlayerProfile profile = ProfileStore.get(player.server).profile(player.getUUID());
        if (!profile.team.contains(species) || profile.fainted.contains(species)) {
            player.sendSystemMessage(Component.literal("只能派出队伍中尚未倒下的宝可梦。"));
            TeamService.sendTeamState(player);
            return;
        }
        if (TeamService.recallLocked(player, species)) {
            player.sendSystemMessage(Component.literal("这只宝可梦刚被收回，请稍等再出战。"));
            return;
        }
        PokemonEntity current = CharizardManager.getControlled(player);
        if (current != null && !id.equals(current.getPokemon().getSpecies().getResourceIdentifier())) {
            player.sendSystemMessage(Component.literal("请先按 X 收回场上宝可梦，再按 C 选择出战成员。"));
            TeamService.sendTeamState(player);
            return;
        }
        com.pkmoba.pokemon.BallThrow.clearPlayer(player.getUUID());
        TeamService.choose(player, species);
        player.displayClientMessage(Component.literal("已确定出战：" + SpeciesRegistry.displayName(id) + " · 按 X 召唤 / 收回"), true);
        TeamService.sendTeamState(player);
    }

    /** X 的召唤/收回由服务器当前实体状态决定，快速双击也不会重复召唤。 */
    public static void togglePartyPokemon(ServerPlayer player, Vec3 landing) {
        if (!partyActionAllowed(player)) return;
        PokemonEntity current = CharizardManager.getControlled(player);
        if (current != null) {
            recallPartyPokemon(player, current.getPokemon().getSpecies().getResourceIdentifier().toString());
            return;
        }
        if (com.pkmoba.pokemon.BallThrow.hasPending(player.getUUID())) {
            com.pkmoba.pokemon.BallThrow.clearPlayer(player.getUUID());
            TeamService.sendTeamState(player);
            return;
        }
        if (!Double.isFinite(landing.x) || !Double.isFinite(landing.y) || !Double.isFinite(landing.z)
                || !canUseSwitchZone(player, landing)) return;
        RoomManager.Room room = RoomManager.roomOf(player.getUUID());
        ArenaStore.Arena arena = room == null ? null : ArenaStore.get(player.server).byName(room.arenaName);
        Match local = matchOf(player);
        if (arena == null && local != null) arena = local.arena;
        if (inMatch(player) && arena != null && !arena.insideBoundary(landing)) {
            player.displayClientMessage(Component.literal("请在竞技场边界内召唤。"), true);
            return;
        }
        if (landing.distanceToSqr(player.position()) > 96 * 96) return;
        var level = player.serverLevel();
        com.pkmoba.pokemon.BallThrow.throwBall(player, landing, at -> {
            if (player.hasDisconnected() || player.serverLevel() != level || !partyActionAllowed(player)
                    || !canUseSwitchZone(player, at)) return;
            if (CharizardManager.getControlled(player) == null) TeamService.summonAt(player, at);
        });
    }

    /** All battle party entry points use the same own-side polygon. Free play is unrestricted. */
    public static boolean canUseSwitchZone(ServerPlayer player, @Nullable Vec3 landing) {
        if (!inMatch(player)) return true;
        PokemonEntity current = CharizardManager.getControlled(player);
        ArenaStore.Arena arena = current == null ? null : arenaForEntity(current);
        RoomManager.Room room = RoomManager.roomOf(player.getUUID());
        if (arena == null && room != null) arena = ArenaStore.get(player.server).byName(room.arenaName);
        Match match = matchOf(player);
        if (arena == null && match != null) arena = match.arena;
        boolean red = RoomManager.sideOf(player.getUUID()) != 2;
        if (room == null && match != null) red = match.a.equals(player.getUUID());
        if (arena != null && arena.switchPolygon(red).size() != 4) {
            player.displayClientMessage(Component.literal("己方尚未设置完整四点换宠区，请先用换宠区工具设置；场地和房间仍可正常使用。"), true);
            return false;
        }
        Vec3 location = current != null && current.isAlive() ? current.position() : player.position();
        boolean allowed = arena != null && arena.insideSwitchZone(location, red)
                && (landing == null || arena.insideSwitchZone(landing, red));
        if (!allowed) player.displayClientMessage(Component.literal("对战中请进入己方四点换宠区，并在区内召唤或更换宝可梦。"), true);
        return allowed;
    }

    public static boolean canRecallForSwitch(ServerPlayer player, PokemonEntity current) {
        return canUseSwitchZone(player, null);
    }

    /** X 收回当前宝可梦；队伍菜单只负责选择与出战。 */
    public static void recallPartyPokemon(ServerPlayer player, String rawSpecies) {
        if (!partyActionAllowed(player)) return;
        PokemonEntity current = CharizardManager.getControlled(player);
        ResourceLocation requested = SpeciesRegistry.parse(rawSpecies);
        if (current == null || requested == null
                || !requested.equals(current.getPokemon().getSpecies().getResourceIdentifier())) {
            TeamService.sendTeamState(player);
            return;
        }
        com.pkmoba.pokemon.BallThrow.clearPlayer(player.getUUID());
        if (!TeamService.recall(player)) return;
        BotDuelService.notifyRecalled(player);
        Match match = matchOf(player);
        if (match != null) {
            PokemonEntity remaining = CharizardManager.getControlled(player);
            boolean isA = match.a.equals(player.getUUID());
            long deadline = remaining == null && !match.awaitingInitialSummon ? player.server.getTickCount() + SWITCH_TICKS : 0;
            if (isA) {
                match.aEntity = remaining == null ? null : remaining.getUUID();
                match.aSwitchDeadline = deadline;
            } else {
                match.bEntity = remaining == null ? null : remaining.getUUID();
                match.bSwitchDeadline = deadline;
            }
            PokemonEntity opponent = find(player.server, isA ? match.bEntity : match.aEntity);
            if (opponent != null) {
                opponent.setInvulnerable(true);
                com.pkmoba.pokemon.BallThrow.releaseLock(opponent);
            }
            if (remaining == null) player.sendSystemMessage(Component.literal("C 选下一只 · X 召唤，请在 20 秒内完成。"));
        }
        TeamService.sendTeamState(player);
    }

    private static boolean partyActionAllowed(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return false;
        if (!BotDuelService.canSummon(player)) {
            player.sendSystemMessage(Component.literal("训练对战入场倒计时结束后才能召唤。"));
            return false;
        }
        Match duel = matchOf(player);
        if (duel != null && duel.awaitingInitialSummon && player.server.getTickCount() < duel.readyAt) {
            player.sendSystemMessage(Component.literal("对战准备倒计时结束后才能召唤。"));
            return false;
        }
        RoomManager.Room room = RoomManager.roomOf(player.getUUID());
        if (room != null && room.state == RoomManager.State.COUNTDOWN) {
            player.sendSystemMessage(Component.literal("开局倒计时中，请按房间提示选择出战宝可梦。"));
            return false;
        }
        return true;
    }

    /** 兼容旧换宠请求；新客户端使用明确的队伍面板操作。 */
    private static void switchPokemon(ServerPlayer player) {
        if (!partyActionAllowed(player)) return;
        Match match = matchOf(player);
        boolean isA = match != null && match.a.equals(player.getUUID());
        ServerPlayer opponent = match == null ? null : player.server.getPlayerList().getPlayer(isA ? match.b : match.a);
        if (CharizardManager.getControlled(player) != null) {
            player.sendSystemMessage(Component.literal("请先按 X 收回，再选择下一只宝可梦。"));
            return;
        }
        PlayerProfile profile = ProfileStore.get(player.server).profile(player.getUUID());
        if (!profile.hasUsableLeft()) {
            player.sendSystemMessage(Component.literal("你已经没有能上场的宝可梦了。"));
            return;
        }
        // 没有在场实体：C 只负责"选好下一只"，真正放出来由 X 键在准星位置完成
        String next = TeamService.advanceSelection(player);
        if (next == null) {
            player.sendSystemMessage(Component.literal(profile.hasUsableLeft()
                    ? "队伍里没有其它可切换的宝可梦了，按 X 键召唤 "
                      + SpeciesRegistry.displayName(ResourceLocation.tryParse(profile.activeSpecies())) + "。"
                    : "你还有可用宝可梦没放出来，按 X 键召唤。"));
            return;
        }
        TeamService.sendTeamState(player);
        if (opponent != null) TeamService.sendTeamState(opponent);
        player.sendSystemMessage(Component.literal("已切换到 "
                + SpeciesRegistry.displayName(ResourceLocation.tryParse(next))
                + "（" + (profile.activeIndex + 1) + "/" + profile.team.size()
                + "），按 X 键召唤，倒计时结束前不放出来判负。"));
    }

    /**
     * 战斗中主动收回后，场上会短暂没有任何宝可梦：此时不能让 tickSide 误判成"阵亡"，
     * 所以主动收回必须同时清掉 aEntity/bEntity（上面已做），由倒计时分支处理超时。
     */
    private static boolean isAwaitingSwitch(Match match, boolean isA) {
        UUID entityId = isA ? match.aEntity : match.bEntity;
        long deadline = isA ? match.aSwitchDeadline : match.bSwitchDeadline;
        return entityId == null && deadline > 0;
    }

    /** 当前玩家还需要多久换人（tick），0 表示不需要换。 */
    public static int pendingTicksFor(@Nullable ServerPlayer player) {
        if (player == null) return 0;
        if (BotDuelService.inMatch(player)) return BotDuelService.pendingTicksFor(player);
        Match match = matchOf(player);
        if (match == null) return 0;
        long deadline = match.a.equals(player.getUUID()) ? match.aSwitchDeadline : match.bSwitchDeadline;
        if (deadline <= 0 || server == null) return 0;
        return (int) Math.max(0, deadline - server.getTickCount());
    }

    /** 玩家在对局中用 X 键召唤了宝可梦：登记到本局并清掉换人倒计时。 */
    public static void notifySummoned(ServerPlayer player, PokemonEntity entity) {
        BotDuelService.notifySummoned(player, entity);
        Match match = matchOf(player);
        if (match == null) return;
        if (match.a.equals(player.getUUID())) {
            match.aEntity = entity.getUUID();
            match.aSwitchDeadline = 0;
        } else if (match.b.equals(player.getUUID())) {
            match.bEntity = entity.getUUID();
            match.bSwitchDeadline = 0;
        }
        if (match.aSwitchDeadline == 0 && match.bSwitchDeadline == 0
                && (!match.awaitingInitialSummon || player.server.getTickCount() >= match.readyAt)) {
            PokemonEntity a = find(player.server, match.aEntity);
            PokemonEntity b = find(player.server, match.bEntity);
            if (a != null) { a.setInvulnerable(false); com.pkmoba.pokemon.BallThrow.releaseLock(a); }
            if (b != null) { b.setInvulnerable(false); com.pkmoba.pokemon.BallThrow.releaseLock(b); }
        }
        if (match.awaitingInitialSummon && player.server.getTickCount() >= match.readyAt) {
            match.awaitingInitialSummon = false;
            PokemonEntity a = find(player.server, match.aEntity);
            PokemonEntity b = find(player.server, match.bEntity);
            if (a != null) { a.setInvulnerable(false); com.pkmoba.pokemon.BallThrow.releaseLock(a); }
            if (b != null) { b.setInvulnerable(false); com.pkmoba.pokemon.BallThrow.releaseLock(b); }
            showTitle(player, "开始召唤", "C 选择出战 · X 召唤");
            ServerPlayer opponentPlayer = player.server.getPlayerList().getPlayer(
                    match.a.equals(player.getUUID()) ? match.b : match.a);
            if (opponentPlayer != null) showTitle(opponentPlayer, "开始召唤", "C 选择出战 · X 召唤");
        }
    }

    private static Vec3 spawnPoint(Match match, boolean isA) {
        ArenaStore.Arena arena = match.arena;
        if (arena != null && arena.complete()) {
            Vec3 point = isA ? arena.redPlayer : arena.bluePlayer;
            if (point != null) return point;
        }
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(isA ? match.a : match.b);
        return player == null ? Vec3.ZERO : player.position().add(2, 0, 0);
    }

    /**
     * 发出一份 1v1 挑战。
     *
     * @param challengeUi true 表示使用界面的短时弹窗挑战，false 表示普通挑战
     */
    public static boolean challenge(ServerPlayer from, ServerPlayer to, @Nullable String arenaName) {
        return challenge(from, to, arenaName, false);
    }

    public static boolean challenge(ServerPlayer from, ServerPlayer to, @Nullable String arenaName,
                                   boolean challengeUi) {
        if (from == to) {
            from.sendSystemMessage(Component.literal("不能挑战自己。"));
            return false;
        }
        if (busy(from)) {
            from.sendSystemMessage(Component.literal("你已经在另一场对局里了。"));
            return false;
        }
        if (busy(to)) {
            from.sendSystemMessage(Component.literal(to.getGameProfile().getName() + " 正在对局中，无法接受挑战。"));
            return false;
        }
        Invite existing = INVITES.get(to.getUUID());
        if (existing != null) {
            from.sendSystemMessage(Component.literal(to.getGameProfile().getName()
                    + " 已经有一条待处理的挑战，等它结束再发起。"));
            return false;
        }
        ArenaStore.Arena arena = null;
        if (server != null) {
            ArenaStore store = ArenaStore.get(from.server);
            arena = arenaName == null || arenaName.isBlank() ? null : store.byName(arenaName);
            if (arenaName != null && !arenaName.isBlank() && arena == null) {
                from.sendSystemMessage(Component.literal("找不到场地：" + arenaName
                        + "，可用：/mo arena list"));
                return false;
            }
        }
        long now = from.server.getTickCount();
        int ticks = challengeUi ? CHALLENGE_TICKS : INVITE_TICKS;
        Invite invite = new Invite(UUID.randomUUID(), from.getUUID(), now + ticks, arena, challengeUi);
        invite.lastSecondsSent = (ticks + 19) / 20;
        INVITES.put(to.getUUID(), invite);

        String label = arena == null ? "1v1" : "1v1（场地：" + arena.name + "）";
        if (challengeUi) {
            sendPopup(to, invite);
            from.sendSystemMessage(Component.literal("已向 " + to.getGameProfile().getName()
                    + " 发送 " + label + " 挑战，等待对方回应（10 秒）。"));
        } else {
            to.sendSystemMessage(Component.literal(from.getGameProfile().getName() + " 邀请你进行 " + label
                    + "，在收到的挑战提示中选择接受。"));
            from.sendSystemMessage(Component.literal("已向 " + to.getGameProfile().getName()
                    + " 发送 " + label + " 邀请，等待对方在挑战提示中接受。"));
        }
        return true;
    }

    /** 推进一次邀请：到点失效，否则按秒推送倒计时。@return true 表示应从表中移除 */
    private static boolean tickInvite(MinecraftServer running, UUID targetId, Invite invite) {
        long now = running.getTickCount();
        ServerPlayer target = running.getPlayerList().getPlayer(targetId);
        if (target == null) {
            // 被挑战者离线：通知挑战者并清掉，避免留下无法结束的请求
            notifyChallenger(running, invite, "对方已经离线，挑战已取消。");
            return true;
        }
        if (now >= invite.expiresAt) {
            notifyChallenger(running, invite, "对方未及时回应，挑战已过期。");
            closePopup(target);
            return true;
        }
        int seconds = (int) Math.max(1L, (invite.expiresAt - now + 19) / 20);
        if (seconds != invite.lastSecondsSent) {
            invite.lastSecondsSent = seconds;
            sendPopup(target, invite);
        }
        return false;
    }

    private static void notifyChallenger(MinecraftServer running, Invite invite, String text) {
        ServerPlayer from = running.getPlayerList().getPlayer(invite.from);
        if (from != null) from.sendSystemMessage(Component.literal(text));
    }

    private static void sendPopup(ServerPlayer target, Invite invite) {
        if (!ServerPlayNetworking.canSend(target, com.pkmoba.network.ChallengePopupPayload.TYPE)) return;
        ServerPlayer from = server == null ? null : server.getPlayerList().getPlayer(invite.from);
        String name = from == null ? "?" : from.getGameProfile().getName();
        ServerPlayNetworking.send(target, new com.pkmoba.network.ChallengePopupPayload(
                true, invite.id, name, Math.max(1, invite.lastSecondsSent)));
    }

    private static void closePopup(ServerPlayer target) {
        if (target == null) return;
        if (!ServerPlayNetworking.canSend(target, com.pkmoba.network.ChallengePopupPayload.TYPE)) return;
        ServerPlayNetworking.send(target, new com.pkmoba.network.ChallengePopupPayload(
                false, new UUID(0L, 0L), "", 0));
    }

    /** 客户端弹窗按钮：服务端逐项复核后再处理，且同一请求只能成功一次。 */
    public static void handleChallengeAction(ServerPlayer to, com.pkmoba.network.ChallengeActionPayload payload) {
        UUID requestId = payload.requestId();
        if (requestId == null) return;
        Invite invite = INVITES.get(to.getUUID());
        if (invite == null || !invite.id.equals(requestId)) {
            // 旧请求 / 已处理 / 已过期：明确失败反馈，弹窗也该关掉
            to.sendSystemMessage(Component.literal("这条挑战已经失效（已过期或已处理）。"));
            closePopup(to);
            return;
        }
        if (server != null && server.getTickCount() >= invite.expiresAt) {
            INVITES.remove(to.getUUID());
            to.sendSystemMessage(Component.literal("挑战已过期，无法接受。"));
            closePopup(to);
            notifyChallenger(to.server, invite, "对方未及时回应，挑战已过期。");
            return;
        }
        ServerPlayer from = to.server.getPlayerList().getPlayer(invite.from);
        if (from == null) {
            INVITES.remove(to.getUUID());
            to.sendSystemMessage(Component.literal("挑战者已经离线，挑战已失效。"));
            closePopup(to);
            return;
        }
        if (payload.accept()) {
            INVITES.remove(to.getUUID());
            closePopup(to);
            if (busy(to) || busy(from)) {
                to.sendSystemMessage(Component.literal("无法开始：你或对方已在其他对局中。"));
                from.sendSystemMessage(Component.literal("对方接受失败：有一方已在对局中。"));
                return;
            }
            boolean started = accept(to, from, invite.arena);
            if (started) {
                from.sendSystemMessage(Component.literal(to.getGameProfile().getName() + " 接受了你的挑战。"));
            } else {
                to.sendSystemMessage(Component.literal("对局启动失败：请检查双方的宝可梦队伍。"));
                from.sendSystemMessage(Component.literal("对局启动失败：对方或你的宝可梦队伍不完整。"));
            }
            return;
        }
        // 拒绝
        INVITES.remove(to.getUUID());
        closePopup(to);
        to.sendSystemMessage(Component.literal("你拒绝了 " + from.getGameProfile().getName() + " 的挑战。"));
        from.sendSystemMessage(Component.literal(to.getGameProfile().getName() + " 拒绝了你的挑战。"));
    }

    public static boolean consent(ServerPlayer to) {
        Invite invite = INVITES.get(to.getUUID());
        if (invite == null) {
            to.sendSystemMessage(Component.literal("你当前没有待接受的 1v1 邀请。"));
            return false;
        }
        ServerPlayer from = to.server.getPlayerList().getPlayer(invite.from);
        if (from == null) {
            INVITES.remove(to.getUUID());
            to.sendSystemMessage(Component.literal("邀请你的玩家已经离线，邀请已失效。"));
            return false;
        }
        INVITES.remove(to.getUUID());
        return accept(to, from, invite.arena);
    }

    private static boolean accept(ServerPlayer to, ServerPlayer from, @Nullable ArenaStore.Arena arena) {
        if (busy(to) || busy(from) || ProfileStore.get(from.server).profile(from.getUUID()).team.isEmpty()
                || ProfileStore.get(to.server).profile(to.getUUID()).team.isEmpty()) return false;
        Match match = new Match(UUID.randomUUID(), from.getUUID(), to.getUUID(), arena);
        if (!spawnParticipants(from, to, match)) return false;
        TeamService.healPartyForBattle(from);
        TeamService.healPartyForBattle(to);
        match.awaitingInitialSummon = true;
        match.readyAt = from.server.getTickCount() + 10 * 20;
        MATCHES.put(match.id, match);
        showTitle(from, "10", "已传送入场 · 开始倒计时");
        showTitle(to, "10", "已传送入场 · 开始倒计时");
        announce(match, "1v1 已开始：" + from.getGameProfile().getName() + " 对战 "
                + to.getGameProfile().getName()
                + "，各用自己选择的宝可梦，一方宝可梦全部倒下即判负。");
        return true;
    }

    /**
     * 房间系统开局入口：不检查权限，只检查双方可战斗状态，然后走和 1v1 完全一样的对局逻辑。
     * 房间负责成员/权限/生命周期，这里只负责"把一场对局真的开起来"。
     */
    public static boolean startRoom(ServerPlayer red, ServerPlayer blue, @Nullable ArenaStore.Arena arena) {
        if (red.getUUID().equals(blue.getUUID())) return false;
        if (busy(red) || busy(blue) || ProfileStore.get(red.server).profile(red.getUUID()).team.isEmpty()
                || ProfileStore.get(blue.server).profile(blue.getUUID()).team.isEmpty()) return false;
        Match match = new Match(UUID.randomUUID(), red.getUUID(), blue.getUUID(), arena);
        if (!spawnParticipants(red, blue, match)) return false;
        TeamService.healPartyForBattle(red);
        TeamService.healPartyForBattle(blue);
        lockSpawnedPlayers(red, blue);
        MATCHES.put(match.id, match);
        showTitle(red, "选择出战", "按 C 确认成员 · 按 X 自行召唤 · 双方就绪后开战");
        showTitle(blue, "选择出战", "按 C 确认成员 · 按 X 自行召唤 · 双方就绪后开战");
        announce(match, "房间对战开始：" + red.getGameProfile().getName() + "（红） 对战 "
                + blue.getGameProfile().getName() + "（蓝）" + (arena == null ? "" : "，场地：" + arena.name)
                + "，一方宝可梦全部倒下即判负。");
        return true;
    }

    public static boolean adminStart(ServerPlayer admin, ServerPlayer a, ServerPlayer b) {
        if (!admin.hasPermissions(2)) return false;
        // 管理员可以把自己选为任一方，但不能把同一个人选两次。
        if (a.getUUID().equals(b.getUUID())) {
            admin.sendSystemMessage(Component.literal("不能把同一个玩家设为双方。"));
            return false;
        }
        if (busy(a) || busy(b)) return false;
        Match match = new Match(UUID.randomUUID(), a.getUUID(), b.getUUID(), null);
        if (!spawnParticipants(a, b, match)) return false;
        TeamService.healPartyForBattle(a);
        TeamService.healPartyForBattle(b);
        MATCHES.put(match.id, match);
        announce(match, "管理员已开始单挑：" + a.getGameProfile().getName() + " 对战 "
                + b.getGameProfile().getName() + "，一方宝可梦全部倒下即判负。");
        return true;
    }

    /** 开局/投球落地后统一给双方在场宝可梦上登场硬直（方案 §三.4）。 */
    private static void lockSpawnedPlayers(ServerPlayer... players) {
        for (ServerPlayer player : players) {
            var entity = CharizardManager.getControlled(player);
            if (entity != null) {
                com.pkmoba.pokemon.BallThrow.releaseLock(entity);
                com.pkmoba.pokemon.BallThrow.applyLock(entity, com.pkmoba.pokemon.BallThrow.SPAWN_LOCK_TICKS);
            }
        }
    }

    private static boolean spawnParticipants(ServerPlayer a, ServerPlayer b, Match match) {
        if (ProfileStore.get(a.server).profile(a.getUUID()).team.isEmpty()
                || ProfileStore.get(b.server).profile(b.getUUID()).team.isEmpty()) return false;

        // 清理旧视角锚点，传送后从竞技场玩家点重新锁定。
        ServerGodMode.forceOff(a);
        ServerGodMode.forceOff(b);
        // 有场地：先把双方传送到玩家点，由玩家按 X 放出宝可梦。
        if (match.arena != null && match.arena.complete()) {
            ServerLevel level = resolveLevel(a.server, match.arena.dimension, a);
            if (level == null) {
                a.sendSystemMessage(Component.literal("场地所在维度不存在，无法开始。"));
                return false;
            }
            teleport(a, level, match.arena.redPlayer, match.arena.bluePlayer);
            teleport(b, level, match.arena.bluePlayer, match.arena.redPlayer);
        }

        com.pkmoba.pokemon.BallThrow.clearPlayer(a.getUUID());
        com.pkmoba.pokemon.BallThrow.clearPlayer(b.getUUID());
        CharizardManager.discardAllControlled(a);
        CharizardManager.discardAllControlled(b);
        match.awaitingInitialSummon = true;
        match.readyAt = a.server.getTickCount() + 10 * 20;
        showTitle(a, "10", "已传送入场 · 开始倒计时");
        showTitle(b, "10", "已传送入场 · 开始倒计时");
        a.sendSystemMessage(Component.literal("已传送入场。10 秒后按 C 选择出战，再按 X 自行召唤。"));
        b.sendSystemMessage(Component.literal("已传送入场。10 秒后按 C 选择出战，再按 X 自行召唤。"));
        // 场地无限大：清掉训练用的圆形限制，避免 clamp 把宝可梦/技能拉回
        com.pkmoba.combat.CharizardCombat.clearArena();
        ServerGodMode.setActive(a, true);
        ServerGodMode.setActive(b, true);
        TeamService.sendTeamState(a);
        TeamService.sendTeamState(b);
        return true;
    }

    public static boolean hasTeam(ServerPlayer player) {
        PlayerProfile profile = ProfileStore.get(player.server).profile(player.getUUID());
        if (profile.activeSpecies() == null) {
            player.sendSystemMessage(Component.literal("你还没有选择宝可梦，请先按「宝可梦选择器」选择。"));
            return false;
        }
        if (!profile.hasUsableLeft()) {
            player.sendSystemMessage(Component.literal("你的宝可梦全部倒下了，请先用 /mo revive 或治疗机复活。"));
            return false;
        }
        return true;
    }

    @Nullable
    private static ServerLevel resolveLevel(MinecraftServer server, @Nullable ResourceLocation dimension, ServerPlayer fallback) {
        if (dimension == null) return fallback.serverLevel();
        return server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, dimension));
    }

    private static void teleport(ServerPlayer player, ServerLevel level, @Nullable Vec3 pos, Vec3 opposite) {
        if (pos == null) return;
        float yaw = opposite == null ? player.getYRot()
                : (float) Math.toDegrees(Math.atan2(-(opposite.x - pos.x), opposite.z - pos.z));
        player.teleportTo(level, pos.x, pos.y, pos.z, yaw, 0);
    }

    private static void announce(Match match, String text) {
        for (ServerPlayer player : participants(match)) {
            player.sendSystemMessage(Component.literal(text));
        }
    }

    private static List<ServerPlayer> participants(Match match) {
        List<ServerPlayer> list = new ArrayList<>(2);
        if (server == null) return list;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(match.a) || player.getUUID().equals(match.b)) list.add(player);
        }
        return list;
    }

    private static boolean busy(ServerPlayer player) {
        return matchOf(player) != null;
    }

    @Nullable
    private static Match matchOf(ServerPlayer player) {
        for (Match match : MATCHES.values()) {
            if (match.a.equals(player.getUUID()) || match.b.equals(player.getUUID())) return match;
        }
        return null;
    }

    public static boolean inMatch(ServerPlayer player) {
        return busy(player) || BotDuelService.inMatch(player);
    }

    /** Shared server gate for movement, skill casts and damage until both sides summon. */
    public static boolean isPreparing(net.minecraft.world.entity.Entity entity) {
        if (entity == null) return false;
        if (BotDuelService.isPreparing(entity)) return true;
        for (Match match : MATCHES.values()) {
            boolean participant = entity.getUUID().equals(match.a) || entity.getUUID().equals(match.b)
                    || entity.getUUID().equals(match.aEntity) || entity.getUUID().equals(match.bEntity);
            long now = entity.level().getServer() == null ? 0 : entity.level().getServer().getTickCount();
            if (participant && (match.awaitingInitialSummon || match.aSwitchDeadline > now || match.bSwitchDeadline > now)) return true;
        }
        return false;
    }

    /**
     * 找到某个宝可梦实体所属对局的点位组（房间边界判定用）。
     * 不在对局里返回 null，调用方按"无限制"处理。
     */
    @Nullable
    public static ArenaStore.Arena arenaForEntity(net.minecraft.world.entity.Entity entity) {
        if (entity == null) return null;
        ArenaStore.Arena botArena = BotDuelService.arenaForEntity(entity);
        if (botArena != null) return botArena;
        for (Match match : MATCHES.values()) {
            if (match.arena == null) continue;
            if (entity.getUUID().equals(match.aEntity) || entity.getUUID().equals(match.bEntity)) {
                return match.arena;
            }
        }
        // m05326：房间对战（RoomManager）也用同一套边界，否则开房后宝可梦能跑出去。
        RoomManager.Room room = RoomManager.roomOfEntity(entity);
        if (room != null && server != null) {
            ArenaStore.Arena roomArena = ArenaStore.get(server).byName(room.arenaName);
            if (roomArena != null) {
                return roomArena;
            }
        }
        return null;
    }

    /** 一条待处理的挑战；{@code lastSecondsSent} 用于按秒推送倒计时，避免每 tick 发包。 */
    private static final class Invite {
        final UUID id;
        final UUID from;
        final long expiresAt;
        @Nullable
        final ArenaStore.Arena arena;
        final boolean challengeUi;
        int lastSecondsSent;

        Invite(UUID id, UUID from, long expiresAt, @Nullable ArenaStore.Arena arena, boolean challengeUi) {
            this.id = id;
            this.from = from;
            this.expiresAt = expiresAt;
            this.arena = arena;
            this.challengeUi = challengeUi;
        }
    }

    public static final class Match {
        public final UUID id;
        public final UUID a;
        public final UUID b;
        public final ArenaStore.Arena arena;
        public UUID aEntity;
        public UUID bEntity;
        public UUID aDyingEntity;
        public UUID bDyingEntity;
        public long aDyingUntil;
        public long bDyingUntil;
        /** 换人截止 tick，0 表示当前不需要换人。 */
        public long aSwitchDeadline;
        public long bSwitchDeadline;
        /** 排定的结束 tick（0 表示未排定）与胜方。 */
        public long finishAt;
        public boolean finishWinnerIsA;
        public String finishReason = "队伍全部倒下";
        public boolean awaitingInitialSummon;
        public long readyAt;

        public Match(UUID id, UUID a, UUID b, @Nullable ArenaStore.Arena arena) {
            this.id = id;
            this.a = a;
            this.b = b;
            this.arena = arena;
        }
    }
}
