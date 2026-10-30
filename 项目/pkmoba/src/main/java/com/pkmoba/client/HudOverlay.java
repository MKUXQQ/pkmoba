package com.pkmoba.client;

/**
 * 已移除调试 HUD 与自绘光标。
 *
 * <p>保留初始化入口是为了兼容 PkmobaClient 的既有生命周期，不再注册任何 HUD 回调。
 */
public final class HudOverlay {
    private HudOverlay() {
    }

    public static void init() {
        // 调试信息和自绘鼠标指针已删除。
    }
}
