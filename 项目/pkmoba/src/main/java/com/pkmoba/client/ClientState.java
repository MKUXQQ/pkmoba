package com.pkmoba.client;

import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 客户端共享状态：一部分由服务端下发（技能状态），一部分是本地观测（点击点）。
 */
public final class ClientState {
    private static int charizardId = -1;
    private static boolean skillStateReady;
    public static boolean isSkillStateReady() { return skillStateReady; }
    private static boolean placementArmed = false;

    @Nullable
    private static Vec3 clickedPoint;
    @Nullable
    private static Vec3 serverTarget;
    private static boolean serverAccepted = false;

    // ---- 技能状态（服务端同步）----
    private static float hp = 100.0F;
    private static float maxHp = 100.0F;
    private static float points = 10.0F;
    private static int cdQ, cdW, cdE, cdR;
    private static int channel = 0;
    private static float charge = 0.0F;
    /**
     * 最近一次 channel 变成非 0 的时刻（{@link System#nanoTime()}）；0 表示当前不在引导。
     *
     * <p>为什么要看门狗：channel 完全由服务端每 tick 的 SkillStatePayload 驱动，正常结束
     * 会推 0。但如果结束那几帧的同步没到（切宠、重连、服务端异常收尾），客户端会一直
     * 记住旧的 channel，表现就是"引导早就结束了，却放不出技能、人也动不了"——这正是
     * E 冲浪卡死那条反馈的客户端侧症状。服务端最长的引导只有 80t（4s），超过
     * {@link #CHANNEL_MAX_NANOS} 还没归零就按"结束包丢了"强制自愈，绝不把玩家锁住。
     */
    private static long channelSinceNanos;
    /** 任何服务端引导都不可能超过 4s，8s 是安全上限。 */
    private static final long CHANNEL_MAX_NANOS = 8_000_000_000L;

    // ---- 切宠宽限期（0.38.3 修 UI 双闪）----
    /** 旧数值继续显示并淡出的时长；0.3s 足够覆盖 TeamStatePayload → SkillStatePayload 的往返。 */
    private static final long SYNC_GRACE_NANOS = 300_000_000L;
    /** 宽限期结束时刻（{@link System#nanoTime()}）；0 表示不在宽限期。 */
    private static long graceUntilNanos;
    /** {@link #clearCombatState()} 之后还没收到属于新实体的 SkillStatePayload。 */
    private static boolean awaitingSync;
    /** 距场地边界距离：正数在界内，负数已出界。 */
    private static float arenaDist = 999.0F;
    private static boolean hasArena = false;
    private static float arenaX, arenaY, arenaZ, arenaRadius = 48.0F;

    // ---- 对局对手（服务端每 tick 下发的真实血量）----
    private static boolean opponentActive = false;
    private static float opponentHp = 0.0F;
    private static float opponentMaxHp = 0.0F;
    private static String opponentName = "";
    private static String opponentPokemonName = "";
    private static String opponentSpecies = "";
    private static java.util.UUID opponentOwnerId;
    private static String opponentOwnerName = "";
    private static int opponentTeamRemaining;

    // ---- 自己的队伍（服务端同步，用于 HUD 左侧模块）----
    private static java.util.List<String> team = java.util.List.of();
    private static java.util.List<String> teamFainted = java.util.List.of();
    private static String teamActive = "";
    private static boolean teamSummoned = false;
    private static int teamPendingTicks = 0;
    private static String teamNext = "";

    // ---- R 大字弹道（客户端渲染用）----
    private static boolean hasGlyph = false;
    private static Vec3 glyphPos = Vec3.ZERO;
    private static Vec3 glyphDir = new Vec3(0, 0, 1);
    private static double glyphSize = 3.5;

    private ClientState() {
    }

    public static int getCharizardId() {
        return charizardId;
    }

    public static void setCharizardId(int id) {
        if (charizardId != id) {
            // 0.38.3：换成另一个实体（新召唤、收回、阵亡后 id 变 -1）时整块清空战斗数据。
            // 以前 id<0 会故意保留 maxHp，切人物种时旧上限就留在了新界面上（"血条不同步"）。
            // 现在清成 maxHp=0 表示"未知"，HUD 画"同步中"；真正阵亡时服务端会补发一帧
            // 0 血量 + 设计上限（CharizardCombat.sendFaintedState），届时 HUD 又显示 0/上限。
            clearCombatState();
            clickedPoint = null;
            serverTarget = null;
        }
        charizardId = id;
        if (id < 0) {
            clickedPoint = null;
            serverTarget = null;
        }
    }

    /**
     * 标记"当前实体数据作废"，进入切宠宽限期。
     *
     * <p>0.38.3 起不再立刻清零：切换宝可梦时 {@code TeamStatePayload} 先到、新实体的
     * {@code SkillStatePayload} 后到，中间这几十毫秒里旧数值保留并淡出（见 {@link #getCombatValueAlpha()}），
     * 宽限期过后仍没收到新数据才由 HUD 显示"同步中"（见 {@link #isAwaitingSync()}）。
     * 这样同一帧不会出现"先清空、下一包再填回"的双闪。
     */
    public static void clearCombatState() {
        // 0.38.3：不再"立刻清零、等下一包再填回来" —— 那正是切宠时 HUD 一闪一闪的根源。
        // 现在保留上一帧数值，由 HUD 读 getCombatValueAlpha() 做淡出；宽限期（300ms）过后
        // 若新数据还没到，isAwaitingSync() 为真，HUD 才改画"同步中"。
        skillStateReady = false;
        awaitingSync = true;
        graceUntilNanos = System.nanoTime() + SYNC_GRACE_NANOS;
        // 施放中的瞬时状态必须清掉，否则新宝可梦会继承上一只的引导条 / 大字弹道。
        channel = 0;
        channelSinceNanos = 0L;
        charge = 0.0F;
        hasGlyph = false;
    }

    /** 切宠宽限期内（旧数值仍在显示、正在淡出）。 */
    public static boolean isCombatGraceActive() {
        return awaitingSync && System.nanoTime() < graceUntilNanos;
    }

    /** 宽限期已过、新实体数据仍未到 —— HUD 这时才该显示"同步中"。 */
    public static boolean isAwaitingSync() {
        return awaitingSync && !isCombatGraceActive();
    }

    /**
     * 旧数值的淡出系数：宽限期内 1.0 → 0.45，收到新数据后立刻恢复 1.0。
     *
     * <p>只影响颜色/透明度，不改变布局，避免"数值跳一下、边框闪一下"的观感。
     */
    public static float getCombatValueAlpha() {
        if (!isCombatGraceActive()) return 1.0F;
        long remaining = graceUntilNanos - System.nanoTime();
        float progress = Math.max(0.0F, Math.min(1.0F, remaining / (float) SYNC_GRACE_NANOS));
        return 0.45F + 0.55F * progress;
    }

    /**
     * 实体已不在场（阵亡/收回）但服务端仍给了设计上限：HUD 画 0/上限，等待重新召唤。
     *
     * @param maxHpIn 服务端下发的血量上限，&lt;=0 时保持"同步中"
     */
    public static void setFaintedState(float maxHpIn) {
        clearCombatState();
        // 阵亡帧是权威数据：不用宽限期，直接画 0/上限。
        awaitingSync = false;
        graceUntilNanos = 0L;
        hp = 0.0F;
        points = 0.0F;
        if (maxHpIn > 0.0F) maxHp = maxHpIn;
    }

    public static boolean isPlacementArmed() {
        return placementArmed;
    }

    public static void setPlacementArmed(boolean armed) {
        placementArmed = armed;
    }

    @Nullable
    public static Vec3 getClickedPoint() {
        return clickedPoint;
    }

    @Nullable
    public static Vec3 getServerTarget() {
        return serverTarget;
    }

    public static boolean isServerAccepted() {
        return serverAccepted;
    }

    public static void setClickedPoint(@Nullable Vec3 pos) {
        clickedPoint = pos;
    }

    public static void setServerAck(double x, double y, double z, boolean accepted) {
        serverTarget = new Vec3(x, y, z);
        serverAccepted = accepted;
    }

    // ---- 技能状态读写 ----

    public static void setSkillState(float hpIn, float maxHpIn, float pointsIn,
                                     int q, int w, int e, int r, int channelIn, float chargeIn,
                                     float arenaDistIn, float cx, float cy, float cz, float radius) {
        skillStateReady = charizardId >= 0;
        awaitingSync = false;
        graceUntilNanos = 0L;
        hp = hpIn;
        maxHp = maxHpIn;
        points = pointsIn;
        cdQ = q;
        cdW = w;
        cdE = e;
        cdR = r;
        // 只在 channel 值真的变了时重新计时：这样连"服务端一直重复推同一个非 0 channel"
        // 的僵死也能被看门狗救回来（合法引导最长 4s，不可能拖到 8s）。
        if (channelIn != channel) {
            channelSinceNanos = channelIn == 0 ? 0L : System.nanoTime();
        }
        channel = channelIn;
        charge = chargeIn;
        arenaDist = arenaDistIn;
        arenaX = cx;
        arenaY = cy;
        arenaZ = cz;
        arenaRadius = radius;
        hasArena = radius > 0.0F;
    }

    public static float getArenaDist() {
        return arenaDist;
    }

    public static boolean hasArena() {
        return hasArena;
    }

    public static float getArenaX() {
        return arenaX;
    }

    public static float getArenaY() {
        return arenaY;
    }

    public static float getArenaZ() {
        return arenaZ;
    }

    public static float getArenaRadius() {
        return arenaRadius;
    }

    // ---- R 大字 ----

    public static void setGlyph(boolean active, double x, double y, double z, double size,
                                double dx, double dy, double dz) {
        hasGlyph = active;
        glyphPos = new Vec3(x, y, z);
        glyphSize = size;
        if (dx * dx + dy * dy + dz * dz > 1.0E-6) {
            glyphDir = new Vec3(dx, dy, dz).normalize();
        }
    }

    public static boolean hasGlyph() {
        return hasGlyph;
    }

    public static Vec3 getGlyphPos() {
        return glyphPos;
    }

    /** 飞行方向（字面法线，发射瞬间确定，不随镜头变化）。 */
    public static Vec3 getGlyphDir() {
        return glyphDir;
    }

    public static double getGlyphSize() {
        return glyphSize;
    }

    public static float getHp() {
        return hp;
    }

    public static float getMaxHp() {
        return maxHp;
    }

    public static float getPoints() {
        return points;
    }

    public static int getCdQ() {
        return cdQ;
    }

    public static int getCdW() {
        return cdW;
    }

    public static int getCdE() {
        return cdE;
    }

    public static int getCdR() {
        return cdR;
    }

    /**
     * 当前引导 channel；带超时看门狗（见 {@link #CHANNEL_MAX_NANOS} 的说明）。
     * 所有读取方（SkillKeys / HUD / 特效）都走这里，所以自愈是全局一致的。
     */
    public static int getChannel() {
        if (channel != 0 && channelSinceNanos != 0
                && System.nanoTime() - channelSinceNanos > CHANNEL_MAX_NANOS) {
            channel = 0;
            charge = 0.0F;
            channelSinceNanos = 0L;
        }
        return channel;
    }

    public static float getCharge() {
        return charge;
    }

    // ---- 对局对手 ----

    /** 写入服务端下发的对手真实血量；active=false 时隐藏血条。 */
    public static void setOpponentState(boolean active, float hpValue, float maxHpValue, String name) {
        setOpponentState(active, hpValue, maxHpValue, null, "", name, "", 0);
    }

    /** 完整版：含对手玩家 UUID/名字、宝可梦名与物种、剩余数量。 */
    public static void setOpponentState(boolean active, float hpValue, float maxHpValue,
                                        java.util.UUID ownerId, String ownerName,
                                        String pokemonName, String species, int teamRemaining) {
        opponentActive = active;
        opponentHp = Math.max(0.0F, hpValue);
        opponentMaxHp = Math.max(0.0F, maxHpValue);
        opponentName = pokemonName == null ? "" : pokemonName;
        opponentOwnerId = ownerId;
        opponentOwnerName = ownerName == null ? "" : ownerName;
        opponentPokemonName = pokemonName == null ? "" : pokemonName;
        opponentSpecies = species == null ? "" : species;
        opponentTeamRemaining = teamRemaining;
    }

    public static boolean isOpponentActive() {
        return opponentActive;
    }

    public static float getOpponentHp() {
        return opponentHp;
    }

    public static float getOpponentMaxHp() {
        return opponentMaxHp;
    }

    public static String getOpponentName() {
        return opponentName;
    }

    public static java.util.UUID getOpponentOwnerId() {
        return opponentOwnerId;
    }

    public static String getOpponentOwnerName() {
        return opponentOwnerName;
    }

    public static String getOpponentPokemonName() {
        return opponentPokemonName;
    }

    public static String getOpponentSpecies() {
        return opponentSpecies;
    }

    public static int getOpponentTeamRemaining() {
        return opponentTeamRemaining;
    }

    // ---- 自己的队伍状态 ----

    public static void setTeamState(java.util.List<String> teamList, java.util.List<String> fainted,
                                    String active, boolean summoned, int pendingTicks, String next) {
        // 0.38.3：HUD 上显示的物种变了，先把上一只的血量/冷却清掉。
        // 否则"按 C 选中下一只"到"按 X 召唤"这段时间里，面板是新物种、数字却是旧实体的。
        if (!teamActive.equals(active == null ? "" : active)) {
            clearCombatState();
        }
        team = teamList == null ? java.util.List.of() : java.util.List.copyOf(teamList);
        teamFainted = fainted == null ? java.util.List.of() : java.util.List.copyOf(fainted);
        teamActive = active == null ? "" : active;
        teamSummoned = summoned;
        teamPendingTicks = Math.max(0, pendingTicks);
        teamNext = next == null ? "" : next;
    }

    /** 服务端算好的"按 C 接下来会切换到的那只"，HUD 预览直接用，不自己再推一遍。 */
    public static String getTeamNext() {
        return teamNext;
    }

    public static java.util.List<String> getTeam() {
        return team;
    }

    public static java.util.List<String> getTeamFainted() {
        return teamFainted;
    }

    public static String getTeamActive() {
        return teamActive;
    }

    public static boolean isTeamSummoned() {
        return teamSummoned;
    }

    public static int getTeamPendingTicks() {
        return teamPendingTicks;
    }

    public static boolean isTeamFainted(String species) {
        return teamFainted.contains(species);
    }

    public static void reset() {
        charizardId = -1;
        skillStateReady = false;
        placementArmed = false;
        clickedPoint = null;
        serverTarget = null;
        serverAccepted = false;
        hp = 100.0F;
        maxHp = 100.0F;
        points = 10.0F;
        cdQ = cdW = cdE = cdR = 0;
        channel = 0;
        channelSinceNanos = 0L;
        charge = 0.0F;
        awaitingSync = false;
        graceUntilNanos = 0L;
        opponentActive = false;
        opponentHp = 0.0F;
        opponentMaxHp = 0.0F;
        opponentName = "";
        opponentPokemonName = "";
        opponentSpecies = "";
        opponentOwnerId = null;
        opponentOwnerName = "";
        opponentTeamRemaining = 0;
        team = java.util.List.of();
        teamFainted = java.util.List.of();
        teamActive = "";
        teamSummoned = false;
        teamPendingTicks = 0;
        teamNext = "";
    }
}
