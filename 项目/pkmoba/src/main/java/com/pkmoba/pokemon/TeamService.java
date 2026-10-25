package com.pkmoba.pokemon;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.PkmobaMod;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.network.StarterListPayload;
import com.pkmoba.network.TeamStatePayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 玩家的宝可梦队伍服务：选择、添加、上场、手动换人、死亡与复活。
 *
 * <p>队伍来源是 {@link PlayerProfile#team}；0.38 起一名玩家可以同时有多只受控宝可梦在场
 * （登记在 {@link CharizardManager#getActiveList}），其中"选中"的那只由
 * {@link CharizardManager#getControlled} 返回，技能/指令/HUD 都作用在它身上。
 * 0.31 起：开局只有选中的那一只，其他成员必须用 {@code /mo pokemon add} 或添加道具扩充。
 */
public final class TeamService {
    /** 队伍上限。 */
    public static final int MAX_TEAM = 6;
    /** {@code /mo remove all} 二次确认的有效期（tick）。 */
    private static final long CONFIRM_TICKS = 20L * 30L;
    /** 玩家 UUID → 确认过期时刻（服务器 tick）。 */
    private static final java.util.Map<UUID, Long> PENDING_REMOVE_ALL = new java.util.HashMap<>();

    private TeamService() {
    }

    /**
     * {@code /mo remove all}：只登记确认，{@link #CONFIRM_TICKS} 内输入
     * {@code /mo remove all confirm} 才真删。
     *
     * @return true 表示已进入等待确认状态
     */
    public static boolean requestRemoveAll(ServerPlayer player) {
        if (profile(player.server, player.getUUID()).team.isEmpty()) {
            player.sendSystemMessage(Component.literal("你没有任何宝可梦可以删除。"));
            return false;
        }
        long now = player.server.getTickCount();
        PENDING_REMOVE_ALL.put(player.getUUID(), now + CONFIRM_TICKS);
        player.sendSystemMessage(Component.literal("这会删除你全部宝可梦，"
                + (CONFIRM_TICKS / 20) + " 秒内输入 /mo remove all confirm 确认。"));
        return true;
    }

    /** 确认执行 {@code /mo remove all}；确认过期或没有确认请求时拒绝。 */
    public static boolean confirmRemoveAll(ServerPlayer player) {
        Long expiry = PENDING_REMOVE_ALL.remove(player.getUUID());
        if (expiry == null) {
            player.sendSystemMessage(Component.literal("没有等待确认的删除操作，请先执行 /mo remove all。"));
            return false;
        }
        if (player.server.getTickCount() > expiry) {
            player.sendSystemMessage(Component.literal("确认已过期，请重新执行 /mo remove all。"));
            return false;
        }
        removeAll(player);
        return true;
    }

    /** 显式取消等待中的确认。 */
    public static void cancelPendingRemoveAll(ServerPlayer player) {
        if (PENDING_REMOVE_ALL.remove(player.getUUID()) != null) {
            player.sendSystemMessage(Component.literal("已取消删除全部宝可梦。"));
        }
    }

    public static PlayerProfile profile(MinecraftServer server, UUID playerId) {
        return ProfileStore.get(server).profile(playerId);
    }

    public static void sendStarterList(ServerPlayer player) {
        sendList(player, "PICK");
    }

    public static void sendAddList(ServerPlayer player) {
        sendList(player, "ADD");
    }

    /** 打开选择界面：还没选过就按"选初始"打开，已经有队伍就按"添加"打开。 */
    public static void openPicker(ServerPlayer player) {
        if (player == null) return;
        if (profile(player.server, player.getUUID()).hasStarter()) {
            sendAddList(player);
        } else {
            sendStarterList(player);
        }
    }

    private static void sendList(ServerPlayer player, String mode) {
        PlayerProfile profile = profile(player.server, player.getUUID());
        if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
                .canSend(player, StarterListPayload.TYPE)) {
            return;
        }
        List<String> species = new ArrayList<>();
        for (SpeciesRegistry.Entry entry : SpeciesRegistry.all()) {
            species.add(entry.id().toString());
        }
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new StarterListPayload(mode,
                        profile.activeSpecies() == null ? "" : profile.activeSpecies(),
                        species, new ArrayList<>(profile.team), new ArrayList<>(profile.fainted)));
    }

    /**
     * 获取界面里"再点一次已拥有的宝可梦"：删除这只，并把最新列表回推让界面原地刷新。
     *
     * <p>删除语义完全复用 {@link #removeSpecies(ServerPlayer, String)}：
     * 如果删的正是场上那只，会先收回并清理受控实体，再删记录。
     */
    public static boolean deleteFromPicker(ServerPlayer player, String rawSpecies) {
        if (player == null) return false;
        boolean removed = removeSpecies(player, rawSpecies);
        // 无论成功与否都刷新一次：成功要更新"已拥有"标记，失败要清掉客户端的过期状态
        sendAddList(player);
        return removed;
    }

    /**
     * {@code /mo pokemon list}：列出队伍，标出当前上场与已阵亡的成员。
     */
    public static void listTeam(ServerPlayer player) {
        if (player == null) return;
        PlayerProfile profile = profile(player.server, player.getUUID());
        if (profile.team.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "你的队伍是空的，用 /mo pokemon 选择初始宝可梦。"));
            return;
        }
        String active = profile.activeSpecies();
        StringBuilder text = new StringBuilder("你的队伍（" + profile.team.size() + "/" + MAX_TEAM + "）：");
        for (int i = 0; i < profile.team.size(); i++) {
            String species = profile.team.get(i);
            ResourceLocation id = ResourceLocation.tryParse(species);
            String name = id == null ? species : SpeciesRegistry.displayName(id);
            if (i > 0) text.append("、");
            text.append(i + 1).append(".").append(name);
            if (species.equals(active)) text.append("（上场）");
            if (profile.fainted.contains(species)) text.append("（已倒下）");
        }
        player.sendSystemMessage(Component.literal(text.toString()));
        if (!profile.hasUsableLeft()) {
            player.sendSystemMessage(Component.literal("全部宝可梦都已倒下，用 /mo revive 或治疗机复活。"));
        }
    }

    /** 下发给客户端自己的队伍状态（HUD 左侧头像/圆点/倒计时）。 */
    public static void sendTeamState(ServerPlayer player) {
        if (player == null) return;
        if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
                .canSend(player, TeamStatePayload.TYPE)) {
            return;
        }
        PlayerProfile profile = profile(player.server, player.getUUID());
        boolean summoned = CharizardManager.getControlled(player) != null;
        String next = switchTarget(player);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new TeamStatePayload(new ArrayList<>(profile.team), new ArrayList<>(profile.fainted),
                        profile.activeSpecies() == null ? "" : profile.activeSpecies(),
                        summoned, com.pkmoba.duel.DuelManager.pendingTicksFor(player),
                        next == null ? "" : next));
    }

    /**
     * 选择：队伍为空时建队（只有这一只）；已建队时只在队伍内切换上场。
     *
     * @return 成功返回 true
     */
    public static boolean choose(ServerPlayer player, String rawSpecies) {
        ResourceLocation id = SpeciesRegistry.parse(rawSpecies);
        if (id == null) {
            player.sendSystemMessage(Component.literal("无法识别的宝可梦：" + rawSpecies));
            return false;
        }
        MinecraftServer server = player.server;
        PlayerProfile profile = profile(server, player.getUUID());
        String species = id.toString();

        if (profile.fainted.contains(species)) {
            player.sendSystemMessage(Component.literal(
                    SpeciesRegistry.displayName(id) + " 已倒下，用 /mo revive 或治疗机复活后才能上场。"));
            return false;
        }

        if (profile.team.isEmpty()) {
            profile.team.add(species);
            profile.activeIndex = 0;
        } else {
            int index = profile.team.indexOf(species);
            if (index < 0) {
                player.sendSystemMessage(Component.literal(
                        "你还没有 " + SpeciesRegistry.displayName(id) + "，用 /mo pokemon add "
                                + id.getPath() + " 添加。"));
                return false;
            }
            profile.activeIndex = index;
        }
        ProfileStore.get(server).setDirty();
        player.sendSystemMessage(Component.literal("当前上场宝可梦：" + SpeciesRegistry.displayName(id)));
        sendTeamState(player);
        return true;
    }

    /** 往队伍里添加一只宝可梦。 */
    public static boolean addSpecies(ServerPlayer player, String rawSpecies) {
        ResourceLocation id = SpeciesRegistry.parse(rawSpecies);
        if (id == null) {
            player.sendSystemMessage(Component.literal("无法识别的宝可梦：" + rawSpecies));
            return false;
        }
        PlayerProfile profile = profile(player.server, player.getUUID());
        String species = id.toString();
        if (profile.team.contains(species)) {
            player.sendSystemMessage(Component.literal("你已经拥有 " + SpeciesRegistry.displayName(id) + "。"));
            return false;
        }
        if (profile.team.size() >= MAX_TEAM) {
            player.sendSystemMessage(Component.literal("队伍已满（上限 " + MAX_TEAM + " 只）。"));
            return false;
        }
        profile.team.add(species);
        profile.fainted.remove(species);
        ProfileStore.get(player.server).setDirty();
        player.sendSystemMessage(Component.literal("已添加宝可梦：" + SpeciesRegistry.displayName(id)
                + "（队伍共 " + profile.team.size() + " 只）"));
        sendTeamState(player);
        return true;
    }

    /**
     * 在给定位置放当前上场的那只（对局入场用）。
     *
     * <p>0.38：入场前先把该玩家自由模式下养着的在场宝可梦全部收回，
     * 避免它们以"在场受控"身份混进 1v1 对局；对局流程本身不受影响。
     */
    @Nullable
    public static PokemonEntity spawnActive(ServerPlayer player, Vec3 pos) {
        CharizardManager.discardAllControlled(player);
        PlayerProfile profile = profile(player.server, player.getUUID());
        String species = profile.activeSpecies();
        if (species == null) {
            player.sendSystemMessage(Component.literal(
                    "你还没有选择宝可梦，按物品栏里的「宝可梦选择器」或输入 /mo pokemon 选择。"));
            return null;
        }
        if (!profile.fainted.contains(species)) {
            return spawnSpecies(player, species, pos);
        }
        // 当前上场那只已经倒下时，自动顺延到下一只可用的
        String next = profile.nextUsable();
        if (next == null) {
            player.sendSystemMessage(Component.literal("你的宝可梦全部倒下了，请先用 /mo revive 或治疗机复活。"));
            return null;
        }
        ProfileStore.get(player.server).setDirty();
        return spawnSpecies(player, next, pos);
    }

    /** 直接生成指定物种并登记为受控实体（内部用，调用方自行保证合法）。 */
    @Nullable
    public static PokemonEntity spawnSpecies(ServerPlayer player, String species, Vec3 pos) {
        ResourceLocation id = ResourceLocation.tryParse(species);
        if (id == null || !SpeciesRegistry.contains(id)) return null;
        // 0.38：不再"先清掉旧实体"。多只可以同时在场，新召唤的这只追加登记并设为选中，
        // 之前那只继续留在场上按自己的状态行动（C 键可以再收回来）。
        ServerLevel level = player.serverLevel();
        PokemonEntity entity = CharizardManager.spawnMatchEntity(level, pos, id);
        if (entity == null) {
            player.sendSystemMessage(Component.literal("宝可梦生成失败，请换个位置再试。"));
            sendTeamState(player);
            return null;
        }
        com.pkmoba.combat.RecallBeam.sendOut(level, entity);
        CharizardManager.registerControlled(player.getUUID(), entity);
        CharizardManager.broadcastSync(player.server);
        sendTeamState(player);
        return entity;
    }

    /**
     * 按 C 键的完整语义（0.38）：
     * <ol>
     *   <li>场上有多只宝可梦 → 只在它们之间轮换"选中"，<b>不收回</b>任何一只</li>
     *   <li>场上只有一只 → 收回它（不记阵亡）</li>
     *   <li>场上没有 → 只把"准备上场"的那只切到下一只可用，<b>不会自动出现</b></li>
     * </ol>
     * 真正把宝可梦放出来由 X 键（{@link #summonAt}）在准星位置完成。
     *
     * @return 多只轮换时返回新的选中实体；其余情况返回 null
     */
    @Nullable
    public static PokemonEntity cycle(ServerPlayer player, Vec3 pos) {
        // 多只同时在场：C 键只在"当前操控的宝可梦"之间切换，不收回任何一只。
        // getActiveList 内部已剔除死亡实体，这里再兜一层：万一选中指针还指着尸体，先把它清掉。
        List<PokemonEntity> field = CharizardManager.getActiveList(player);
        if (field.isEmpty()) {
            PokemonEntity stale = CharizardManager.getControlled(player);
            if (stale != null && !stale.isAlive()) {
                markDead(player, stale);
                field = CharizardManager.getActiveList(player);
            }
        }
        if (field.size() > 1) {
            PokemonEntity next = CharizardManager.selectNext(player);
            if (next != null) {
                syncActiveSpeciesTo(player, next);
                sendTeamState(player);
                player.sendSystemMessage(Component.literal("已切换到 " + display(speciesOf(next))
                        + "（场上共 " + field.size() + " 只，再按 C 继续切换）"));
            }
            return next;
        }
        if (recall(player)) return null;
        PlayerProfile profile = profile(player.server, player.getUUID());
        if (profile.team.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "你还没有宝可梦，用「PKMOBA 获取宝可梦」道具或 /mo pokemon add 先拿一只。"));
            return null;
        }
        String target = advanceSelection(player);
        if (target == null) {
            player.sendSystemMessage(Component.literal(noSwitchReason(profile)));
            return null;
        }
        sendTeamState(player);
        player.sendSystemMessage(Component.literal("已切换到 " + display(target)
                + "（" + (profile.activeIndex + 1) + "/" + profile.team.size()
                + "），按 X 键在准星位置召唤。"));
        return null;
    }

    /**
     * 按 <b>X</b> 会召唤出来的那一只：当前上场那只还有效就是它，否则顺延到下一只可用的。
     *
     * <p>只给 X 召唤与"备战状态展示"用，<b>不参与</b> C 键的切换计算。
     *
     * @return 物种 id；没有可用的返回 null
     */
    @Nullable
    public static String summonTarget(ServerPlayer player) {
        PlayerProfile profile = profile(player.server, player.getUUID());
        if (profile.team.isEmpty()) return null;
        String active = profile.activeSpecies();
        if (active != null && !profile.fainted.contains(active)) return active;
        return profile.peekNextUsable();
    }

    /**
     * 按 <b>C</b> 会切换过去的那一只：永远是队伍里"别的"一只可用成员。
     *
     * <p>之前这里错用了 {@code previewNext}（"按 X 会召唤谁"），导致收回后再按 C
     * 算出来的还是同一只、切不动。切换与预览必须用不同的口径。
     *
     * @return 物种 id；没有其他可用成员时返回 null
     */
    @Nullable
    public static String switchTarget(ServerPlayer player) {
        PlayerProfile profile = profile(player.server, player.getUUID());
        return profile.peekNextUsable(species -> recallLocked(player, species));
    }

    // ---------------------------------------------------------------- 主动收回锁（方案 §四.2）

    /** 玩家 UUID → (物种 id → 解锁的游戏刻)。主动收回后 3 秒内不能再选同一只。 */
    private static final java.util.Map<java.util.UUID, java.util.Map<String, Long>> RECALL_LOCK =
            new java.util.HashMap<>();

    /** 主动收回后上锁；死亡换宠不受限（那是强制流程，不是玩家的选择）。 */
    public static void lockRecalled(ServerPlayer player, String species) {
        if (player == null || species == null || species.isEmpty()) return;
        RECALL_LOCK.computeIfAbsent(player.getUUID(), k -> new java.util.HashMap<>())
                .put(species, (long) player.server.getTickCount() + 20L * 3);
    }

    public static boolean recallLocked(ServerPlayer player, String species) {
        if (player == null || species == null) return false;
        java.util.Map<String, Long> map = RECALL_LOCK.get(player.getUUID());
        if (map == null) return false;
        Long until = map.get(species);
        if (until == null) return false;
        if (player.server.getTickCount() >= until) {
            map.remove(species);
            return false;
        }
        return true;
    }

    /**
     * 把上场下标落到 {@link #switchTarget} 上（只改选择，不生成实体）。
     *
     * @return 切换后的物种 id；没有其他可用成员时返回 null
     */
    @Nullable
    public static String advanceSelection(ServerPlayer player) {
        String target = switchTarget(player);
        if (target == null) return null;
        PlayerProfile profile = profile(player.server, player.getUUID());
        int index = profile.team.indexOf(target);
        if (index >= 0 && profile.activeIndex != index) {
            profile.activeIndex = index;
            ProfileStore.get(player.server).setDirty();
        }
        return target;
    }

    /** 切不动时按当前队伍情况给一句准确的原因。 */
    private static String noSwitchReason(PlayerProfile profile) {
        if (profile.team.isEmpty()) {
            return "你还没有宝可梦，用「PKMOBA 获取宝可梦」道具或 /mo pokemon add 先拿一只。";
        }
        if (!profile.hasUsableLeft()) {
            return "你的宝可梦全部倒下了，用 /mo revive 或治疗机复活。";
        }
        if (profile.team.size() == 1) {
            return "队伍里只有 " + display(profile.team.get(0)) + " 一只，按 X 键召唤它。";
        }
        return "没有其它可切换的宝可梦了，按 X 键召唤 " + display(profile.team.get(profile.activeIndex)) + "。";
    }

    /** 物种 id → 中文显示名（解析失败时退回原字符串）。 */
    private static String display(String species) {
        ResourceLocation id = ResourceLocation.tryParse(species);
        return id == null ? species : SpeciesRegistry.displayName(id);
    }

    /**
     * 删除玩家拥有的某只宝可梦。
     *
     * <p>如果删的正是场上那只，先走 {@link #recall} 把实体安全清掉再删记录，
     * 避免留下"记录没了但实体还在"的孤儿。
     *
     * @return 真的删掉了返回 true
     */
    public static boolean removeSpecies(ServerPlayer player, String rawSpecies) {
        ResourceLocation id = SpeciesRegistry.parse(rawSpecies);
        if (id == null) {
            player.sendSystemMessage(Component.literal("无法识别的宝可梦：" + rawSpecies));
            return false;
        }
        PlayerProfile profile = profile(player.server, player.getUUID());
        String species = id.toString();
        if (!profile.team.contains(species)) {
            player.sendSystemMessage(Component.literal(
                    "你没有 " + SpeciesRegistry.displayName(id) + "，没有删除任何东西。"));
            return false;
        }
        String name = SpeciesRegistry.displayName(id);
        // 删掉的物种正好有一只留在场上：只收回那一只，避免留下没人管的孤儿实体
        PokemonEntity field = CharizardManager.findControlled(player, id);
        if (field != null) {
            CharizardManager.discardControlled(player, field);
        }
        profile.removeSpecies(species);
        ProfileStore.get(player.server).setDirty();
        CharizardManager.broadcastSync(player.server);
        sendTeamState(player);
        player.sendSystemMessage(Component.literal("已删除 " + name + "，队伍还剩 "
                + profile.team.size() + " 只。"));
        return true;
    }

    /** 删除全部宝可梦（含场上实体）。调用方负责二次确认。 */
    public static int removeAll(ServerPlayer player) {
        PlayerProfile profile = profile(player.server, player.getUUID());
        int count = profile.team.size();
        if (count == 0) {
            player.sendSystemMessage(Component.literal("你没有任何宝可梦可以删除。"));
            return 0;
        }
        CharizardManager.discardAllControlled(player);
        profile.team.clear();
        profile.fainted.clear();
        profile.activeIndex = 0;
        ProfileStore.get(player.server).setDirty();
        CharizardManager.broadcastSync(player.server);
        sendTeamState(player);
        player.sendSystemMessage(Component.literal("已删除全部 " + count + " 只宝可梦。"));
        return count;
    }

    /**
     * 收回场上宝可梦（<b>不</b>记为阵亡）。
     *
     * @return 场上原本有宝可梦并被收回返回 true
     */
    public static boolean recall(ServerPlayer player) {
        PokemonEntity current = CharizardManager.getControlled(player);
        if (current == null) return false;
        String species = speciesOf(current);
        boolean alive = current.isAlive();
        if (alive) lockRecalled(player, species);
        PlayerProfile profile = profile(player.server, player.getUUID());
        if (!alive) profile.markFainted(species);
        // 0.38：只收回"选中的这一只"；同玩家场上其它宝可梦继续保留、各自行动。
        CharizardManager.discardControlled(player);
        // 选中已经自动落到剩下的某一只上：把队伍的"上场"同步过去，别让 HUD 还显示刚收回的那只。
        syncActiveSpeciesTo(player, CharizardManager.getControlled(player));
        ProfileStore.get(player.server).setDirty();
        sendTeamState(player);
        ResourceLocation id = ResourceLocation.tryParse(species);
        String name = id == null ? species : SpeciesRegistry.displayName(id);
        // 只有主动收回才提示；已经倒下的那只静默清理，避免刷出"已经倒下"的重复消息
        if (current.isAlive()) {
            player.sendSystemMessage(Component.literal("已收回 " + name + "。"));
        }
        return true;
    }

    private static int countUsable(PlayerProfile profile) {
        int count = 0;
        for (String id : profile.team) if (!profile.fainted.contains(id)) count++;
        return count;
    }

    /** 队伍里还有几只没倒下的（对局换人提示用）。 */
    public static int usableCount(ServerPlayer player) {
        return countUsable(profile(player.server, player.getUUID()));
    }

    /**
     * 手动换下一只：当前这只还活着就只撤下（不记为阵亡），倒下则已被标记过。
     *
     * @return 换成功返回新实体；没有可上场的返回 null
     */
    @Nullable
    public static PokemonEntity switchNext(ServerPlayer player, Vec3 pos) {
        return cycle(player, pos);
    }

    /**
     * X 键召唤：把"准备上场的那只"放到准星指向的地面点。
     *
     * <p>0.38 起允许不收回场上的其它宝可梦就直接召唤新的：多只同时在场、各自独立行动。
     * 如果这个物种已经有一只留在场上，则把它挪到目标点并设为选中（不重复生成）。
     */
    @Nullable
    public static PokemonEntity summonAt(ServerPlayer player, Vec3 target) {
        // 0.38 用户要求"无论多远都能放置"：不再做玩法距离限制，也不再要求先收回。
        // 只留 256 格保险上限（不是玩法限制）：再远就落在未加载区块里，宝可梦可能掉进虚空/窒息，
        // 而且上帝视角的区块覆盖（本体锚点 view-distance ≈160 格）也看不到它。
        double maxDistance = 256.0;
        Vec3 eye = player.getEyePosition();
        if (eye.distanceTo(target) > maxDistance) {
            player.sendSystemMessage(Component.literal("太远了：召唤位置不能超过 " + (int) maxDistance
                    + " 格（保险上限，防止落进未加载区块）。"));
            return null;
        }
        ServerLevel level = player.serverLevel();
        Vec3 safe = findGround(level, target);
        if (safe == null) {
            player.sendSystemMessage(Component.literal("那里没有安全的地面，请换个位置。"));
            return null;
        }
        PlayerProfile profile = profile(player.server, player.getUUID());
        String species = profile.activeSpecies();
        if (species == null) {
            player.sendSystemMessage(Component.literal("你还没有选择宝可梦，先按「宝可梦选择器」选择。"));
            return null;
        }
        if (profile.fainted.contains(species)) {
            String next = profile.nextUsable();
            if (next == null) {
                player.sendSystemMessage(Component.literal("你的宝可梦全部倒下了，请先用 /mo revive 或治疗机复活。"));
                return null;
            }
            species = next;
            profile.activeIndex = profile.team.indexOf(species);
            ProfileStore.get(player.server).setDirty();
        }
        ResourceLocation speciesId = ResourceLocation.tryParse(species);
        PokemonEntity existing = CharizardManager.findControlled(player, speciesId);
        if (existing != null && existing.level() != level) {
            // 那只留在别的维度里，带不过来：先清掉登记再重新生成一只
            CharizardManager.discardControlled(player, existing);
            existing = null;
        }
        if (existing != null) {
            // 这个物种已经在场上：挪过去而不是再生成一只，并设为当前操控的那只。
            existing.getNavigation().stop();
            existing.setDeltaMovement(Vec3.ZERO);
            existing.teleportTo(safe.x, safe.y, safe.z);
            CharizardManager.select(player, existing);
            // 挪过去的那只成了新的"选中"，队伍的 activeIndex 要跟上（HUD/X 召唤/技能都看它）
            syncActiveSpeciesTo(player, existing);
            CharizardManager.broadcastSync(player.server);
            sendTeamState(player);
            player.sendSystemMessage(Component.literal("已把 " + display(species) + " 移到该位置。"));
            com.pkmoba.duel.DuelManager.notifySummoned(player, existing);
            return existing;
        }
        PokemonEntity entity = spawnSpecies(player, species, safe);
        if (entity != null) {
            com.pkmoba.duel.DuelManager.notifySummoned(player, entity);
        }
        return entity;
    }

    /**
     * 选点召唤入口（{@code /mo summon [宝可梦]} 上膛后右键点地）：按<b>指定物种</b>落点。
     *
     * <p>0.38：不再走旧版"单例训练实体"（{@link CharizardManager#spawn}），而是并进多只受控登记：
     * 该物种已经在场就挪过去并设为选中，否则新生成一只追加登记。
     *
     * @return 成功返回实体；物种非法/落点不安全/生成被取消返回 null
     */
    @Nullable
    public static PokemonEntity summonSpeciesAt(ServerPlayer player, ResourceLocation speciesId, Vec3 target) {
        if (speciesId == null) return null;
        // 与 X 召唤同一套 256 格保险上限（不是玩法限制）：
        // 再远会落在未加载区块里，宝可梦可能掉进虚空/窒息，上帝视角的区块覆盖也看不到它。
        if (player.getEyePosition().distanceTo(target) > 256.0) {
            player.sendSystemMessage(Component.literal(
                    "太远了：召唤位置不能超过 256 格（保险上限，防止落进未加载区块）。"));
            return null;
        }
        ServerLevel level = player.serverLevel();
        Vec3 safe = findGround(level, target);
        if (safe == null) {
            player.sendSystemMessage(Component.literal("那里没有安全的地面，请换个位置。"));
            return null;
        }
        PokemonEntity existing = CharizardManager.findControlled(player, speciesId);
        if (existing != null && existing.level() != level) {
            // 那只留在别的维度里，带不过来：先清掉登记再重新生成一只
            CharizardManager.discardControlled(player, existing);
            existing = null;
        }
        if (existing != null) {
            // 这个物种已经在场上：挪过去而不是再生成一只，并设为当前操控的那只。
            existing.getNavigation().stop();
            existing.setDeltaMovement(Vec3.ZERO);
            existing.teleportTo(safe.x, safe.y, safe.z);
            CharizardManager.select(player, existing);
            syncActiveSpeciesTo(player, existing);
            CharizardManager.broadcastSync(player.server);
            sendTeamState(player);
            player.sendSystemMessage(Component.literal("已把 " + display(speciesId.toString()) + " 移到该位置。"));
            com.pkmoba.duel.DuelManager.notifySummoned(player, existing);
            return existing;
        }
        PokemonEntity entity = spawnSpecies(player, speciesId.toString(), safe);
        if (entity != null) {
            syncActiveSpeciesTo(player, entity);
            com.pkmoba.duel.DuelManager.notifySummoned(player, entity);
        }
        return entity;
    }

    @Nullable
    private static Vec3 findGround(ServerLevel level, Vec3 target) {
        // 从目标点往上抬 3 格再向下找第一个可站立位置，避免卡进方块
        Vec3 start = target.add(0, 3, 0);
        var mutable = net.minecraft.core.BlockPos.MutableBlockPos.containing(start);
        for (int i = 0; i < 12; i++) {
            var below = mutable.below();
            var state = level.getBlockState(below);
            if (!state.isAir() && state.isSolidRender(level, below)) {
                return new Vec3(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5);
            }
            mutable = below.mutable();
        }
        return null;
    }

    /** 复活：清空阵亡记录，不清队伍顺序。 */
    public static void revive(ServerPlayer player) {
        PlayerProfile profile = profile(player.server, player.getUUID());
        profile.reviveAll();
        ProfileStore.get(player.server).setDirty();
        player.sendSystemMessage(Component.literal("你的宝可梦已全部复活。"));
        sendTeamState(player);
    }

    public static boolean isActive(ServerPlayer player, PokemonEntity entity) {
        PokemonEntity controlled = CharizardManager.getControlled(player);
        return controlled != null && controlled == entity;
    }

    /** 记录实体阵亡并把它从控制表里摘掉（同玩家其它在场宝可梦不受影响）。 */
    public static void markDead(ServerPlayer player, PokemonEntity entity) {
        PlayerProfile profile = profile(player.server, player.getUUID());
        String deadSpecies = speciesOf(entity);
        if (profile.fainted.contains(deadSpecies) && !CharizardManager.getActiveList(player).contains(entity)) {
            // 同一只已经处理过（对局流程与 syncAll 兜底都会调到这里）：直接返回，别重复刷消息/发包。
            return;
        }
        profile.markFainted(deadSpecies);
        // 0.38：只摘掉倒下的这一只；如果它正好是选中的那只，选中会自动落到剩下的某一只上。
        CharizardManager.unregisterControlled(player, entity);
        // 原版宝可梦的召回效果：红光 + 红环 + 召回音效，播完再把实体移除（用户 m04110）。
        com.pkmoba.combat.RecallBeam.recall((net.minecraft.server.level.ServerLevel) entity.level(), entity);
        syncActiveSpeciesTo(player, CharizardManager.getControlled(player));
        // 0.39.4：场上已经没有活着的宝可梦、但队伍里还有能上的，就把"准备上场"的那只挪过去。
        // 以前这里停在倒下的那一只上：HUD 永远显示 0 血，按 C 也像是"切不动"。
        if (CharizardManager.getControlled(player) == null && profile.hasUsableLeft()) {
            String next = profile.nextUsable();
            if (next != null) {
                ProfileStore.get(player.server).setDirty();
                player.sendSystemMessage(Component.literal(
                        "已切换到 " + display(next) + "（按 X 键在准星位置召唤它上场）"));
            }
        }
        ProfileStore.get(player.server).setDirty();
        // 先补一帧 0 血量再发队伍状态，否则实体移除后 HUD 会停在最后一帧的血量
        com.pkmoba.combat.CharizardCombat.sendFaintedState(player,
                com.pkmoba.combat.CharizardCombat.maxHpFor(entity));
        sendTeamState(player);
        PkmobaMod.LOGGER.info("[pkmoba] {} 的宝可梦阵亡 species={} 剩余可用={}",
                player.getGameProfile().getName(), speciesOf(entity), profile.hasUsableLeft());
    }

    /**
     * 当前上场的那只是否已经阵亡（0.35：实体被移除后 HUD 仍要知道"它是死了"而不是"还活着"）。
     */
    public static boolean isActiveFainted(ServerPlayer player) {
        if (player == null) return false;
        PlayerProfile profile = profile(player.server, player.getUUID());
        String active = profile.activeSpecies();
        return active != null && profile.fainted.contains(active);
    }

    /**
     * 把队伍里的"上场 index"同步到给定实体对应的物种上。
     *
     * <p>多只同时在场时，C 轮换/收回/阵亡都会改变"选中的是哪只"，
     * 队伍数据里的 activeIndex 必须跟着走，否则 HUD、X 召唤、技能会看着旧的那只。
     */
    private static void syncActiveSpeciesTo(ServerPlayer player, @Nullable PokemonEntity entity) {
        if (entity == null) return;
        PlayerProfile profile = profile(player.server, player.getUUID());
        int index = profile.team.indexOf(speciesOf(entity));
        if (index >= 0 && profile.activeIndex != index) {
            profile.activeIndex = index;
            ProfileStore.get(player.server).setDirty();
        }
    }

    public static String speciesOf(@Nullable PokemonEntity entity) {
        if (entity == null) return "";
        return entity.getPokemon().getSpecies().getResourceIdentifier().toString();
    }

    public static List<String> teamOf(ServerPlayer player) {
        return profile(player.server, player.getUUID()).team;
    }
}
