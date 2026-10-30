package com.pkmoba.client;

import com.pkmoba.network.CameraSettingsPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;

/** /mo is registered exclusively on the server; apply settings only to the requesting client. */
public final class CameraCommands {
    private CameraCommands() { }
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(CameraSettingsPayload.TYPE, (p, context) -> context.client().execute(() -> {
            switch (p.action()) {
                case 0 -> { }
                case 1 -> {
                    if (!valid(p.left(), 0, 40) || !valid(p.right(), 0, 40) || !valid(p.vertical(), 0, 40)) return;
                    CameraSettings.left = p.left(); CameraSettings.right = p.right();
                    CameraSettings.top = CameraSettings.bottom = p.vertical();
                    CameraSettings.save(); CameraSettings.showPreview();
                }
                case 2 -> {
                    if (!valid(p.speed(), 1, 100)) return;
                    CameraSettings.speed = p.speed(); CameraSettings.save();
                }
                case 3 -> CameraSettings.reset();
                default -> { return; }
            }
            Minecraft mc = context.client();
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal(String.format(java.util.Locale.ROOT,
                "镜头推屏 左/右/上下 %.1f/%.1f/%.1f%%，速度 %.1f 格/秒。",
                CameraSettings.left, CameraSettings.right, CameraSettings.top, CameraSettings.speed)));
        }));
    }
    private static boolean valid(double n, double min, double max) { return Double.isFinite(n) && n >= min && n <= max; }
}
