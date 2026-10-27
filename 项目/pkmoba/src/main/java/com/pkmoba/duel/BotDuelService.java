package com.pkmoba.duel;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.authlib.GameProfile;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.combat.CharizardCombat;
import com.pkmoba.combat.Skill;
import com.pkmoba.godmode.ServerGodMode;
import com.pkmoba.network.DuelStatePayload;
import com.pkmoba.network.MatchResultPayload;
import com.pkmoba.pokemon.ProfileStore;
import com.pkmoba.pokemon.TeamService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Room-only training opponents. Bot identities never enter the real player list or profiles. */
public final class BotDuelService {
    public static final String BOT_NAME = "训练人机";
    private static final List<String> TEAM = List.of("charizard", "blastoise", "venusaur", "pikachu");
    private static final Map<UUID, Match> MATCHES = new LinkedHashMap<>();
    private static final int SWITCH_TICKS = 20 * 20;
    private static final int BOT_REPLACEMENT_TICKS = 2 * 20;
    private static final int ENTRY_COUNTDOWN_TICKS = 10 * 20;

    private static final class Match {
        final UUID human;
        final UUID bot;
        final ArenaStore.Arena arena;
        final boolean humanRed;
        final ServerPlayer actor;
        final long entryStartsAt;
        UUID humanEntity;
        UUID botEntity;
        int botIndex;
        long humanDeadline;
        long botDeadline;
        long humanDyingUntil;
        UUID humanDying;
        long botDyingUntil;
        UUID botDying;
        long battleStartsAt;
        int nextSkill;
        int lastSpecies = -1;
        Skill lastSkill;
        Skill chargedIntent;
        long chargedIntentUntil;
        Skill nextCharged = Skill.W;
        long nextIntentLog;
        final long[] lastCastAt = new long[4];
        boolean initialSummonPending = true;
        long protectedUntil;
        Match(ServerPlayer player, UUID bot, ArenaStore.Arena arena, boolean humanRed, ServerLevel level) {
            this.human = player.getUUID();
            this.bot = bot;
            this.arena = arena;
            this.humanRed = humanRed;
            this.actor = new ServerPlayer(player.server, level, new GameProfile(bot, "PkmobaBot"), ClientInformation.createDefault());
            this.actor.setPos(botPoint());
            this.entryStartsAt = player.server.getTickCount() + ENTRY_COUNTDOWN_TICKS;
            this.protectedUntil = entryStartsAt;
        }
        Vec3 humanPoint() { return humanRed ? arena.redPlayer : arena.bluePlayer; }
        Vec3 humanPokemonPoint() { return humanRed ? arena.redPlayer : arena.bluePlayer; }
        Vec3 botPoint() { return humanRed ? arena.bluePlayer : arena.redPlayer; }
    }

    public static List<String> teamForDisplay() {
        return TEAM.stream().map(id -> "cobblemon:" + id).toList();
    }

    private BotDuelService() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(BotDuelService::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Match match : new ArrayList<>(MATCHES.values())) finish(server, match, null, "服务器关闭");
            MATCHES.clear();
        });
    }

    @Nullable public static ServerPlayer actor(UUID owner) {
        for (Match match : MATCHES.values()) if (match.bot.equals(owner)) return match.actor;
        return null;
    }

    public static boolean isBot(PokemonEntity entity) {
        return entity != null && actor(CharizardManager.getControlledOwner(entity)) != null;
    }

    public static boolean inMatch(ServerPlayer player) { return player != null && MATCHES.containsKey(player.getUUID()); }

    public static boolean awaitingInitialSummon(ServerPlayer player) {
        Match match = player == null ? null : MATCHES.get(player.getUUID());
        return match != null && match.initialSummonPending;
    }

    public static boolean canSummon(ServerPlayer player) {
        Match match = player == null ? null : MATCHES.get(player.getUUID());
        return match == null || !match.initialSummonPending || player.server.getTickCount() >= match.entryStartsAt;
    }

    public static boolean isPreparing(Entity entity) {
        Match match = matchFor(entity);
        if (match == null) return false;
        if (match.initialSummonPending) return true;
        long now = entity.level().getServer() == null ? 0 : entity.level().getServer().getTickCount();
        return match.entryStartsAt > now || match.battleStartsAt > now || match.protectedUntil > now
                || match.humanDeadline > now || match.botDeadline > now;
    }

    @Nullable public static ArenaStore.Arena arenaForEntity(Entity entity) {
        Match match = matchFor(entity);
        return match == null ? null : match.arena;
    }

    public static boolean areOpponents(Entity a, Entity b) {
        if (!(a instanceof PokemonEntity pa) || !(b instanceof PokemonEntity pb)) return false;
        Match match = matchFor(a);
        if (match == null || match != matchFor(b)) return false;
        UUID oa = CharizardManager.getControlledOwner(pa);
        UUID ob = CharizardManager.getControlledOwner(pb);
        return (match.human.equals(oa) && match.bot.equals(ob)) || (match.bot.equals(oa) && match.human.equals(ob));
    }

    @Nullable private static Match matchFor(Entity entity) {
        if (entity == null) return null;
        UUID owner = entity instanceof PokemonEntity pokemon ? CharizardManager.getControlledOwner(pokemon) : entity.getUUID();
        for (Match match : MATCHES.values()) {
            if (match.human.equals(owner) || match.bot.equals(owner)) return match;
        }
        return null;
    }

    public static boolean start(ServerPlayer human, UUID bot, ArenaStore.Arena arena, boolean humanRed) {
        if (bot == null || inMatch(human) || DuelManager.inMatch(human) || arena == null || !arena.complete()) return false;
        var roster = ProfileStore.get(human.server).profile(human.getUUID());
        if (roster.team.isEmpty() || roster.team.stream().allMatch(roster.fainted::contains)) {
            human.sendSystemMessage(Component.literal("你的队伍没有可出战宝可梦，请先复活。"));
            return false;
        }
        ResourceLocation dimension = arena.dimension;
        ServerLevel level = dimension == null ? human.serverLevel() : human.server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension));
        if (level == null) return false;
        // Fresh matches start with the full roster restored, regardless of free-play HP history.
        TeamService.healPartyForBattle(human);
        com.pkmoba.pokemon.BallThrow.clearPlayer(human.getUUID());
        for (PokemonEntity existing : new ArrayList<>(CharizardManager.getActiveList(human))) {
            CharizardCombat.reset(existing);
        }
        CharizardManager.discardAllControlled(human);
        Match match = new Match(human, bot, arena, humanRed, level);
        MATCHES.put(human.getUUID(), match);
        Vec3 p = match.humanPoint();
        ServerGodMode.forceOff(human);
        float facing = (float) (Math.toDegrees(Math.atan2(match.botPoint().z - p.z,
                match.botPoint().x - p.x)) - 90.0);
        human.teleportTo(level, p.x, p.y, p.z, facing, 0);
        if (!spawnBot(match)) { finish(human.server, match, null, "机器人宝可梦生成失败"); return false; }
        PokemonEntity botEntity = find(human.server, match.botEntity);
        if (botEntity != null) {
            botEntity.setInvulnerable(true);
            com.pkmoba.pokemon.BallThrow.releaseLock(botEntity);
        }
        CharizardCombat.clearArena();
        ServerGodMode.setActive(human, true);
        TeamService.sendTeamState(human);
        human.sendSystemMessage(Component.literal("训练对战已创建。倒计时结束后即可选择宝可梦并召唤。"));
        sendCountdown(human, ENTRY_COUNTDOWN_TICKS);
        return true;
    }

    private static boolean spawnBot(Match match) {
        if (match.botIndex >= TEAM.size()) return false;
        PokemonEntity entity = CharizardManager.spawnMatchEntity(match.actor.serverLevel(), match.botPoint(),
                ResourceLocation.fromNamespaceAndPath("cobblemon", TEAM.get(match.botIndex)));
        if (entity == null) return false;
        CharizardCombat.reset(entity);
        float designMax = CharizardCombat.maxHpFor(entity);
        com.pkmoba.combat.PokemonHp.healToFull(entity, designMax);
        CharizardManager.registerControlled(match.bot, entity);
        match.botEntity = entity.getUUID();
        match.botDeadline = 0;
        match.nextSkill = 0;
        match.lastSkill = null;
        match.chargedIntent = null;
        entity.setInvulnerable(true);
        com.pkmoba.pokemon.BallThrow.releaseLock(entity);
        com.pkmoba.combat.RecallBeam.sendOut(match.actor.serverLevel(), entity);
        CharizardManager.broadcastSync(match.actor.server);
        return true;
    }

    public static void notifySummoned(ServerPlayer player, PokemonEntity entity) {
        Match match = MATCHES.get(player.getUUID());
        if (match == null) return;
        match.humanEntity = entity.getUUID();
        match.initialSummonPending = false;
        match.humanDeadline = 0;
        match.battleStartsAt = 0;
        long now = player.server.getTickCount();
        match.protectedUntil = Math.max(match.entryStartsAt, match.botDeadline);
        boolean preparing = match.protectedUntil > now;
        entity.setInvulnerable(preparing);
        com.pkmoba.pokemon.BallThrow.releaseLock(entity);
        PokemonEntity bot = find(player.server, match.botEntity);
        if (bot != null) {
            bot.setInvulnerable(preparing);
            com.pkmoba.pokemon.BallThrow.releaseLock(bot);
        }
        player.sendSystemMessage(Component.literal("宝可梦已出战，训练对战开始！"));
    }

    public static void notifyRecalled(ServerPlayer player) {
        Match match = MATCHES.get(player.getUUID());
        if (match == null) return;
        PokemonEntity current = CharizardManager.getControlled(player);
        match.humanEntity = current == null ? null : current.getUUID();
        match.humanDeadline = current == null ? player.server.getTickCount() + SWITCH_TICKS : 0;
        match.protectedUntil = match.humanDeadline;
        match.battleStartsAt = 0;
        if (current == null) {
            PokemonEntity bot = find(player.server, match.botEntity);
            if (bot != null) {
                bot.setInvulnerable(true);
                com.pkmoba.pokemon.BallThrow.releaseLock(bot);
            }
        } else {
            match.humanEntity = current.getUUID();
            match.humanDeadline = 0;
        }
    }

    public static int pendingTicksFor(ServerPlayer player) {
        Match match = player == null ? null : MATCHES.get(player.getUUID());
        return match == null || match.humanDeadline == 0 ? 0 : (int) Math.max(0, match.humanDeadline - player.server.getTickCount());
    }

    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        for (Match match : new ArrayList<>(MATCHES.values())) {
            ServerPlayer human = server.getPlayerList().getPlayer(match.human);
            if (human == null || !human.isAlive() || human.level() != match.actor.level()) {
                finish(server, match, null, "玩家离线、死亡或离开维度");
                continue;
            }
            if (match.humanDying != null && now >= match.humanDyingUntil) { discard(server, match.humanDying); match.humanDying = null; }
            if (match.botDying != null && now >= match.botDyingUntil) { discard(server, match.botDying); match.botDying = null; }
            PokemonEntity own = find(server, match.humanEntity);
            PokemonEntity bot = find(server, match.botEntity);
            if (match.initialSummonPending || now < match.entryStartsAt || match.humanDeadline > now || match.botDeadline > now
                    || match.battleStartsAt > now || match.protectedUntil > now) {
                if (own != null) own.setInvulnerable(true);
                if (bot != null) bot.setInvulnerable(true);
            } else {
                if (own != null && own.isInvulnerable()) own.setInvulnerable(false);
                if (bot != null && bot.isInvulnerable()) bot.setInvulnerable(false);
            }
            if (now < match.entryStartsAt) {
                if ((match.entryStartsAt - now) % 20 == 0) {
                    sendCountdown(human, match.entryStartsAt - now);
                    human.playNotifySound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(),
                            net.minecraft.sounds.SoundSource.PLAYERS, 0.65F, 1.0F + (float) (10 * 20 - (match.entryStartsAt - now)) / 400.0F);
                }
            } else if (match.initialSummonPending) {
                match.initialSummonPending = false;
                match.protectedUntil = 0;
                showTitle(human, "开始召唤", "按 C 选择宝可梦 · 按 X 出战");
                human.playNotifySound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(),
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.4F);
            }
            if (match.battleStartsAt > 0 && now >= match.battleStartsAt) {
                match.battleStartsAt = 0;
                human.sendSystemMessage(Component.literal("训练对战开始！"));
            }
            if (match.humanEntity != null && (own == null || !own.isAlive())) {
                match.humanDying = match.humanEntity;
                match.humanDyingUntil = now + SWITCH_TICKS;
                if (own != null) { CharizardCombat.reset(own); TeamService.markDead(human, own); }
                match.humanEntity = null;
                var profile = ProfileStore.get(server).profile(match.human);
                if (profile.team.stream().noneMatch(id -> !profile.fainted.contains(id))) { finish(server, match, false, "你的队伍全部倒下"); continue; }
                match.humanDeadline = now + SWITCH_TICKS;
                match.battleStartsAt = 0;
                match.protectedUntil = match.humanDeadline;
                if (bot != null) {
                    bot.setInvulnerable(true);
                    com.pkmoba.pokemon.BallThrow.releaseLock(bot);
                }
                showTitle(human, "你的宝可梦倒下了", "按 C 选择 · 按 X 出战 · 剩余 20 秒");
                human.sendSystemMessage(Component.literal("你的宝可梦倒下了。按 C 选择下一只并按 X 出战，你有 20 秒。"));
            }
            if (match.humanEntity == null && match.humanDeadline > 0 && now >= match.humanDeadline) { finish(server, match, false, "换人超时"); continue; }
            if (match.botEntity != null && (bot == null || !bot.isAlive())) {
                match.botDying = match.botEntity;
                match.botDyingUntil = now + SWITCH_TICKS;
                if (bot != null) CharizardCombat.reset(bot);
                CharizardManager.unregisterControlled(match.bot);
                match.botEntity = null;
                match.botIndex++;
                if (match.botIndex >= TEAM.size()) { finish(server, match, true, "机器人队伍全部倒下"); continue; }
                match.botDeadline = now + BOT_REPLACEMENT_TICKS;
                match.protectedUntil = match.botDeadline;
                showTitle(human, "机器人宝可梦倒下了", "下一只即将出战");
                human.sendSystemMessage(Component.literal("机器人宝可梦倒下了，2 秒后下一只出战。"));
            }
            if (match.botEntity == null && match.botDeadline > 0 && now >= match.botDeadline) {
                if (!spawnBot(match)) { finish(server, match, null, "机器人换人失败"); continue; }
                bot = find(server, match.botEntity);
                if (bot != null) human.sendSystemMessage(Component.literal("新的机器人宝可梦已出战。"));
            }
            if (own != null && own.isAlive() && bot != null && bot.isAlive()) {
                if (match.battleStartsAt > now) {
                    sendCountdown(human, match.battleStartsAt - now);
                } else if (match.battleStartsAt == 0 && match.humanDeadline == 0 && match.botDeadline == 0) {
                    ai(match, bot, own, now);
                }
            }
            if (now % 5 == 0) { sendHud(human, match, bot); TeamService.sendTeamState(human); }
        }
    }

    private static void ai(Match match, PokemonEntity bot, PokemonEntity opponent, long now) {
        match.actor.setPos(bot.position());
        if (com.pkmoba.pokemon.BallThrow.locked(bot) || DuelManager.isPreparing(bot)) {
            bot.getNavigation().stop();
            return;
        }
        double distance = bot.position().subtract(opponent.position()).horizontalDistance();
        // Small bounded lead for moving targets, never predict through half the arena.
        Vec3 velocity = opponent.getDeltaMovement().multiply(1, 0, 1);
        if (velocity.lengthSqr() > .36) velocity = velocity.normalize().scale(.6);
        Vec3 target = opponent.getBoundingBox().getCenter().add(velocity.scale(3));
        CharizardCombat.updateAimForBot(bot, target);
        if (match.lastSpecies != speciesIndex(bot)) {
            match.lastSpecies = speciesIndex(bot);
            match.lastSkill = null;
            match.chargedIntent = null;
            java.util.Arrays.fill(match.lastCastAt, 0);
        }

        int channel = CharizardCombat.channelFor(bot);
        // A committed charged cast owns resource saving and approach until it starts.
        // Otherwise 1 bean/sec is immediately spent on cheap Q/E and R never reaches its cost.
        if (channel == 0) {
            if (match.chargedIntent != null && (now >= match.chargedIntentUntil
                    || !CharizardCombat.readyForBot(bot, match.chargedIntent))) {
                // Expired approaches must hand over their turn rather than immediately
                // selecting the same failed W again and starving R forever.
                match.nextCharged = match.chargedIntent == Skill.W ? Skill.R : Skill.W;
                match.chargedIntent = null;
            }
            if (match.chargedIntent == null) {
                Skill first = (speciesIndex(bot) == 0 || Skill.isBlastoise(bot)) ? match.nextCharged : Skill.R;
                Skill second = first == Skill.W ? Skill.R : Skill.W;
                for (Skill candidate : new Skill[] { first, second }) {
                    if (candidate == Skill.W && speciesIndex(bot) != 0 && !Skill.isBlastoise(bot)) continue;
                    if (!CharizardCombat.readyForBot(bot, candidate)) continue;
                    long last = match.lastCastAt[candidate.ordinal()];
                    if (last > 0 && now - last < 160) continue;
                    match.chargedIntent = candidate;
                    match.chargedIntentUntil = now + 240;
                    com.pkmoba.PkmobaMod.LOGGER.info("[pkmoba-bot] intent entity={} species={} skill={} tick={} range={}",
                            bot.getId(), bot.getPokemon().getSpecies().getResourceIdentifier(), candidate, now, skillRange(bot, candidate));
                    break;
                }
            }
        }
        boolean spinning = Skill.isBlastoise(bot) && channel == com.pkmoba.combat.BlastoiseCombat.CHANNEL_SPIN;
        boolean rooted = CharizardCombat.blocksMovement(bot)
                || speciesIndex(bot) == 0 && channel == 2;
        if (rooted) {
            bot.getNavigation().stop();
        } else if (now % 5 == 0) {
            // Rapid Spin only hits at contact range. Do not back away while it is active.
            double desired = spinning ? 1.5 : match.chargedIntent != null
                    ? Math.max(2.0, skillRange(bot, match.chargedIntent) - 1.5) : desiredRange(bot);
            Vec3 away = bot.position().subtract(opponent.position()).multiply(1, 0, 1);
            if (distance > desired + 1.0) bot.getNavigation().moveTo(opponent, spinning ? 1.25 : 1.15);
            else if (!spinning && distance < Math.max(1.8, desired - 2.2) && away.lengthSqr() > .001) {
                Vec3 retreat = bot.position().add(away.normalize().scale(2));
                if (match.arena.insideBoundary(retreat)) bot.getNavigation().moveTo(retreat.x, retreat.y, retreat.z, .95);
                else bot.getNavigation().stop();
            } else bot.getNavigation().stop();
        }
        // Channels tick themselves; keep aim updated above, but never interrupt their windup.
        if (channel != 0 || rooted || now % 4 != 0) return;
        boolean sight = bot.hasLineOfSight(opponent);
        if (match.chargedIntent != null) {
            Skill intent = match.chargedIntent;
            if ((sight || (speciesIndex(bot) == 0 && intent == Skill.W))
                    && distance <= skillRange(bot, intent) && distance >= minimumRange(bot, intent)
                    && CharizardCombat.tryCastForBot(bot, intent, target)) {
                match.lastSkill = intent;
                match.lastCastAt[intent.ordinal()] = now;
                match.nextCharged = intent == Skill.W ? Skill.R : Skill.W;
                match.chargedIntent = null;
            } else if (now >= match.nextIntentLog) {
                match.nextIntentLog = now + 80;
                com.pkmoba.PkmobaMod.LOGGER.info("[pkmoba-bot] waiting entity={} skill={} tick={} distance={} affordable={} channel={} navDone={}",
                        bot.getId(), intent, now, distance, CharizardCombat.canCastForBot(bot, intent), channel, bot.getNavigation().isDone());
            }
            return; // Do not drain reserved beans with a different cheap cast.
        }
        Skill selected = null;
        double selectedScore = Double.NEGATIVE_INFINITY;
        for (Skill skill : Skill.values()) {
            if (!sight && !(speciesIndex(bot) == 0 && skill == Skill.W)) continue;
            if (!CharizardCombat.canCastForBot(bot, skill)) continue;
            if (distance > skillRange(bot, skill) || distance < minimumRange(bot, skill)) continue;
            double score = skillScore(bot, skill, distance, opponent);
            // Prevent a cheap E/Q rotation from permanently starving W; only legal, in-range
            // skills participate and the bonus is bounded, not a forced cooldown bypass.
            long idle = match.lastCastAt[skill.ordinal()] == 0 ? 180 : now - match.lastCastAt[skill.ordinal()];
            score += Math.min(2.5, idle / 100.0);
            if (skill == match.lastSkill) score -= 1.2;
            if (score > selectedScore) { selectedScore = score; selected = skill; }
        }
        if (selected != null && CharizardCombat.tryCastForBot(bot, selected, target)) {
            match.lastSkill = selected;
            match.lastCastAt[selected.ordinal()] = now;
        }
    }

    private static int speciesIndex(PokemonEntity pokemon) {
        if (com.pkmoba.combat.Skill.isBlastoise(pokemon)) return 1;
        if (com.pkmoba.combat.Skill.isVenusaur(pokemon)) return 2;
        if (com.pkmoba.combat.Skill.isPikachu(pokemon)) return 3;
        return 0;
    }

    private static double desiredRange(PokemonEntity pokemon) {
        if (Skill.isBlastoise(pokemon)) {
            if (CharizardCombat.canCastForBot(pokemon, Skill.Q)) return 8.5;
            if (CharizardCombat.canCastForBot(pokemon, Skill.R)) return 4.8;
            if (CharizardCombat.canCastForBot(pokemon, Skill.W)) return 2.2;
            return 6;
        }
        return Skill.isPikachu(pokemon) ? 9.5 : Skill.isVenusaur(pokemon) ? 7.5 : 6.5;
    }

    private static double skillRange(PokemonEntity pokemon, Skill skill) {
        if (com.pkmoba.combat.Skill.isPikachu(pokemon)) return switch (skill) {
            case Q -> com.pkmoba.combat.PikachuCombat.Q_RANGE;
            case W -> com.pkmoba.combat.PikachuCombat.W_RANGE;
            case E -> com.pkmoba.combat.PikachuCombat.E_RANGE;
            case R -> com.pkmoba.combat.PikachuCombat.R_RANGE;
        };
        if (com.pkmoba.combat.Skill.isBlastoise(pokemon)) return switch (skill) {
            case Q -> 16; case W -> 8.0; case E -> 10; case R -> 5.8;
        };
        if (com.pkmoba.combat.Skill.isVenusaur(pokemon)) return switch (skill) {
            case Q -> com.pkmoba.combat.VenusaurCombat.Q_RANGE;
            case W -> com.pkmoba.combat.VenusaurCombat.W_RANGE;
            case E -> com.pkmoba.combat.VenusaurCombat.E_RANGE;
            case R -> com.pkmoba.combat.VenusaurCombat.R_RANGE;
        };
        return switch (skill) { case Q -> 9; case W -> 12; case E -> 14; case R -> 16; };
    }

    private static double minimumRange(PokemonEntity pokemon, Skill skill) {
        // Hydro Pump works at point blank as well; the old four-block minimum stranded it.
        if (com.pkmoba.combat.Skill.isPikachu(pokemon) && skill == Skill.R) return 2;
        return 0;
    }

    private static double skillScore(PokemonEntity pokemon, Skill skill, double distance, PokemonEntity target) {
        if (com.pkmoba.combat.Skill.isPikachu(pokemon)) {
            double desired = switch (skill) { case Q -> 10; case W -> 5; case E -> 7; case R -> 8; };
            double score = 5 - Math.abs(distance - desired) * 0.45;
            if (skill == Skill.E && distance < 9) score += 1.0;
            if (skill == Skill.R) score += target.getHealth() <= target.getMaxHealth() * 0.5 ? 2.0 : -0.8;
            return score;
        }
        if (!Skill.isBlastoise(pokemon) && !Skill.isVenusaur(pokemon)) {
            // Fire Spin is the area/control opener; Fly is a gap-close, not a permanent
            // three-second lockout that repeatedly starves W at ordinary fighting range.
            return switch (skill) {
                case W -> 6.4 - Math.abs(distance - 6.5) * .20;
                case Q -> 5.5 - Math.abs(distance - 5.5) * .30;
                case E -> distance > 9 ? 5.8 : 1.8;
                case R -> target.getHealth() < target.getMaxHealth() * .45 ? 7.0 : 3.7;
            };
        }
        double desired = switch (skill) {
            case Q -> com.pkmoba.combat.Skill.isBlastoise(pokemon) ? 10 : 5;
            case W -> 5.5;
            case E -> 6.5;
            case R -> 8;
        };
        double score = 5 - Math.abs(distance - desired) * 0.45;
        if (skill == Skill.R) score += target.getHealth() <= target.getMaxHealth() * 0.55 ? 2.2 : -1.2;
        if (skill == Skill.E && distance < 4) score += 0.9;
        if (skill == Skill.W && distance > 7) score += 0.7;
        return score;
    }

    private static void sendCountdown(ServerPlayer human, long ticksLeft) {
        int seconds = (int) Math.ceil(ticksLeft / 20.0);
        showTitle(human, Integer.toString(seconds), "即将开始召唤");
    }

    private static void showTitle(ServerPlayer player, String title, String subtitle) {
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, 22, 0));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(title)));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
    }

    private static void sendHud(ServerPlayer human, Match match, @Nullable PokemonEntity bot) {
        if (!ServerPlayNetworking.canSend(human, DuelStatePayload.TYPE)) return;
        String species = TEAM.get(Math.min(match.botIndex, TEAM.size() - 1));
        ServerPlayNetworking.send(human, new DuelStatePayload(true, bot == null ? 0 : bot.getHealth(),
                bot == null ? CharizardCombat.maxHpFor("cobblemon:" + species) : bot.getMaxHealth(),
                match.bot, BOT_NAME, com.pkmoba.pokemon.SpeciesRegistry.displayName(ResourceLocation.fromNamespaceAndPath("cobblemon", species)),
                species, Math.max(0, TEAM.size() - match.botIndex)));
    }

    public static int cancelArena(MinecraftServer server, String arena) {
        int count = 0;
        for (Match match : new ArrayList<>(MATCHES.values())) {
            if (!"all".equalsIgnoreCase(arena) && !match.arena.name.equalsIgnoreCase(arena)) continue;
            finish(server, match, null, "对局被取消"); count++;
        }
        return count;
    }

    private static void finish(MinecraftServer server, Match match, @Nullable Boolean won, String reason) {
        if (MATCHES.remove(match.human) == null) return;
        ServerPlayer human = server.getPlayerList().getPlayer(match.human);
        discard(server, match.humanEntity);
        discard(server, match.botEntity);
        discard(server, match.humanDying);
        discard(server, match.botDying);
        CharizardManager.unregisterControlled(match.human);
        CharizardManager.unregisterControlled(match.bot);
        if (human != null) {
            com.pkmoba.pokemon.BallThrow.clearPlayer(match.human);
            ServerGodMode.setActive(human, false);
            if (ServerPlayNetworking.canSend(human, DuelStatePayload.TYPE)) ServerPlayNetworking.send(human, new DuelStatePayload(false, 0, 1, null, "", "", "", 0));
            if (ServerPlayNetworking.canSend(human, MatchResultPayload.TYPE)) ServerPlayNetworking.send(human, new MatchResultPayload(won == null ? "" : won ? human.getGameProfile().getName() : BOT_NAME, Boolean.TRUE.equals(won), reason));
            human.sendSystemMessage(Component.literal("训练对战结束：" + reason));
            TeamService.sendTeamState(human);
        }
        CharizardManager.broadcastSync(server);
    }

    @Nullable private static PokemonEntity find(MinecraftServer server, @Nullable UUID id) {
        if (id == null) return null;
        for (ServerLevel level : server.getAllLevels()) if (level.getEntity(id) instanceof PokemonEntity pokemon) return pokemon;
        return null;
    }

    private static void discard(MinecraftServer server, @Nullable UUID id) {
        PokemonEntity entity = find(server, id);
        if (entity != null) { CharizardCombat.reset(entity); entity.getNavigation().stop(); entity.discard(); }
    }
}
