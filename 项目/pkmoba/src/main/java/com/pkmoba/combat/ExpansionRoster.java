package com.pkmoba.combat;

import java.util.List;
import java.util.Locale;

/** Expansion contract shared by authority, previews, HUD and resource validation.
 * Definitions alone do not make a species selectable; registration follows engine integration.
 * All timing is server ticks. Values are first-pass balance, not original-game power.
 */
public final class ExpansionRoster {
    public enum Shape { LINE, CONE, DASH, POINT, SELF, WALL, BLINK, BUFF }
    public record Move(String name, Shape shape, int cost, int cooldown, int windup,
                       int duration, double range, double radius, float damage) {}
    public record Species(String id, String name, float maxHp, List<Move> moves) {
        public Move move(int index) { return moves.get(index); }
        public int channel(int index) { return 21 + ALL.indexOf(this) * 4 + index; }
    }
    /** 0.101 (m15488): hard rule - no active skill may cool down for longer than ten seconds. */
    private static final int MAX_COOLDOWN_SECONDS = 10;

    private static Move m(String name, Shape shape, int cost, int seconds, int windup,
                          int duration, double range, double radius, float damage) {
        int clamped = Math.max(2, Math.min(MAX_COOLDOWN_SECONDS, seconds));
        return new Move(name, shape, cost, clamped * 20, windup, duration, range, radius, damage);
    }
    public static final List<Species> ALL = List.of(
        new Species("lucario", "路卡利欧", 320, List.of(
            // Swords Dance buffs attack by 36.6%, then Meteor Mash / Thunder Punch cash it in;
            // Extreme Speed is a long, responsive dash that hits once on contact.
            m("剑舞", Shape.SELF, 1, 7, 4, 14, 0, 3.0, 0),
            m("彗星拳", Shape.CONE, 2, 5, 5, 16, 4.0, 45, 38),
            m("雷电拳", Shape.CONE, 2, 6, 4, 14, 3.8, 40, 28),
            m("神速", Shape.DASH, 2, 8, 3, 18, 12, .9, 22))),
        // 0.96 official ice kit: Freeze-Dry, Aurora Veil, Blizzard, Tera Blast.
        new Species("alolan_ninetales", "阿罗拉九尾", 320, List.of(
            m("冷冻干燥", Shape.LINE, 1, 3, 3, 26, 16, .8, 34),
            m("极光幕", Shape.SELF, 2, 6, 4, 6, 0, 3.4, 0),
            m("暴风雪", Shape.POINT, 3, 8, 6, 18, 14, 4.2, 58),
            m("太晶爆发", Shape.LINE, 3, 10, 5, 18, 16, 1.2, 78))),
        new Species("blaziken", "火焰鸡", 300, List.of(
            // 0.112 (m16191): only moves Blaziken actually learns in the games -
            // Blaze Kick / Sky Uppercut / High Jump Kick / Flare Blitz.
            m("火焰踢", Shape.LINE, 2, 4, 4, 10, 13, 1.0, 36),
            m("冲天拳", Shape.CONE, 2, 6, 4, 10, 3.2, 30, 34),
            m("飞膝踢", Shape.POINT, 2, 6, 5, 16, 12, 1.6, 42),
            m("闪焰冲锋", Shape.DASH, 3, 10, 6, 20, 14, 1.8, 54))),
        new Species("gengar", "耿鬼", 260, List.of(
            m("暗影球", Shape.LINE, 2, 4, 8, 30, 18, 1.6, 26),
            m("污泥炸弹", Shape.POINT, 3, 7, 8, 24, 13, 2.5, 22),
            m("催眠术", Shape.LINE, 3, 10, 8, 40, 15, 1.5, 0),
            m("食梦", Shape.BLINK, 3, 9, 8, 12, 12, 1.6, 30))),
        new Species("tyranitar", "班基拉斯", 380, List.of(
            m("岩崩", Shape.POINT, 3, 6, 18, 20, 12, 2.8, 36),
            m("咬碎", Shape.CONE, 2, 5, 8, 6, 3.5, 35, 44),
            m("沙暴", Shape.SELF, 3, 10, 8, 100, 0, 3.5, 56),
            m("地震", Shape.SELF, 3, 10, 12, 26, 0, 5.2, 78))),
        new Species("gardevoir", "沙奈朵", 260, List.of(
            // Official Gardevoir moves only: Psychic, Moonblast, Teleport and Dazzling Gleam.
            m("精神强念", Shape.POINT, 2, 3, 4, 32, 13, 4.0, 14),
            m("月亮之力", Shape.POINT, 3, 5, 10, 28, 16, 4.2, 48),
            m("瞬间移动", Shape.BLINK, 2, 5, 4, 6, 10, .5, 0),
            m("魔法闪耀", Shape.SELF, 2, 7, 6, 16, 7, 5.0, 34))),
        // 0.123: the two most-played fast attackers of the current meta. Greninja trades body
        // for range (three shuriken ticks, a thrown dark orb, a psychic cone, a charged beam);
        // Meowscarada mixes one bomb, one lunge and a real dash that snaps back.
        new Species("greninja", "甲贺忍蛙", 300, List.of(
            // 0.129 (m00903): five hand-thrown shuriken straight down the aim line, a longer
            // wall-respecting dark lunge, a thrown dark orb, and Nasty Plot (+30% for 8 s) in the
            // R slot. Every cooldown stays under the ten-second rule.
            m("螺旋手里剑", Shape.LINE, 2, 4, 2, 34, 16, .6, 10),
            m("暗袭要害", Shape.DASH, 2, 6, 4, 7, 6.5, 1.5, 40),
            m("恶之波动", Shape.POINT, 3, 8, 6, 16, 13, 2.5, 55),
            m("诡计", Shape.SELF, 2, 10, 4, 10, 0, 3.2, 0))),
        new Species("meowscarada", "魔幻假面喵", 310, List.of(
            // 0.130 Meowscarada 2.0 (m00915): homing Magical Leaf, U-turn, guaranteed-crit Flower
            // Trick and the Petal Blizzard storm. R keeps its 55 second ultimate lockout, so it is
            // built directly instead of through m(), whose helper caps every move at ten seconds.
            m("魔法叶", Shape.LINE, 2, 5, 2, 24, 12, .5, 10),
            m("急速折返", Shape.DASH, 2, 8, 3, 9, 8, 1.5, 35),
            m("千变万花", Shape.POINT, 3, 10, 5, 20, 14, 2, 60),
            m("落英缤纷", Shape.POINT, 3, 10, 8, 15, 10, 4, 24))),
        // 0.132 Ceruledge 2.0: Psycho Cut, Shadow Sneak, Bitter Blade and Swords Dance. The
        // design doc keeps E at eleven and R at fifty-five seconds, so both are built directly
        // to bypass the ten-second clamp in m().
        new Species("ceruledge", "苍炎刃鬼", 330, List.of(
            m("精神利刃", Shape.LINE, 2, 5, 3, 14, 11, .8, 35),
            m("影子偷袭", Shape.DASH, 2, 8, 3, 7, 5, 1.5, 40),
            m("悔念剑", Shape.CONE, 3, 10, 4, 10, 3, 2, 30),
            m("剑舞", Shape.SELF, 2, 10, 6, 10, 0, 3.5, 0))),
        // 0.133 Hisuian Zoroark: dark claw dash, bitter malice ring, shadow ball, illusion pack.
        new Species("hisuian_zoroark", "洗翠索罗亚克", 310, List.of(
            m("暗影爪", Shape.DASH, 2, 6, 2, 6, 4.5, 1.5, 40),
            m("冤冤相报", Shape.POINT, 2, 10, 4, 6, 6, 3, 45),
            m("暗影球", Shape.LINE, 3, 9, 3, 14, 14, .8, 55),
            m("幻象分身", Shape.SELF, 3, 10, 6, 86, 6, 2, 90))),
        // 0.134 Krookodile: sand-caked bruiser - crunch a target's guard, dig under the floor,
        // quake a whole zone, then five furious swings and the original Outrage fatigue.
        new Species("krookodile", "流氓鳄", 340, List.of(
            m("咬碎", Shape.LINE, 2, 6, 2, 6, 3.5, 1.5, 40),
            m("挖洞", Shape.DASH, 2, 10, 2, 12, 7, 2, 45),
            m("地震", Shape.POINT, 3, 10, 6, 18, 5, 4, 55),
            m("逆鳞", Shape.SELF, 3, 10, 6, 56, 3.5, 2, 24))),
        // 0.135 Armarouge: ranged fire/psychic artillery - a psyshock beam, a flame dash, the twin
        // armour cannon and a five-pulse Expanding Force field. Armor Cannon (12 s) and Expanding
        // Force (55 s) are pulled down to the project's ten second ceiling.
        new Species("armarouge", "红莲铠骑", 300, List.of(
            m("精神冲击", Shape.LINE, 2, 6, 2, 14, 13, .8, 40),
            m("火焰旋涡", Shape.DASH, 2, 9, 3, 8, 5, 1.5, 35),
            m("铠甲炮", Shape.LINE, 3, 10, 4, 16, 14, 1, 55),
            m("扩展力量", Shape.POINT, 3, 10, 8, 15, 10, 4, 24)))
    );
    private ExpansionRoster() {}
    /**
     * 0.88: regional forms spawn as their base Cobblemon species, so every client lookup (HUD, key
     * map, animations, effects) must resolve through the registry alias as well. Without this an
     * Alolan Ninetales entity was "unknown" on the client and the whole skill bar went dead.
     */
    public static Species resolve(String identifier) {
        Species found = find(identifier);
        if (found != null) return found;
        var id = net.minecraft.resources.ResourceLocation.tryParse(identifier);
        var local = com.pkmoba.pokemon.SpeciesRegistry.localForCobble(id);
        return local == null ? null : find(local.toString());
    }

    public static Species find(String id) {
        if (id == null) return null;
        String bare = id.toLowerCase(Locale.ROOT);
        int colon = bare.indexOf(':');
        if (colon >= 0) {
            if (!bare.substring(0,colon).equals("cobblemon")) return null;
            bare = bare.substring(colon + 1);
        }
        for (Species species : ALL) if (species.id().equals(bare)) return species;
        return null;
    }
}
