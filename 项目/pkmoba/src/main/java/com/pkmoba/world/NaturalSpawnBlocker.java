package com.pkmoba.world;

import com.cobblemon.mod.common.api.events.CobblemonEvents;

/**
 * 关闭世界里的宝可梦自然生成（0.35 需求）。
 *
 * <p>做法是挂 Cobblemon 官方的生成事件并取消它，而不是定时扫描世界删实体：
 * <ul>
 *   <li>Cobblemon 所有自然生成最终都走 {@code SingleEntitySpawnAction}，那里会
 *       {@code CobblemonEvents.ENTITY_SPAWN.postThen(...)}，取消事件即可阻止生成；</li>
 *   <li>玩家主动召唤（本模组 {@code Pokemon.sendOut}）、队伍数据、实体追踪、1v1 都不经过该事件，
 *       因此完全不受影响；</li>
 *   <li>已经存在于世界里的宝可梦不会被删除。</li>
 * </ul>
 *
 * <p>事件是跨维度、跨群系、跨物种的统一入口，所以不需要再按群系/时间/维度分别处理。
 */
public final class NaturalSpawnBlocker {

    private NaturalSpawnBlocker() {
    }

    /** 只注册一次；重复调用是安全的（由调用方保证只调一次）。 */
    public static void init() {
        try {
            CobblemonEvents.ENTITY_SPAWN.subscribe(event -> event.cancel());
        } catch (Throwable throwable) {
            // 拿不到 Cobblemon 事件 API 时不要拖垮整个模组初始化
            com.pkmoba.PkmobaMod.LOGGER.warn("[pkmoba] 无法关闭宝可梦自然生成", throwable);
        }
    }
}
