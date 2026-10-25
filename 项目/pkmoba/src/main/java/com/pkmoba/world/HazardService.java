package com.pkmoba.world;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 场地陷阱（撒菱/毒菱一类）的内存服务（0.38 只落接口）。
 *
 * <p>为什么先只做内存表：0.38 的战斗需求只有「高速旋转（W）起手与每 20 tick 清掉身边 3 格内的钉子」
 * 和调试指令，真正的伤害/视觉/落盘（区块重载、对局结束清理、客户端同步）留到后续版本。
 * 因此这里刻意不依赖 {@code ServerLevel}，也不生成方块实体 —— 换维度/重启即清空，
 * 不会像旧版 R 领域那样留下"幽灵钉子"。
 *
 * <p>线程模型：只在服务端主线程调用（技能 tick 与指令），不做并发保护。
 */
public final class HazardService {

    /** 单个场地的陷阱上限，避免调试指令刷爆内存。 */
    private static final int MAX_HAZARDS = 512;

    /** 陷阱位置表（0.38 不区分种类，一根钉子 = 一个位置）。 */
    private static final List<Vec3> HAZARDS = new ArrayList<>();

    private HazardService() {
    }

    /** 在 {@code pos} 处放置 {@code count} 根钉子（同一位置可叠多根）。 */
    public static void add(Vec3 pos, int count) {
        if (pos == null || count <= 0) {
            return;
        }
        for (int i = 0; i < count && HAZARDS.size() < MAX_HAZARDS; i++) {
            HAZARDS.add(pos);
        }
    }

    /**
     * 清掉以 {@code pos} 为球心、{@code radius} 为半径内的所有钉子。
     *
     * @return 实际清掉的钉子数（0 表示附近没有钉子，调用方可据此决定是否播特效）
     */
    public static int clearNear(Vec3 pos, double radius) {
        if (pos == null || radius <= 0) {
            return 0;
        }
        double radiusSqr = radius * radius;
        int removed = 0;
        for (Iterator<Vec3> it = HAZARDS.iterator(); it.hasNext(); ) {
            if (it.next().distanceToSqr(pos) <= radiusSqr) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** 当前钉子总数（HUD/调试用）。 */
    public static int count() {
        return HAZARDS.size();
    }

    /** 只读快照（后续版本给客户端同步或调试渲染用）。 */
    public static List<Vec3> all() {
        return Collections.unmodifiableList(new ArrayList<>(HAZARDS));
    }

    /** 清空全部钉子（对局结束/服务器停机/调试）。 */
    public static void clearAll() {
        HAZARDS.clear();
    }
}
