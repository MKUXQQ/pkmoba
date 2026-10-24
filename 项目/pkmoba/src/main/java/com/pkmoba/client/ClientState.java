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
            skillStateReady = false;
            // 实体被移除（id<0）时保留 maxHp，让血条显示 0/上限 而不是 0/0；
            // 真正阵亡时服务端会补发一帧 0 血量（CharizardCombat.sendFaintedState）。
            hp = 0.0F;
            points = 0.0F;
            if (id >= 0) maxHp = 0.0F;
            cdQ = cdW = cdE = cdR = channel = 0;
            charge = 0;
            hasGlyph = false;
            clickedPoint = null;
            serverTarget = null;
        }
        charizardId = id;
        if (id < 0) {
            clickedPoint = null;
            serverTarget = null;
        }
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
        hp = hpIn;
        maxHp = maxHpIn;
        points = pointsIn;
        cdQ = q;
        cdW = w;
        cdE = e;
        cdR = r;
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

    public static int getChannel() {
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
        charge = 0.0F;
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
