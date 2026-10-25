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

    /** 倒计时长度：5 秒。 */
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
        public State state = State.WAITING;
        public int countdown;
        public int endTicks;

        Room(int id, String arenaName) {
            this.id = id;
            this.arenaName = arenaName;
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
    private static int nextId = 1;
    private static MinecraftServer server;

    private RoomManager() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(RoomManager::tick);
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
        if (!arena.complete()) {
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
        Room room = new Room(nextId++, arena.name);
        ROOMS.put(room.id, room);
        player.sendSystemMessage(Component.literal("点位组「" + arena.name + "」已生成房间 #" + room.id
                + "（空房）。按 H 打开大厅，第一个加入的人成为房主。"));
        broadcast();
    }

    /** 加入房间：自动补空位（红先、蓝后）。 */
    public static void join(ServerPlayer player, int roomId) {
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
        if (room.state == State.FIGHTING || room.state == State.COUNTDOWN) {
            player.sendSystemMessage(Component.literal("对局已开始，不能退出房间。"));
            return;
        }
        removeMember(room, player.getUUID());
        player.sendSystemMessage(Component.literal("已退出房间 #" + room.id + "。"));
        broadcast();
    }

    /** 房主开始对战：双方都在才能开，进入 5 秒倒计时。 */
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
        ArenaStore.Arena arena = ArenaStore.get(player.server).byName(room.arenaName);
        if (arena == null || !arena.complete()) {
            player.sendSystemMessage(Component.literal("点位组不可用，无法开始。"));
            return;
        }
        ServerPlayer red = playerOf(player.server, room.red);
        ServerPlayer blue = playerOf(player.server, room.blue);
        if (red == null || blue == null) {
            player.sendSystemMessage(Component.literal("有一方玩家不在线。"));
            return;
        }
        if (!DuelManager.hasTeam(red) || !DuelManager.hasTeam(blue)) {
            player.sendSystemMessage(Component.literal("有一方还没有宝可梦队伍。"));
            return;
        }
        // 倒计时期间双方先各自站到己方玩家点。
        if (!teleportTo(red, arena, arena.redPlayer) || !teleportTo(blue, arena, arena.bluePlayer)) {
            player.sendSystemMessage(Component.literal("点位组所在维度不可用，无法开始。"));
            return;
        }
        room.state = State.COUNTDOWN;
        room.countdown = COUNTDOWN_TICKS;
        title(red, "对战即将开始", "5");
        title(blue, "对战即将开始", "5");
        broadcast();
    }

    // ------------------------------------------------------------------ 每 tick

    private static void tick(MinecraftServer running) {
        server = running;
        if (ROOMS.isEmpty()) return;
        boolean changed = false;
        List<Room> snapshot = new ArrayList<>(ROOMS.values());
        for (Room room : snapshot) {
            switch (room.state) {
                case COUNTDOWN -> {
                    room.countdown--;
                    int seconds = Math.max(0, (room.countdown + 19) / 20);
                    if (room.countdown % 20 == 0) {
                        ServerPlayer red = playerOf(running, room.red);
                        ServerPlayer blue = playerOf(running, room.blue);
                        if (seconds > 0) {
                            title(red, "对战即将开始", String.valueOf(seconds));
                            title(blue, "对战即将开始", String.valueOf(seconds));
                        }
                        changed = true;
                    }
                    if (room.countdown <= 0) {
                        beginMatch(running, room);
                        changed = true;
                    }
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
                        room.countdown = 0;
                        room.endTicks = 0;
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
        if (red == null || blue == null || arena == null) {
            room.state = State.WAITING;
            return;
        }
        if (!DuelManager.startRoom(red, blue, arena)) {
            room.state = State.READY;
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
        ROOMS.values().removeIf(room -> arenaName.trim().equalsIgnoreCase(room.arenaName));
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
        removeMember(room, player.getUUID());
        broadcast();
    }

    private static void removeMember(Room room, UUID player) {
        if (player.equals(room.red)) room.red = null;
        if (player.equals(room.blue)) room.blue = null;
        room.joinOrder.remove(player);
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
        room.joinOrder.clear();
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

    private static void title(@Nullable ServerPlayer player, String main, String sub) {
        if (player == null) return;
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

    public static void sendSnapshot(ServerPlayer player) {
        MinecraftServer running = player.server;
        List<RoomListPayload.RoomView> views = new ArrayList<>();
        for (Room room : ROOMS.values()) {
            UUID me = player.getUUID();
            boolean startable = me.equals(room.host) && room.full()
                    && (room.state == State.WAITING || room.state == State.READY);
            int seconds = room.state == State.COUNTDOWN ? Math.max(0, (room.countdown + 19) / 20) : 0;
            views.add(new RoomListPayload.RoomView(room.id, room.arenaName,
                    nameOf(running, room.host), nameOf(running, room.red), nameOf(running, room.blue),
                    room.state.ordinal(), seconds, !room.full(), startable,
                    idOf(room.host), idOf(room.red), idOf(room.blue)));
        }
        if (!ServerPlayNetworking.canSend(player, RoomListPayload.TYPE)) return;
        ServerPlayNetworking.send(player, new RoomListPayload(views, roomIdOf(player.getUUID()),
                sideOf(player.getUUID()), ArenaStore.get(running).names()));
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
        if (arena == null || !arena.insideBoundary(point)) {
            player.sendSystemMessage(Component.literal("落点必须在竞技场边界内。"));
            return;
        }
        LANDING.put(player.getUUID(), point);
        player.sendSystemMessage(Component.literal("已选择出战落点 "
                + (int) point.x + ", " + (int) point.y + ", " + (int) point.z
                + "（倒计时结束从这里上场）"));
    }

    /** 倒计时结束：把宝可梦挪到玩家选定的落点（没选就留在点位组的宝可梦点）。 */
    private static void applyLanding(ServerPlayer player) {
        Vec3 point = LANDING.remove(player.getUUID());
        if (point == null || player == null) return;
        com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity =
                com.pkmoba.charizard.CharizardManager.getControlled(player);
        if (entity == null) return;
        entity.getNavigation().stop();
        entity.setDeltaMovement(Vec3.ZERO);
        entity.teleportTo(point.x, point.y, point.z);
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
        ServerPlayer player = running.getPlayerList().getPlayer(id);
        if (player != null) return player.getGameProfile().getName();
        return "";
    }

    /** 客户端操作总入口（服务端复核后执行）。 */
    public static void handle(ServerPlayer player, RoomActionPayload payload) {
        String action = payload.action() == null ? "" : payload.action();
        switch (action) {
            case "OPEN" -> open(player);
            case "CREATE" -> create(player, payload.arg());
            case "JOIN" -> join(player, payload.roomId());
            case "LEAVE" -> leave(player);
            case "START" -> start(player);
            case "SELECT" -> {
                if (!inCountdown(player)) {
                    player.sendSystemMessage(Component.literal("只有在开局倒计时期间才能选宝可梦。"));
                } else if (!com.pkmoba.pokemon.TeamService.choose(player, payload.arg())) {
                    PkmobaMod.LOGGER.warn("[pkmoba] 倒计时选宝可梦失败：{}", payload.arg());
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
