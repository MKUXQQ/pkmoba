package com.pkmoba.pokemon;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 单个玩家的宝可梦档案：队伍顺序、当前上场位置、已阵亡成员。 */
public final class PlayerProfile {
    public final UUID playerId;
    /** 队伍（按出场顺序），元素是物种完整 id，如 {@code cobblemon:charizard}。 */
    public final List<String> team = new ArrayList<>();
    /** 已阵亡、需要复活才能再上场的物种 id。 */
    public final Set<String> fainted = new LinkedHashSet<>();
    /** 当前上场的是队伍第几只（0 起）。 */
    public int activeIndex;

    public PlayerProfile(UUID playerId) {
        this.playerId = playerId;
    }

    public boolean hasStarter() {
        return !team.isEmpty();
    }

    public String activeSpecies() {
        if (team.isEmpty()) return null;
        if (activeIndex < 0 || activeIndex >= team.size()) activeIndex = 0;
        return team.get(activeIndex);
    }

    /** 还有没有能上场的宝可梦（未阵亡）。 */
    public boolean hasUsableLeft() {
        for (String id : team) if (!fainted.contains(id)) return true;
        return false;
    }

    /** 切到下一只未阵亡的宝可梦，返回它的物种 id；没有则返回 null。 */
    public String nextUsable() {
        String next = peekNextUsable();
        if (next != null) activeIndex = team.indexOf(next);
        return next;
    }

    /**
     * 只计算"下一只会是谁"，<b>不</b>改动 {@link #activeIndex}。
     *
     * <p>HUD 的"下一只"预览与真正按 C 切换必须共用这一份逻辑，否则会出现
     * "预览显示水箭龟、实际放出喷火龙"的错位。
     *
     * @return 下一只可上场宝可梦的物种 id；没有其他可用成员时返回 null
     */
    public String peekNextUsable() {
        int size = team.size();
        if (size <= 1) return null;
        for (int step = 1; step < size; step++) {
            int index = (activeIndex + step) % size;
            String id = team.get(index);
            if (!fainted.contains(id)) return id;
        }
        return null;
    }

    /** 把某只宝可梦从队伍里彻底删掉（含阵亡记录），并修正上场下标。 */
    public boolean removeSpecies(String speciesId) {
        int index = team.indexOf(speciesId);
        if (index < 0) return false;
        team.remove(index);
        fainted.remove(speciesId);
        if (team.isEmpty()) {
            activeIndex = 0;
        } else if (index < activeIndex) {
            activeIndex--;
        } else if (activeIndex >= team.size()) {
            activeIndex = team.size() - 1;
        }
        return true;
    }

    public void markFainted(String speciesId) {
        if (speciesId != null) fainted.add(speciesId);
    }

    public void reviveAll() {
        fainted.clear();
    }
}
