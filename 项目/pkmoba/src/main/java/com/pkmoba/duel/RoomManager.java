package com.pkmoba.duel;

import com.pkmoba.PkmobaMod;
import com.pkmoba.network.RoomListPayload;
import com.pkmoba.network.RoomActionPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 房间对战系统（H 键大厅）。
 *
 * <p>数据关系：{@link ArenaStore.Arena 竞技场点位组} → {@link Room 房间实例} →
 * 玩家与阵营 → 对战状态。点位组是持久地图配置，房间是临时实例，两者分离；
 * 房间只通过名字引用点位组，不修改它。
 *
 * <p>状态机：{@code WAITING → READY → COUNTDOWN → FIGHTING → ENDED → WAITING}。
 * 倒计时 5 秒，结束后把双方交给 {@link DuelManager} 实际开局（对局本身仍是原来的
 * DuelManager 逻辑，房间只负责成员/权限/生命周期）。
 *
 * <p>0.42 规则（用户 m04660）：房间只由点位放置器四点成组时自动创建（H 键只进不出建，
 * 不再提供客户端 CREATE 入口）；自动创建的房间初始没有房主，<b>第一个加入的玩家成为房主</b>，
 * 房主离开时按加入顺序转交给最早的下一位成员；房间空了不销毁，状态归零回「待开局」。
 */
public final class RoomManager {
    /** 房间状态。 */
    public enum State { WAITING, READY, COUNTDOWN, FIGHTING, ENDED }

    /** 确认队伍后提供完整入场预告。 */
    private static final int COUNTDOWN_TICKS = 20 * 5;
    /** 对局结束到房间恢复的等待：3 秒。 */
    private static final int END_LINGER_TICKS = 20 * 3;

    /** 一个房间实例。 */
    public static final class Room {
        public final int id;
        public final String arenaName;
        /** 加入顺序：第一个人加入时成为房主，房主离开时把房主交给顺序里的下一个人。 */
        public final List<UUID> joinOrder = new ArrayList<>();
        public UUID host;
        public UUID red;
        public UUID blue;
        public UUID bot;
        public List<String> botRoster = List.of();
        public final java.util.Set<UUID> ready = new java.util.HashSet<>();
        public State state = State.WAITING;
        public int countdown;
        public int endTicks;
        public final Map<UUID, List<String>> rosters = new java.util.HashMap<>();
        public RoomSettingsStore.Settings settings;
        public final Map<UUID, java.util.Set<String>> revealed = new java.util.HashMap<>();

        Room(int id, String arenaName) {
            this.id = id;
            this.arenaName = arenaName;
            this.settings = server == null ? new RoomSettingsStore.Settings()
                    : RoomSettingsStore.get(server).settings(arenaName);
        }

        @Nullable
        public UUID sideOf(UUID player) {
            if (player == null) return null;
            if (player.equals(red)) return red;
            if (player.equals(blue)) return blue;
            return null;
        }

        public boolean isMember(UUID player) {
            return player != null && (player.equals(red) || player.equals(blue));
        }

        public boolean full() {
            return red != null && blue != null;
        }

        public int memberCount() {
            return (red != null ? 1 : 0) + (blue != null ? 1 : 0);
        }
    }

    private static final Map<Integer, Room> ROOMS = new LinkedHashMap<>();
    /** Match arenaN to room N whenever free; otherwise reuse the first vacant room ID. */
    private static int nextRoomId(String arenaName) {
        if (arenaName.matches("(?i)arena[1-9][0-9]*")) {
            try {
                int preferred = Integer.parseInt(arenaName.substring(5));
                if (!ROOMS.containsKey(preferred)) return preferred;
            } catch (NumberFormatException ignored) { }
        }
        int candidate = 1;
        while (ROOMS.containsKey(candidate)) candidate++;
        return candidate;
    }
    private static MinecraftServer server;

    private RoomManager() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(RoomManager::tick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            ROOMS.clear();
            LANDING.clear();
            JOIN_ATTEMPTS.clear();
            com.pkmoba.pokemon.ProfileStore.get(stopped).clearBattles();
            ArenaStore.clearDrafts();
            server = null;
        });
    }

    // ------------------------------------------------------------------ 操作入口

    /** 打开大厅：把当前快照发回客户端（客户端收到后自己开界面）。 */
    public static void open(ServerPlayer player) {
        sendSnapshot(player);
    }

    /**
     * 自动生成房间：点位放置器四点成组时调用（见 {@code ModeItems.autoGroup}）。
     *
     * <p>0.42：这个入口<b>不把调用者加进房间</b>，也不指定房主——房间创建后为空房，
     * 谁先在 H 大厅点「加入」谁就是房主。客户端 UI 没有 CREATE 入口，H 键只打开大厅。
     */
    public static void create(ServerPlayer player, String arenaName) {
        MinecraftServer running = player.server;
        ArenaStore store = ArenaStore.get(running);
        ArenaStore.Arena arena = store.byName(arenaName);
        if (arena == null) {
            player.sendSystemMessage(Component.literal("找不到点位组：" + arenaName + "（用 /mo arena list 查看）"));
            return;
        }
        server = running;
        if (!usable(running, arena)) {
            player.sendSystemMessage(Component.literal("点位组「" + arena.name + "」还没配置完整，不能开房。"));
            return;
        }
        // 一个点位组只对应一个房间：已经生成过就复用，不重复建。
        for (Room other : ROOMS.values()) {
            if (!other.arenaName.equalsIgnoreCase(arena.name)) continue;
            if (other.state == State.COUNTDOWN || other.state == State.FIGHTING) {
                player.sendSystemMessage(Component.literal("该点位组正在对战中，稍后再来。"));
                return;
            }
            player.sendSystemMessage(Component.literal("点位组「" + arena.name + "」的房间 #"
                    + other.id + " 已存在，按 H 打开大厅即可加入。"));
            return;
        }
        Room room = new Room(nextRoomId(arena.name), arena.name);
        ROOMS.put(room.id, room);
        player.sendSystemMessage(Component.literal("点位组「" + arena.name + "」已生成房间 #" + room.id
                + "（空房）。按 H 打开大厅，第一个加入的人成为房主。"));
        broadcast();
    }

    /** 加入房间：自动补空位（红先、蓝后）。 */
    public static void join(ServerPlayer player, int roomId) {
        join(player, roomId, "");
    }

    private static final Map<UUID, Long> JOIN_ATTEMPTS = new java.util.HashMap<>();

    public static void join(ServerPlayer player, int roomId, String password) {
        reconcile(player.server);
        Room room = ROOMS.get(roomId);
        if (room == null) {
            player.sendSystemMessage(Component.literal("房间不存在或已解散。"));
            return;
        }
        if (room.isMember(player.getUUID())) {
            player.sendSystemMessage(Component.literal("你已经在这个房间里了。"));
            return;
        }
        if (room.state == State.COUNTDOWN || room.state == State.FIGHTING || room.state == State.ENDED) {
            player.sendSystemMessage(Component.literal("该房间已开局，禁止中途加入。"));
            return;
        }
        if (room.full()) {
            player.sendSystemMessage(Component.literal("房间已满员。"));
            return;
        }
        Room mine = roomOf(player.getUUID());
        if (mine != null) {
            player.sendSystemMessage(Component.literal("你已经在房间 #" + mine.id + " 里，请先退出。"));
            return;
        }
        if (DuelManager.inMatch(player)) {
            player.sendSystemMessage(Component.literal("你正在对局中，结束之后才能进房间。"));
            return;
        }
        if (room.settings.passwordProtected()) {
            long now = player.server.getTickCount();
            if (now < JOIN_ATTEMPTS.getOrDefault(player.getUUID(), 0L)) {
                player.sendSystemMessage(Component.literal("请稍等后重试加入。"));
                return;
            }
            JOIN_ATTEMPTS.put(player.getUUID(), now + 40);
            if (password == null || password.length() > 32 || !RoomRules.verifyPassword(password,
                    room.settings.passwordSalt, room.settings.passwordHash)) {
                player.sendSystemMessage(Component.literal("房间密码错误。"));
                return;
            }
        }
        room.rosters.put(player.getUUID(), defaultRoster(player, room));
        if (room.red == null) {
            room.red = player.getUUID();
        } else {
            room.blue = player.getUUID();
        }
        room.joinOrder.add(player.getUUID());
        // 第一个加入的人成为房主（空房自动生成时没有房主）。
        boolean becameHost = false;
        if (room.host == null) {
            room.host = player.getUUID();
            becameHost = true;
        }
        room.state = room.full() ? State.READY : State.WAITING;
        player.sendSystemMessage(Component.literal("已加入房间 #" + room.id + "（"
                + (player.getUUID().equals(room.red) ? "红方" : "蓝方") + "）"
                + (becameHost ? "，你是房主，双方到齐后可以开始对战。" : "。")));
        broadcast();
    }

    /** 退出房间：房主退出时按加入顺序把房主转交给下一位；房间空了状态归零回「待开局」（不销毁）。 */
    public static void leave(ServerPlayer player) {
        Room room = roomOf(player.getUUID());
        if (room == null) {
            player.sendSystemMessage(Component.literal("你不在任何房间里。"));
            return;
        }
        if (room.state == State.FIGHTING) {
            player.sendSystemMessage(Component.literal("对局已开始，不能退出房间。"));
            return;
        }
        cancelCountdown(room);
        removeMember(room, player.getUUID());
        player.sendSystemMessage(Component.literal("已退出房间 #" + room.id + "。"));
        broadcast();
    }

    public static void addBot(ServerPlayer player, int roomId) {
        Room room = roomOf(player.getUUID());
        if (room == null || room.id != roomId || !player.getUUID().equals(room.host)
                || room.full() || (room.state != State.WAITING && room.state != State.READY)) return;
        room.ready.clear();
        room.bot = UUID.randomUUID();
        room.botRoster = BotDuelService.randomTeam();
        if (room.red == null) room.red = room.bot; else room.blue = room.bot;
        room.state = State.READY;
        broadcast();
    }

    /** Host-only seat removal; never tear down a running match through a lobby action. */
    public static void removeBot(ServerPlayer player, int roomId) {
        Room room = roomOf(player.getUUID());
        if (room == null || room.id != roomId || !player.getUUID().equals(room.host)
                || room.bot == null || (room.state != State.WAITING && room.state != State.READY
                && room.state != State.COUNTDOWN)) {
            sendSnapshot(player);
            return;
        }
        cancelCountdown(room);
        UUID bot = room.bot;
        if (bot.equals(room.red)) room.red = null;
        if (bot.equals(room.blue)) room.blue = null;
        room.rosters.remove(bot);
        room.revealed.remove(bot);
        LANDING.remove(bot);
        room.bot = null;
        room.botRoster = List.of();
        room.ready.clear();
        room.countdown = 0;
        room.state = State.WAITING;
        player.sendSystemMessage(Component.literal("人机已移出房间，准备状态已重置。"));
        broadcast();
    }

    private static boolean allReady(Room room) {
        return room.full() && (room.red.equals(room.bot) || room.ready.contains(room.red))
                && (room.blue.equals(room.bot) || room.ready.contains(room.blue));
    }

    public static void ready(ServerPlayer player) {
        Room room = roomOf(player.getUUID());
        if (room == null || room.state == State.FIGHTING || room.state == State.ENDED) return;
        if (room.state == State.COUNTDOWN || room.ready.contains(player.getUUID())) {
            cancelCountdown(room);
            room.ready.remove(player.getUUID());
            broadcast();
            return;
        }
        if (!validRoster(player, room)) return;
        room.ready.add(player.getUUID());
        player.sendSystemMessage(Component.literal("已准备，双方就绪后自动倒计时 5 秒。"));
        ServerPlayer host = playerOf(player.server, room.host);
        if (host != null && allReady(room)) { start(host); return; }
        broadcast();
    }

    /** 双方明确准备后，房主才能启动入场倒计时。 */
    public static void start(ServerPlayer player) {
        Room room = roomOf(player.getUUID());
        if (room == null) {
            player.sendSystemMessage(Component.literal("你不在任何房间里。"));
            return;
        }
        if (!player.getUUID().equals(room.host)) {
            player.sendSystemMessage(Component.literal("只有房主可以开始游戏。"));
            return;
        }
        if (!room.full()) {
            player.sendSystemMessage(Component.literal("需要红蓝双方都有人才能开始。"));
            return;
        }
        if (room.state != State.WAITING && room.state != State.READY) {
            player.sendSystemMessage(Component.literal("房间状态不允许开始（当前：" + stateName(room.state) + "）。"));
            return;
        }
        if (!validRoster(player, room)) return;
        if (!allReady(room)) {
            player.sendSystemMessage(Component.literal("请双方先确认队伍并点击准备，再开始对战。"));
            return;
        }
        ArenaStore.Arena arena = ArenaStore.get(player.server).byName(room.arenaName);
        if (!usable(player.server, arena)) {
            player.sendSystemMessage(Component.literal("点位组不可用，无法开始。"));
            return;
        }
        ServerPlayer red = playerOf(player.server, room.red);
        ServerPlayer blue = playerOf(player.server, room.blue);
        if (room.bot == null && (red == null || blue == null || !validRoster(red, room) || !validRoster(blue, room))) return;
        room.revealed.clear();
        room.state = State.COUNTDOWN;
        room.countdown = COUNTDOWN_TICKS;
        broadcast();
    }

    // ------------------------------------------------------------------ 每 tick

    private static void tick(MinecraftServer running) {
        server = running;
        if (running.getTickCount() % 200 == 0) JOIN_ATTEMPTS.entrySet().removeIf(entry -> entry.getValue() < running.getTickCount());
        if (ROOMS.isEmpty()) return;
        boolean changed = false;
        List<Room> snapshot = new ArrayList<>(ROOMS.values());
        for (Room room : snapshot) {
            switch (room.state) {
                case COUNTDOWN -> {
                    if (!allReady(room)) { cancelCountdown(room); changed = true; break; }
                    room.countdown--;
                    if (room.countdown <= 0) beginMatch(running, room);
                    else if (room.countdown % 20 == 0) {
                        title(playerOf(running, room.red), Integer.toString(room.countdown / 20), "准备开局 · 可取消准备返回编辑");
                        title(playerOf(running, room.blue), Integer.toString(room.countdown / 20), "准备开局 · 可取消准备返回编辑");
                    }
                    if (room.countdown % 20 == 0) changed = true;
                }
                case FIGHTING -> {
                    ServerPlayer red = playerOf(running, room.red);
                    ServerPlayer blue = playerOf(running, room.blue);
                    boolean redIn = red != null && DuelManager.inMatch(red);
                    boolean blueIn = blue != null && DuelManager.inMatch(blue);
                    if (!redIn && !blueIn) {
                        room.state = State.ENDED;
                        room.endTicks = END_LINGER_TICKS;
                        changed = true;
                    }
                }
                case ENDED -> {
                    room.endTicks--;
                    if (room.endTicks <= 0) {
                        // 对局结束：房间保留、双方仍在房里，状态回到「待开局」可以再来一局。
                        room.ready.clear();
                        room.countdown = 0;
                        room.endTicks = 0;
                        // Bots exist only for the challenge explicitly requested by the host.
                        if (room.bot != null) {
                            if (room.bot.equals(room.red)) room.red = null;
                            if (room.bot.equals(room.blue)) room.blue = null;
                            room.bot = null;
                        }
                        room.state = room.full() ? State.READY : State.WAITING;
                        changed = true;
                    }
                }
                default -> {
                }
            }
        }
        if (changed) broadcast();
    }

    private static void beginMatch(MinecraftServer running, Room room) {
        ServerPlayer red = playerOf(running, room.red);
        ServerPlayer blue = playerOf(running, room.blue);
        ArenaStore.Arena arena = ArenaStore.get(running).byName(room.arenaName);
        if (room.bot != null) {
            ServerPlayer human = playerOf(running, room.host);
            if (human == null || !validRoster(human, room)
                    || !com.pkmoba.pokemon.ProfileStore.get(running).beginBattle(human.getUUID(), room.rosters.get(human.getUUID()))) {
                room.ready.clear(); room.state = State.READY; room.countdown = 0;
                return;
            }
            if (!BotDuelService.start(human, room.bot, arena, room.host.equals(room.red))) {
                com.pkmoba.pokemon.ProfileStore.get(running).endBattle(human.getUUID());
                room.state = State.READY;
                room.ready.clear();
                room.countdown = 0;
                return;
            }
            room.state = State.FIGHTING;
            room.countdown = 0;
            applyLanding(human);
            title(human, "选择出战", "X 召唤首发 · 不再叠加入场倒计时");
            broadcast();
            return;
        }
        if (red == null || blue == null || arena == null) {
            room.state = State.WAITING;
            room.countdown = 0;
            room.ready.clear();
            return;
        }
        if (!validRoster(red, room) || !validRoster(blue, room)
                || !DuelManager.startRoom(red, blue, arena, room.rosters.get(red.getUUID()), room.rosters.get(blue.getUUID()))) {
            room.state = State.READY;
            room.countdown = 0;
            room.ready.clear();
            red.sendSystemMessage(Component.literal("开局失败：请确认双方都有宝可梦队伍。"));
            blue.sendSystemMessage(Component.literal("开局失败：请确认双方都有宝可梦队伍。"));
            return;
        }
        room.state = State.FIGHTING;
        room.countdown = 0;
        applyLanding(red);
        applyLanding(blue);
    }

    // ------------------------------------------------------------------ 查询/工具

    @Nullable
    public static Room roomOf(UUID player) {
        if (player == null) return null;
        for (Room room : ROOMS.values()) {
            if (room.isMember(player)) return room;
        }
        return null;
    }

    /** m05326：点位组被删时，把挂在这一组上的房间也一并清掉。 */
    public static void deleteByArena(String arenaName) {
        if (arenaName == null) return;
        ROOMS.values().removeIf(room -> {
            if (!arenaName.trim().equalsIgnoreCase(room.arenaName)) return false;
            if (server != null) invalidate(server, room);
            return true;
        });
        broadcast();
    }

    /** m05326：按实体找房间（宝可梦 → 主人 → 房间），给边界裁剪用。 */
    @Nullable
    public static Room roomOfEntity(net.minecraft.world.entity.Entity entity) {
        if (entity == null) return null;
        UUID owner = null;
        if (entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity mon) {
            owner = com.pkmoba.charizard.CharizardManager.getControlledOwner(mon);
        }
        if (owner != null) {
            Room room = roomOf(owner);
            if (room != null) return room;
        }
        for (Room room : ROOMS.values()) {
            if (room.isMember(entity.getUUID())) return room;
        }
        return null;
    }

    public static boolean inRoom(UUID player) {
        return roomOf(player) != null;
    }

    public static int sideOf(UUID player) {
        Room room = roomOf(player);
        if (room == null) return 0;
        if (player.equals(room.red)) return 1;
        if (player.equals(room.blue)) return 2;
        return 0;
    }

    /** 玩家退出服务器/换维度时调用：释放房间位置。 */
    public static void onQuit(ServerPlayer player) {
        Room room = roomOf(player.getUUID());
        if (room == null) return;
        if (room.state == State.FIGHTING) DuelManager.cancelArena(player.server, room.arenaName);
        cancelCountdown(room);
        removeMember(room, player.getUUID());
        broadcast();
    }

    private static void removeMember(Room room, UUID player) {
        if (room.bot != null) {
            if (server != null) BotDuelService.cancelArena(server, room.arenaName);
            if (room.bot.equals(room.red)) room.red = null;
            if (room.bot.equals(room.blue)) room.blue = null;
            room.bot = null;
        }
        if (player.equals(room.red)) room.red = null;
        if (player.equals(room.blue)) room.blue = null;
        room.ready.clear();
        room.rosters.remove(player);
        room.countdown = 0;
        room.joinOrder.remove(player);
        LANDING.remove(player);
        // 房主离开：按加入顺序把房主交给最早的下一位成员。
        if (player.equals(room.host)) {
            room.host = room.joinOrder.isEmpty() ? null : room.joinOrder.get(0);
        }
        if (room.memberCount() == 0) {
            // 房间空了不销毁：房间属于点位组，成员与对局状态归零回「待开局」。
            resetRoom(room);
            return;
        }
        if (room.host == null) {
            room.host = room.joinOrder.isEmpty() ? null : room.joinOrder.get(0);
        }
        room.state = room.full() ? State.READY : State.WAITING;
    }

    /** 清空成员并把房间状态归零回「待开局」（房间本身保留，等待下一批玩家加入）。 */
    private static void resetRoom(Room room) {
        room.host = null;
        room.red = null;
        room.blue = null;
        room.bot = null;
        room.ready.clear();
        room.joinOrder.clear();
        room.rosters.clear();
        room.state = State.WAITING;
        room.countdown = 0;
        room.endTicks = 0;
    }

    @Nullable
    private static ServerPlayer playerOf(MinecraftServer running, @Nullable UUID id) {
        return id == null ? null : running.getPlayerList().getPlayer(id);
    }

    private static boolean teleportTo(ServerPlayer player, ArenaStore.Arena arena, @Nullable Vec3 point) {
        if (point == null) return true;
        ServerLevel target = levelOf(player, arena);
        if (target == null) return false;
        player.teleportTo(target, point.x, point.y, point.z, player.getYRot(), player.getXRot());
        return true;
    }

    @Nullable
    private static ServerLevel levelOf(ServerPlayer player, ArenaStore.Arena arena) {
        if (arena.dimension == null) return player.serverLevel();
        ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                arena.dimension);
        return player.server.getLevel(key);
    }

    private static void holdPlayer(@Nullable ServerPlayer player, @Nullable Vec3 position) {
        if (player == null || position == null) return;
        player.setDeltaMovement(Vec3.ZERO);
        if (player.position().distanceToSqr(position) > 0.01)
            player.teleportTo(player.serverLevel(), position.x, position.y, position.z, player.getYRot(), player.getXRot());
    }

    private static void title(@Nullable ServerPlayer player, String main, String sub) {
        if (player == null) return;
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, 25, 5));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                Component.literal(main)));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                Component.literal(sub)));
    }

    public static String stateName(State state) {
        return switch (state) {
            case WAITING -> "等待加入";
            case READY -> "准备中";
            case COUNTDOWN -> "倒计时";
            case FIGHTING -> "战斗中";
            case ENDED -> "结束";
        };
    }

    /** 把大厅快照推给所有在线玩家（人数少，全量广播最简单也最不容易不同步）。 */
    public static void broadcast() {
        MinecraftServer running = server;
        if (running == null) return;
        for (ServerPlayer player : running.getPlayerList().getPlayers()) {
            sendSnapshot(player);
        }
    }

    /** Rebuild transient rooms from persisted, playable arenas, including after a restart. */
    private static void reconcile(MinecraftServer running) {
        server = running;
        ArenaStore store = ArenaStore.get(running);
        ROOMS.values().removeIf(room -> {
            ArenaStore.Arena arena = store.byName(room.arenaName);
            if (usable(running, arena)) return false;
            invalidate(running, room);
            return true;
        });
        for (ArenaStore.Arena arena : store.allArenas()) {
            if (!usable(running, arena)) continue;
            boolean exists = ROOMS.values().stream()
                    .anyMatch(room -> room.arenaName.equalsIgnoreCase(arena.name));
            if (!exists) {
                Room room = new Room(nextRoomId(arena.name), arena.name);
                ROOMS.put(room.id, room);
            }
        }
    }

    private static void invalidate(MinecraftServer running, Room room) {
        DuelManager.cancelArena(running, room.arenaName);
        for (UUID member : new ArrayList<>(room.joinOrder)) {
            LANDING.remove(member);
            ServerPlayer player = playerOf(running, member);
            if (player != null) player.sendSystemMessage(Component.literal(
                    "场地「" + room.arenaName + "」已删除或未就绪，已退出房间。"));
        }
        resetRoom(room);
    }

    private static boolean usable(MinecraftServer running, @Nullable ArenaStore.Arena arena) {
        return arena != null && arena.complete() && (arena.dimension == null
                || running.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                arena.dimension)) != null);
    }

    public static void sendSnapshot(ServerPlayer player) {
        MinecraftServer running = player.server;
        reconcile(running);
        List<RoomListPayload.RoomView> views = new ArrayList<>();
        for (Room room : ROOMS.values()) {
            UUID me = player.getUUID();
            boolean startable = me.equals(room.host) && allReady(room)
                    && (room.state == State.WAITING || room.state == State.READY);
            int seconds = room.state == State.COUNTDOWN ? Math.max(0, (room.countdown + 19) / 20) : 0;
            views.add(new RoomListPayload.RoomView(room.id, room.arenaName,
                    nameOf(running, room.host), nameOf(running, room.red), nameOf(running, room.blue),
                    room.state.ordinal(), seconds, room.state == State.WAITING && !room.full()
                    && roomOf(me) == null && !DuelManager.inMatch(player), startable,
                    idOf(room.host), idOf(room.red), idOf(room.blue),
                    me.equals(room.red) ? teamOf(running, room, room.red) : List.of(),
                    me.equals(room.blue) ? teamOf(running, room, room.blue) : List.of(),
                    2, room.settings.capacity, room.settings.passwordProtected(),
                    RoomRules.arenaDisplayName(room.settings.displayName.isBlank() ? room.arenaName : room.settings.displayName),
                    visibleRoster(room, room.red, me), visibleRoster(room, room.blue, me),
                    room.ready.contains(me), room.ready.size(), room.settings.visibility, idOf(room.bot)));
        }
        if (!ServerPlayNetworking.canSend(player, RoomListPayload.TYPE)) return;
        ServerPlayNetworking.send(player, new RoomListPayload(views, roomIdOf(player.getUUID()),
                sideOf(player.getUUID()), ArenaStore.get(running).names(),
                List.copyOf(com.pkmoba.pokemon.ProfileStore.get(running).collection(player.getUUID()).favorites)));
    }

    /** 玩家 UUID → 倒计时里选定的出战落点（方案 §三.2）。 */
    private static final java.util.Map<UUID, Vec3> LANDING = new java.util.HashMap<>();

    private static boolean inCountdown(ServerPlayer player) {
        Room room = roomOf(player.getUUID());
        return room != null && room.state == State.COUNTDOWN;
    }

    private static void landing(ServerPlayer player, String arg) {
        Room room = roomOf(player.getUUID());
        if (room == null || room.state != State.COUNTDOWN) {
            player.sendSystemMessage(Component.literal("只有在开局倒计时期间才能选落点。"));
            return;
        }
        String[] parts = arg == null ? new String[0] : arg.trim().split("\\s+");
        if (parts.length != 3) return;
        Vec3 point;
        try {
            point = new Vec3(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]));
        } catch (NumberFormatException e) {
            return;
        }
        ArenaStore.Arena arena = ArenaStore.get(player.server).byName(room.arenaName);
        boolean red = player.getUUID().equals(room.red);
        if (arena == null || !arena.insideSwitchZone(point, red)) {
            player.sendSystemMessage(Component.literal(arena == null
                    ? "场地数据不可用，无法设置落点。"
                    : "落点必须在己方四点换宠区内；场地可正常开房，未配置己方换宠区时无法在战中换宠。"));
            return;
        }
        LANDING.put(player.getUUID(), point);
        player.sendSystemMessage(Component.literal("已选择出战落点 "
                + (int) point.x + ", " + (int) point.y + ", " + (int) point.z
                + "（倒计时结束从这里上场）"));
    }

    /** 倒计时结束：把宝可梦挪到玩家选定的落点（没选就留在点位组的宝可梦点）。 */
    private static void applyLanding(ServerPlayer player) {
        if (player == null) return;
        Vec3 point = LANDING.remove(player.getUUID());
        if (point == null) return;
        com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity =
                com.pkmoba.charizard.CharizardManager.getControlled(player);
        if (entity == null) return;
        entity.getNavigation().stop();
        entity.setDeltaMovement(Vec3.ZERO);
        entity.teleportTo(point.x, point.y, point.z);
    }

    private static List<String> teamOf(MinecraftServer running, Room room, UUID id) {
        if (id == null) return List.of();
        if (id.equals(room.bot)) return room.botRoster;
        return List.copyOf(com.pkmoba.pokemon.ProfileStore.get(running).collection(id).team);
    }

    private static List<String> visibleRoster(Room room, UUID id, UUID viewer) {
        return RosterVisibility.filter(rosterOf(room, id), viewer.equals(id), room.settings.visibility,
                room.state == State.FIGHTING || room.state == State.ENDED,
                room.revealed.getOrDefault(id, java.util.Set.of()));
    }

    /** Called when an entity actually deploys, never when merely selected. */
    public static void reveal(UUID owner, String species) {
        Room room = roomOf(owner);
        if (room == null || room.state != State.FIGHTING) return;
        if (room.revealed.computeIfAbsent(owner, ignored -> new java.util.HashSet<>()).add(species)) broadcast();
    }

    private static List<String> rosterOf(Room room, UUID id) {
        if (id == null) return List.of();
        if (id.equals(room.bot)) return room.botRoster.stream().limit(room.settings.capacity).toList();
        return List.copyOf(room.rosters.getOrDefault(id, List.of()));
    }

    private static List<String> defaultRoster(ServerPlayer player, Room room) {
        return com.pkmoba.pokemon.ProfileStore.get(player.server).collection(player.getUUID()).team.stream()
                .filter(id -> com.pkmoba.pokemon.SpeciesRegistry.parse(id) != null).limit(room.settings.capacity).toList();
    }

    private static boolean validRoster(ServerPlayer player, Room room) {
        try {
            List<String> roster = RoomRules.roster(room.rosters.getOrDefault(player.getUUID(), List.of()),
                    com.pkmoba.pokemon.ProfileStore.get(player.server).collection(player.getUUID()).team,
                    com.pkmoba.pokemon.SpeciesRegistry.ids(), room.settings.capacity);
            if (roster.isEmpty()) throw new IllegalArgumentException("empty roster");
            return true;
        } catch (IllegalArgumentException ex) {
            room.ready.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("请先选择合法的本场阵容（1 至 " + room.settings.capacity + " 只已拥有宝可梦）。"));
            return false;
        }
    }

    private static void cancelCountdown(Room room) {
        if (room.state == State.COUNTDOWN) {
            room.state = room.full() ? State.READY : State.WAITING;
            room.countdown = 0;
            for (UUID id : room.joinOrder) LANDING.remove(id);
        }
    }

    public static boolean collectionMutationAllowed(ServerPlayer player) {
        Room room = roomOf(player.getUUID());
        if (room != null && (room.state == State.COUNTDOWN || room.state == State.FIGHTING || room.state == State.ENDED)) {
            player.sendSystemMessage(Component.literal("开局倒计时或对局中不能修改或治疗收藏，请先取消准备。"));
            return false;
        }
        if (DuelManager.inMatch(player)) {
            player.sendSystemMessage(Component.literal("对局中不能修改或治疗收藏。"));
            return false;
        }
        return true;
    }

    public static void collectionChanged(ServerPlayer player) {
        Room room = roomOf(player.getUUID());
        if (room != null) {
            room.ready.remove(player.getUUID());
            List<String> owned = com.pkmoba.pokemon.ProfileStore.get(player.server).collection(player.getUUID()).team;
            room.rosters.put(player.getUUID(), room.rosters.getOrDefault(player.getUUID(), List.of()).stream()
                    .filter(owned::contains).limit(room.settings.capacity).toList());
            broadcast();
        }
    }

    private static void configure(ServerPlayer player, int roomId, String action, String arg) {
        Room room = roomOf(player.getUUID());
        if (room == null || room.id != roomId || room.state == State.FIGHTING || room.state == State.ENDED) return;
        if (!java.util.Set.of("SET_ROSTER", "TOGGLE_ROSTER", "FAVORITE").contains(action) && !player.getUUID().equals(room.host)) {
            player.sendSystemMessage(Component.literal("只有房主可以修改房间设置。"));
            return;
        }
        if (room.state == State.COUNTDOWN) {
            player.sendSystemMessage(Component.literal("请先取消准备，再编辑房间或阵容。"));
            return;
        }
        try {
            switch (action) {
                case "SET_VISIBILITY" -> room.settings.visibility = RosterVisibility.validate(Integer.parseInt(arg));
                case "FAVORITE" -> {
                    var species = com.pkmoba.pokemon.SpeciesRegistry.parse(arg);
                    if (species == null) throw new IllegalArgumentException("species");
                    var store = com.pkmoba.pokemon.ProfileStore.get(player.server);
                    var stars = store.collection(player.getUUID()).favorites;
                    if (!stars.remove(species.toString())) stars.add(species.toString());
                    store.setDirty();
                    sendSnapshot(player);
                    return;
                }
                case "TOGGLE_ROSTER" -> {
                    var species = com.pkmoba.pokemon.SpeciesRegistry.parse(arg);
                    if (species == null) throw new IllegalArgumentException("species");
                    String id = species.toString();
                    List<String> roster = new ArrayList<>(room.rosters.getOrDefault(player.getUUID(), List.of()));
                    if (!roster.remove(id)) {
                        if (roster.size() >= room.settings.capacity) {
                            player.sendSystemMessage(Component.literal("已达本场上限，请先取消一只。"));
                            sendSnapshot(player);
                            return;
                        }
                        var owned = com.pkmoba.pokemon.ProfileStore.get(player.server).collection(player.getUUID());
                        if (!owned.team.contains(id) && !com.pkmoba.pokemon.TeamService.addSpecies(player, id)) return;
                        roster.add(id);
                    }
                    room.rosters.put(player.getUUID(), List.copyOf(roster));
                    room.ready.remove(player.getUUID());
                    broadcast();
                    return;
                }
                case "SET_NAME" -> room.settings.displayName = RoomRules.displayName(arg);
                case "SET_CAPACITY" -> {
                    room.settings.capacity = RoomRules.capacity(Integer.parseInt(arg));
                    room.rosters.replaceAll((id, roster) -> roster.stream().limit(room.settings.capacity).toList());
                }
                case "SET_PASSWORD" -> {
                    if (arg.length() > 32) throw new IllegalArgumentException("password length");
                    long now = player.server.getTickCount();
                    if (now < JOIN_ATTEMPTS.getOrDefault(player.getUUID(), 0L)) {
                        player.sendSystemMessage(Component.literal("请稍等后再修改密码。"));
                        return;
                    }
                    JOIN_ATTEMPTS.put(player.getUUID(), now + 40);
                    room.settings.passwordSalt = arg.isEmpty() ? "" : RoomRules.newSalt();
                    room.settings.passwordHash = arg.isEmpty() ? "" : RoomRules.passwordHash(arg, room.settings.passwordSalt);
                }
                case "SET_ROSTER" -> {
                    List<String> ids = new ArrayList<>();
                    for (String raw : RoomRules.parseRoster(arg)) {
                        var id = com.pkmoba.pokemon.SpeciesRegistry.parse(raw);
                        if (id == null) throw new IllegalArgumentException("species");
                        ids.add(id.toString());
                    }
                    room.rosters.put(player.getUUID(), RoomRules.roster(ids,
                            com.pkmoba.pokemon.ProfileStore.get(player.server).collection(player.getUUID()).team,
                            com.pkmoba.pokemon.SpeciesRegistry.ids(), room.settings.capacity));
                    room.ready.remove(player.getUUID());
                    broadcast();
                    return;
                }
                default -> { return; }
            }
            room.ready.clear();
            RoomSettingsStore.get(player.server).markDirtyAndSave();
            String summary = switch (action) {
                case "SET_NAME" -> "房名已更新";
                case "SET_CAPACITY" -> "本场携带上限改为 " + room.settings.capacity;
                case "SET_PASSWORD" -> room.settings.passwordProtected() ? "房间已设置密码" : "房间改为公开加入";
                default -> "房间规则已更新";
            };
            for (UUID member : room.joinOrder) {
                ServerPlayer recipient = playerOf(player.server, member);
                if (recipient != null) recipient.sendSystemMessage(Component.literal(summary + "，双方准备已取消。"));
            }
            broadcast();
        } catch (IllegalArgumentException ex) {
            player.sendSystemMessage(Component.literal("设置无效：请检查房名、密码、容量或本场阵容。"));
        }
    }

    private static String idOf(UUID id) {
        return id == null ? "" : id.toString();
    }

    private static int roomIdOf(UUID player) {
        Room room = roomOf(player);
        return room == null ? 0 : room.id;
    }

    private static String nameOf(MinecraftServer running, @Nullable UUID id) {
        if (id == null) return "";
        for (Room room : ROOMS.values()) if (id.equals(room.bot)) return BotDuelService.BOT_NAME;
        ServerPlayer player = running.getPlayerList().getPlayer(id);
        if (player != null) return player.getGameProfile().getName();
        return "";
    }

    /** 客户端操作总入口（服务端复核后执行）。 */
    public static void handle(ServerPlayer player, RoomActionPayload payload) {
        String action = payload.action() == null ? "" : payload.action();
        switch (action) {
            case "OPEN" -> open(player);
            case "CREATE" -> { if (player.hasPermissions(2)) create(player, payload.arg()); }
            case "JOIN" -> join(player, payload.roomId(), payload.arg());
            case "SET_NAME", "SET_PASSWORD", "SET_CAPACITY", "SET_ROSTER", "SET_VISIBILITY", "TOGGLE_ROSTER", "FAVORITE" -> configure(player, payload.roomId(), action, payload.arg());
            case "UNREADY", "CANCEL_COUNTDOWN" -> {
                Room room = roomOf(player.getUUID());
                if (room != null && room.state != State.FIGHTING && room.state != State.ENDED) {
                    cancelCountdown(room);
                    room.ready.remove(player.getUUID());
                    broadcast();
                }
            }
            case "LEAVE" -> leave(player);
            case "READY" -> ready(player);
            case "START" -> start(player);
            case "ADD_BOT" -> addBot(player, payload.roomId());
            case "REMOVE_BOT" -> removeBot(player, payload.roomId());
            case "SELECT" -> {
                if (!inCountdown(player)) {
                    player.sendSystemMessage(Component.literal("只有在开局倒计时期间才能选宝可梦。"));
                } else {
                    player.sendSystemMessage(Component.literal("请先取消准备，再编辑本场阵容与首发。"));
                }
            }
            case "LANDING" -> landing(player, payload.arg());
            default -> {
                PkmobaMod.LOGGER.warn("[pkmoba] 未知房间操作：{}", action);
                player.sendSystemMessage(Component.literal("未知的房间操作：" + action));
            }
        }
    }
}
