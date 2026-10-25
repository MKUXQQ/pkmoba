package com.pkmoba.client;

import com.cobblemon.mod.common.client.entity.PokemonClientDelegate;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.combat.MouthAnchor;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端嘴部锚点：优先读 Cobblemon 活体骨骼定位器（会跟着施法动画一起动），
 * 拿不到时退回 {@link MouthAnchor} 的静态偏移。
 *
 * <p>为什么要这么绕：{@code PokemonClientDelegate#getLocatorStates()} 的 key 在不同
 * Cobblemon 版本里可能是 {@code mouth}/{@code locator_mouth}/{@code internal_locator__mouth}
 * 之一，硬编码一个 key 很可能什么都拿不到，而"拿不到"的表现就是火焰从胸口飘出来
 * （退回 {@code getEyePosition()} 的旧表现）。所以这里按候选 key 逐个试，
 * 并且用"离脚底高度必须在 1.0~4.0 格、离实体不超过 6 格"做合理性校验，
 * 不合格就当失败，绝不把一堆乱数据糊到特效上。
 */
public final class MouthLocator {

    /** 候选 key：新老版本/不同命名习惯全试一遍。 */
    private static final String[] KEYS = {
            "mouth", "locator_mouth", "internal_locator__mouth",
            "jaw_upper", "locator_jaw_upper", "muzzle"
    };

    /** 合理性区间：嘴不可能比 1 格更矮（除了极小体型），也不可能高过 4 格。 */
    private static final double MIN_UP = 0.8;
    private static final double MAX_UP = 4.2;
    /** 离实体原点最远 6 格，超出说明取到了别的实体的数据。 */
    private static final double MAX_DISTANCE_SQR = 6.0 * 6.0;

    private static boolean loggedKeys;

    private MouthLocator() {
    }

    /**
     * 取嘴部世界坐标。
     *
     * @param entity     宝可梦实体
     * @param extraForward 额外前推（格），通常传 0
     * @return 永远非 null；定位器不可用时是静态偏移结果
     */
    public static Vec3 mouth(PokemonEntity entity, double extraForward) {
        Vec3 live = liveLocator(entity);
        if (live != null) {
            Vec3 forward = MouthAnchor.forward(entity.getYRot());
            return extraForward == 0.0 ? live : live.add(forward.scale(extraForward));
        }
        return MouthAnchor.position(entity, extraForward);
    }

    /** 只取活体定位器，失败返回 null（给需要区分来源的调用方用）。 */
    public static Vec3 liveLocator(PokemonEntity entity) {
        if (entity == null) {
            return null;
        }
        try {
            if (!(entity.getDelegate() instanceof PokemonClientDelegate delegate)) {
                return null;
            }
            var states = delegate.getLocatorStates();
            if (states == null || states.isEmpty()) {
                return null;
            }
            for (String key : KEYS) {
                var locator = states.get(key);
                if (locator == null) {
                    debugKeysOnce(states.keySet());
                    continue;
                }
                Vec3 origin = locator.getOrigin();
                if (valid(entity, origin)) {
                    return origin;
                }
            }
            debugKeysOnce(states.keySet());
        } catch (RuntimeException ignored) {
            // 任何异常都退回静态偏移，绝不影响渲染。
        }
        return null;
    }

    private static boolean valid(PokemonEntity entity, Vec3 origin) {
        if (origin == null || !Double.isFinite(origin.x) || !Double.isFinite(origin.y)
                || !Double.isFinite(origin.z)) {
            return false;
        }
        double up = origin.y - entity.getY();
        if (up < MIN_UP || up > MAX_UP) {
            return false;
        }
        return origin.distanceToSqr(entity.position()) <= MAX_DISTANCE_SQR;
    }

    /**
     * 只打一次日志，把真实存在的定位器 key 列出来。
     * 下次如果嘴部还是不对，看日志里 {@code [pkmoba] 嘴部定位器 key} 这一行就能直接定位。
     */
    private static void debugKeysOnce(java.util.Set<String> keys) {
        if (loggedKeys) {
            return;
        }
        loggedKeys = true;
        List<String> interesting = new ArrayList<>();
        for (String key : keys) {
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("mouth") || lower.contains("jaw") || lower.contains("muzzle")
                    || lower.contains("head")) {
                interesting.add(key);
            }
        }
        org.slf4j.LoggerFactory.getLogger("pkmoba").info(
                "[pkmoba] 嘴部定位器 key（共 {} 个，命中候选 {} 个）: {}",
                keys.size(), interesting.size(), interesting);
    }
}
