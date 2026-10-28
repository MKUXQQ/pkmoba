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
    private static Move m(String name, Shape shape, int cost, int seconds, int windup,
                          int duration, double range, double radius, float damage) {
        return new Move(name, shape, cost, seconds * 20, windup, duration, range, radius, damage);
    }
    public static final List<Species> ALL = List.of(
        new Species("lucario", "路卡利欧", 300, List.of(
            m("波导弹", Shape.LINE, 2, 4, 8, 28, 18, .65, 26),
            m("发劲", Shape.CONE, 3, 7, 6, 6, 4, 55, 30),
            m("神速", Shape.DASH, 2, 7, 3, 10, 9, .8, 22),
            m("近身战", Shape.DASH, 3, 9, 12, 32, 7, 1.2, 32))),
        new Species("abomasnow", "暴雪王", 360, List.of(
            m("冰砾", Shape.LINE, 2, 4, 8, 26, 16, .7, 24),
            m("冰冻之风", Shape.CONE, 3, 7, 8, 20, 8, 40, 30),
            m("白雾", Shape.WALL, 3, 10, 12, 80, 10, 3, 0),
            m("暴风雪", Shape.POINT, 3, 9, 24, 80, 14, 4.5, 32))),
        new Species("blaziken", "火焰鸡", 300, List.of(
            m("火焰踢", Shape.CONE, 2, 4, 5, 8, 3.8, 45, 28),
            m("二连踢", Shape.CONE, 3, 7, 5, 16, 3.8, 45, 36),
            m("蓄能焰袭", Shape.DASH, 2, 8, 4, 12, 8, .8, 20),
            m("闪焰冲锋", Shape.DASH, 3, 9, 18, 16, 14, 1, 34))),
        new Species("gengar", "耿鬼", 260, List.of(
            m("暗影球", Shape.LINE, 2, 4, 8, 30, 18, 1.6, 26),
            m("污泥炸弹", Shape.POINT, 3, 7, 8, 24, 13, 2.5, 22),
            m("催眠术", Shape.CONE, 3, 10, 12, 6, 6, 35, 0),
            m("食梦", Shape.DASH, 3, 9, 12, 14, 9, 1, 28))),
        new Species("tyranitar", "班基拉斯", 380, List.of(
            m("岩崩", Shape.POINT, 3, 6, 20, 8, 12, 2.8, 30),
            m("咬碎", Shape.CONE, 2, 5, 8, 6, 3.5, 35, 38),
            m("沙暴", Shape.SELF, 3, 10, 8, 100, 0, 3.5, 40),
            m("尖石攻击", Shape.LINE, 3, 9, 20, 30, 16, 1.6, 34))),
        new Species("garchomp", "烈咬陆鲨", 330, List.of(
            m("龙爪", Shape.CONE, 2, 4, 5, 10, 4, 50, 30),
            m("重踏", Shape.SELF, 3, 7, 10, 6, 0, 4, 28),
            m("挖洞", Shape.DASH, 3, 9, 10, 24, 9, 2.6, 26),
            m("龙之俯冲", Shape.DASH, 3, 9, 16, 16, 13, 1.2, 32))),
        new Species("scizor", "巨钳螳螂", 310, List.of(
            m("子弹拳", Shape.DASH, 2, 4, 3, 8, 5, .8, 24),
            m("连斩", Shape.CONE, 3, 7, 4, 20, 3.8, 55, 36),
            m("急速折返", Shape.DASH, 2, 8, 4, 18, 5, .9, 22),
            m("剑舞", Shape.BUFF, 3, 10, 12, 100, 0, 2, 0))),
        new Species("gardevoir", "沙奈朵", 260, List.of(
            m("精神强念", Shape.LINE, 2, 4, 8, 28, 18, .6, 24),
            m("月亮之力", Shape.POINT, 3, 7, 10, 30, 15, 2.8, 34),
            m("瞬间移动", Shape.BLINK, 2, 9, 6, 4, 7, .5, 0),
            m("预知未来", Shape.POINT, 3, 9, 40, 8, 16, 4, 34)))
    );
    private ExpansionRoster() {}
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
