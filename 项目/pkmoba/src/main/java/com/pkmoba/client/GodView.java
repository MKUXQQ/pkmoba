package com.pkmoba.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;

/** 默认保留世界锚点；按住锁定键时平滑跟随受控宝可梦。 */
public final class GodView {
    private static final float PITCH = 55.0F, YAW = 0.0F;
    private static final double BASE_DISTANCE = 12, MIN_DISTANCE = 6, MAX_DISTANCE = 32;
    private static boolean active, locked, initialized;
    private static boolean restoreInputPending;
    private static int restoreInputTicks;
    private static double zoomOffset;
    private static Vec3 freeAnchor = Vec3.ZERO, renderAnchor = Vec3.ZERO, panVelocity = Vec3.ZERO;
    private static long lastFrameNanos;
    private GodView() { }
    public static boolean isActive() { return active; }
    public static boolean isLocked() { return locked; }
    public static float getPitch() { return PITCH; }
    public static float getYaw() { return YAW; }
    public static void activate() { activate(null); }
    public static void activate(Vec3 entryAnchor) {
        if (active) return;
        active = true; locked = false; zoomOffset = 0; restoreInputPending = false;
        Minecraft client = Minecraft.getInstance();
        Entity controlled = client.level == null ? null : client.level.getEntity(ClientState.getCharizardId());
        freeAnchor = controlled != null || entryAnchor == null ? targetAnchor(1) : entryAnchor;
        renderAnchor = freeAnchor;
        initialized = true; panVelocity = Vec3.ZERO; lastFrameNanos = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        mc.gameRenderer.setRenderHand(false);
        mc.mouseHandler.releaseMouse();
    }
    public static void deactivate() {
        // false 确认必须幂等：即使本地早已 inactive，也仍修复鼠标与输入残留。
        active = false; locked = false; panVelocity = Vec3.ZERO;
        Minecraft mc = Minecraft.getInstance();
        mc.gameRenderer.setRenderHand(true);
        restoreInputPending = true;
        restoreInputTicks = 5;
        InputHandler.clearSuppressedInput(mc);
        restorePlayerControl(mc);
    }
    public static void reset() {
        if (active) Minecraft.getInstance().gameRenderer.setRenderHand(true);
        active = false; locked = false; initialized = false; zoomOffset = 0; restoreInputPending = false;
        freeAnchor = renderAnchor = panVelocity = Vec3.ZERO; lastFrameNanos = 0;
    }
    public static void setLockedFromInput(boolean held, boolean tapped) {
        if (!active || locked == held) return;
        if (!held) freeAnchor = renderAnchor;
        locked = held;
        panVelocity = Vec3.ZERO;
    }
    private static Vec3 targetAnchor(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        Entity target = mc.level == null ? null : mc.level.getEntity(ClientState.getCharizardId());
        if (target != null) return target.getPosition(partialTick).add(0, 1, 0);
        if (active && initialized) return freeAnchor;
        return mc.player == null ? renderAnchor : mc.player.getEyePosition(partialTick);
    }
    public static Vec3 getAnchor(float partialTick) {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : Math.max(0, Math.min(0.1, (now - lastFrameNanos) / 1.0E9));
        lastFrameNanos = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && !mc.isPaused()) {
            Vec3 desiredVelocity = locked ? Vec3.ZERO : edgeVelocity(mc);
            if (SkillHud.isMouseOver()) panVelocity = Vec3.ZERO;
            panVelocity = panVelocity.add(desiredVelocity.subtract(panVelocity).scale(1 - Math.exp(-14 * dt)));
            if (!locked) freeAnchor = freeAnchor.add(panVelocity.scale(dt));
            Vec3 target = locked ? targetAnchor(partialTick) : freeAnchor;
            if (!initialized) { renderAnchor = target; initialized = true; }
            renderAnchor = renderAnchor.add(target.subtract(renderAnchor).scale(1 - Math.exp(-10 * dt)));
        } else {
            panVelocity = Vec3.ZERO;
        }
        return renderAnchor;
    }
    public static Vec3 getCameraPos(float partialTick) {
        return getAnchor(partialTick).subtract(lookDirection().scale(getCurrentDistance()));
    }
    public static void adjustZoom(double notches) {
        if (active) zoomOffset = Math.max(MIN_DISTANCE - BASE_DISTANCE,
                Math.min(MAX_DISTANCE - BASE_DISTANCE, zoomOffset - notches * 1.5));
    }
    public static double getCurrentDistance() { return BASE_DISTANCE + zoomOffset; }
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (active && mc.screen == null && mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
        if (!active && restoreInputPending) restorePlayerControl(mc);
    }
    private static void restorePlayerControl(Minecraft mc) {
        if (mc.player == null || mc.screen != null || !mc.isWindowActive()) return;
        if (!mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.grabMouse();
        if (!mc.mouseHandler.isMouseGrabbed()) return;
        // 原版 grabMouse 为菜单关闭设置 10000 tick 防误击；本模式退出应立即恢复操作。
        mc.missTime = 0;
        InputHandler.restorePlayerInput(mc);
        restoreInputPending = --restoreInputTicks > 0;
    }
    private static Vec3 edgeVelocity(Minecraft mc) {
        if (SkillHud.isMouseOver()) return Vec3.ZERO;
        int[] size = windowSize(mc.getWindow().getWindow());
        double[] pos = cursorWindowPos();
        if (size[0] <= 0 || size[1] <= 0 || pos[0] < 0 || pos[1] < 0 || pos[0] > size[0] || pos[1] > size[1]) return Vec3.ZERO;
        double horizontal = strength(size[0] - pos[0], size[0], CameraSettings.right)
                - strength(pos[0], size[0], CameraSettings.left);
        double vertical = strength(pos[1], size[1], CameraSettings.top)
                - strength(size[1] - pos[1], size[1], CameraSettings.bottom);
        Vec3 direction = lookDirection();
        Vec3 forward = new Vec3(direction.x, 0, direction.z).normalize();
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        Vec3 velocity = right.scale(horizontal).add(forward.scale(vertical));
        if (velocity.lengthSqr() > 1) velocity = velocity.normalize();
        return velocity.scale(CameraSettings.speed);
    }
    private static double strength(double distance, double dimension, double percent) {
        double zone = dimension * percent / 100;
        return zone <= 0 ? 0 : Math.max(0, Math.min(1, (zone - distance) / zone));
    }
    private static Vec3 lookDirection() { return Vec3.directionFromRotation(PITCH, YAW); }
    public static double[] cursorWindowPos() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer x = stack.mallocDouble(1), y = stack.mallocDouble(1);
            GLFW.glfwGetCursorPos(Minecraft.getInstance().getWindow().getWindow(), x, y);
            return new double[]{x.get(0), y.get(0)};
        }
    }
    public static double[] cursorNdc(Minecraft mc) {
        int[] size = windowSize(mc.getWindow().getWindow());
        double[] pos = cursorWindowPos();
        return new double[]{2 * pos[0] / Math.max(1, size[0]) - 1, 1 - 2 * pos[1] / Math.max(1, size[1])};
    }
    private static int[] windowSize(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(window, w, h);
            return new int[]{w.get(0), h.get(0)};
        }
    }
}
