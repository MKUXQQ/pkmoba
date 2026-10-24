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
        ServerPlayNetworking.registerGlobalReceiver(SwitchPokemonPayload.TYPE, (payload, context) ->
                context.server().execute(() -> switchPokemon(context.player())));
    }

    private static void tick(MinecraftServer running) {
        server = running;
        long now = running.getTickCount();
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

        // 战败动画播完再真正移除实体
        match.aDyingEntity = tickDying(running, match.aDyingEntity, match.aDyingUntil, now);
        match.bDyingEntity = tickDying(running, match.bDyingEntity, match.bDyingUntil, now);

        if (tickSide(running, match, a, b, true, now)) return true;
        if (tickSide(running, match, b, a, false, now)) return true;
        return false;
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
                    self.sendSystemMessage(Component.literal(
                            "你的宝可梦倒下了！按 C 放出下一只，剩余 " + usable(profile)
                                    + " 只，20 秒内不换判负。"));
                    opponent.sendSystemMessage(Component.literal(
                            self.getGameProfile().getName() + " 的宝可梦倒下了。"));
                }
                return false;
            }
        } else if (deadline > 0) {
            // 等待换人：超时判负
            if (now >= deadline) {
                boolean aWins = !isA;
                scheduleFinish(match, now, aWins);
                match.finishAt = now;
                match.finishWinnerIsA = aWins;
                return false;
            }
        }

        // 已经排定结束时间：到点收尾
        if (match.finishAt > 0 && now >= match.finishAt) {
            return finish(running, match, self, opponent, match.finishWinnerIsA);
        }
        return false;
    }

    private static void scheduleFinish(Match match, long at, boolean aWins) {
        if (match.finishAt > 0) return;
        match.finishAt = at;
        match.finishWinnerIsA = aWins;
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
                    "对战结束：" + winner + " 获胜（" + loser + " 的宝可梦全部倒下）。"));
            if (ServerPlayNetworking.canSend(player, MatchResultPayload.TYPE)) {
                ServerPlayNetworking.send(player, new MatchResultPayload(winner,
                        player.getUUID().equals(winnerPlayer.getUUID()), "队伍打光"));
            }
        }
        end(match, a, b, find(running, match.aEntity), find(running, match.bEntity));
        return true;
    }

    /** 管理员强制结束：/mo duel stop <场地|all> */
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

    private static void end(Match match, @Nullable ServerPlayer a, @Nullable ServerPlayer b,
                            @Nullable PokemonEntity ea, @Nullable PokemonEntity eb) {
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

    /** 对局中按下 C：有宝可梦在场就收回，没有就放出下一只可用的。 */
    private static void switchPokemon(ServerPlayer player) {
        Match match = matchOf(player);
        if (match == null) {
            // 不在对局中也允许用 C 收回/放出，方便平时练习
            TeamService.cycle(player, player.position().add(2, 0, 0));
            return;
        }
        boolean isA = match.a.equals(player.getUUID());
        ServerPlayer opponent = player.server.getPlayerList().getPlayer(isA ? match.b : match.a);
        PokemonEntity current = CharizardManager.getControlled(player);
        if (current != null) {
            // 主动收回：场上清空并进入换人倒计时，玩家再按一次 C 放出下一只。
            TeamService.recall(player);
            if (isA) {
                match.aEntity = null;
                match.aSwitchDeadline = player.server.getTickCount() + SWITCH_TICKS;
            } else {
                match.bEntity = null;
                match.bSwitchDeadline = player.server.getTickCount() + SWITCH_TICKS;
            }
            PlayerProfile profile = ProfileStore.get(player.server).profile(player.getUUID());
            if (profile.hasUsableLeft()) {
                player.sendSystemMessage(Component.literal(
                        "已收回，按 C 选下一只、再按 X 召唤出来；剩余 " + usable(profile) + " 只，20 秒内不换判负。"));
            }
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
        Match match = matchOf(player);
        if (match == null) return 0;
        long deadline = match.a.equals(player.getUUID()) ? match.aSwitchDeadline : match.bSwitchDeadline;
        if (deadline <= 0 || server == null) return 0;
        return (int) Math.max(0, deadline - server.getTickCount());
    }

    /** 玩家在对局中用 X 键召唤了宝可梦：登记到本局并清掉换人倒计时。 */
    public static void notifySummoned(ServerPlayer player, PokemonEntity entity) {
        Match match = matchOf(player);
        if (match == null) return;
        if (match.a.equals(player.getUUID())) {
            match.aEntity = entity.getUUID();
            match.aSwitchDeadline = 0;
        } else if (match.b.equals(player.getUUID())) {
            match.bEntity = entity.getUUID();
            match.bSwitchDeadline = 0;
        }
    }

    private static Vec3 spawnPoint(Match match, boolean isA) {
        ArenaStore.Arena arena = match.arena;
        if (arena != null && arena.complete()) {
            Vec3 point = isA ? arena.redPokemon : arena.bluePokemon;
            if (point != null) return point;
        }
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(isA ? match.a : match.b);
        return player == null ? Vec3.ZERO : player.position().add(2, 0, 0);
    }

    /**
     * 发出一份 1v1 挑战。
     *
     * @param challengeUi true 表示来自道具界面（10 秒弹窗），false 表示 /mo duel 命令（30 秒）
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
                    + "，输入 /mo duel consent 接受。"));
            from.sendSystemMessage(Component.literal("已向 " + to.getGameProfile().getName()
                    + " 发送 " + label + " 邀请，等待对方输入 /mo duel consent 接受。"));
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
        if (busy(to) || busy(from)) return false;
        Match match = new Match(UUID.randomUUID(), from.getUUID(), to.getUUID(), arena);
        if (!spawnParticipants(from, to, match)) return false;
        MATCHES.put(match.id, match);
        announce(match, "1v1 已开始：" + from.getGameProfile().getName() + " 对战 "
                + to.getGameProfile().getName()
                + "，各用自己选择的宝可梦，一方宝可梦全部倒下即判负。");
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
        MATCHES.put(match.id, match);
        announce(match, "管理员已开始单挑：" + a.getGameProfile().getName() + " 对战 "
                + b.getGameProfile().getName() + "，一方宝可梦全部倒下即判负。");
        return true;
    }

    private static boolean spawnParticipants(ServerPlayer a, ServerPlayer b, Match match) {
        if (!hasTeam(a) || !hasTeam(b)) return false;

        // 有场地：先把双方传送到玩家点，再到宝可梦点放宝可梦
        if (match.arena != null && match.arena.complete()) {
            ServerLevel level = resolveLevel(a.server, match.arena.dimension, a);
            if (level == null) {
                a.sendSystemMessage(Component.literal("场地所在维度不存在，无法开始。"));
                return false;
            }
            teleport(a, level, match.arena.redPlayer);
            teleport(b, level, match.arena.bluePlayer);
        }

        Vec3 aPos = match.arena != null && match.arena.complete() ? match.arena.redPokemon : a.position().add(3, 0, 0);
        Vec3 bPos = match.arena != null && match.arena.complete() ? match.arena.bluePokemon : b.position().add(-3, 0, 0);

        PokemonEntity ea = TeamService.spawnActive(a, aPos);
        if (ea == null) {
            a.sendSystemMessage(Component.literal("对战开始失败：你的宝可梦无法生成。"));
            return false;
        }
        PokemonEntity eb = TeamService.spawnActive(b, bPos);
        if (eb == null) {
            CharizardManager.unregisterControlled(a.getUUID());
            ea.discard();
            a.sendSystemMessage(Component.literal("对战开始失败：对手的宝可梦无法生成。"));
            return false;
        }
        match.aEntity = ea.getUUID();
        match.bEntity = eb.getUUID();
        // 场地无限大：清掉训练用的圆形限制，避免 clamp 把宝可梦/技能拉回
        com.pkmoba.combat.CharizardCombat.clearArena();
        ServerGodMode.setActive(a, true);
        ServerGodMode.setActive(b, true);
        TeamService.sendTeamState(a);
        TeamService.sendTeamState(b);
        return true;
    }

    private static boolean hasTeam(ServerPlayer player) {
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

    private static void teleport(ServerPlayer player, ServerLevel level, @Nullable Vec3 pos) {
        if (pos == null) return;
        player.teleportTo(level, pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
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
        return busy(player);
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

        public Match(UUID id, UUID a, UUID b, @Nullable ArenaStore.Arena arena) {
            this.id = id;
            this.a = a;
            this.b = b;
            this.arena = arena;
        }
    }
}
