package com.pkmoba.client;

import com.pkmoba.PkmobaMod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;

/**
 * 上帝视角镜头：默认自由视角，按住空格才跟随受控宝可梦。
 *
 * <p>0.38 曾改成"默认跟随、按住空格解锁"，用户 m00869 反馈"怎么一直跟着模型了，
 * 我需要摁住空格才跟着模型"，所以 0.38.1 恢复为 0.21 的语义：
 * <ul>
 *   <li>进入视角默认 {@code locked = false}（自由），锚点从受控宝可梦位置起步，
 *       之后靠边缘推屏平移，不设距离上限；</li>
 *   <li>按住空格 {@code locked = true}，镜头贴住受控宝可梦实体（{@link ClientState} 里的
 *       activeEntityId），松手立刻回到自由；</li>
 *   <li>宝可梦实体暂时没送到客户端时，跟随状态下先保持最后已知位置 1.5 秒，仍没有就平滑退回
 *       玩家本体视角 —— 不瞬移、不卡死；</li>
 *   <li>滚轮缩放上限 128 格，步进随当前距离变大：近处细调，远处也能快速拉近看清。</li>
 * </ul>
 *
 * <p>0.38.2：跟随输入改由 {@code InputHandler} 直接轮询 GLFW 物理键（不再信
 * {@code KeyMapping.isDown()} 的事件缓存），并加硬保险 —— 只要本视角未激活、开着 GUI 或
 * 游戏暂停，{@code locked} 一律强制 false，见 {@link #enforceLockable}。
 *
 * <p>职责边界：本类只负责"看"。玩家本体由服务端 {@code lock/PlayerLockService} 挂在受控宝可梦
 * 上方 12 格作为动态锚点，保证区块发送与实体追踪始终覆盖宝可梦；两边都不要互相写位置。
 */
public final class GodView {
    private static final float PITCH = 55.0F, YAW = 0.0F;
    /** 缩放范围：6 ~ 128 格（旧版上限 32 格，看不清远处的宝可梦）。 */
    private static final double BASE_DISTANCE = 12, MIN_DISTANCE = 6, MAX_DISTANCE = 128;
    /** 跟随/自由平移的平滑速率（1/秒）：越大越跟手，太小会有拖影。 */
    private static final double FOLLOW_RATE = 12.0, PAN_RATE = 14.0;
    /** 目标实体丢失后保持最后位置的时间（秒），超时才退回玩家视角。 */
    private static final double LOST_GRACE_SECONDS = 1.5;
    private static boolean active, locked, initialized;
    private static boolean restoreInputPending;
    private static int restoreInputTicks;
    private static double zoomOffset;
    private static Vec3 freeAnchor = Vec3.ZERO, renderAnchor = Vec3.ZERO, panVelocity = Vec3.ZERO;
    /** 跟随目标的最后已知位置：实体被客户端卸载时用它兜底，避免镜头弹回入场点。 */
    private static Vec3 lastTargetAnchor = Vec3.ZERO;
    private static boolean hasLastTarget;
    private static double lostSeconds;
    private static long lastFrameNanos;
    /** 自检：CameraMixin 每帧经 getAnchor 进入的次数，用来判断相机接管是否真的生效。 */
    private static long cameraFrameCount, lastTickCameraFrameCount, cameraPathDeadSince;
    private static boolean cameraPathWarned;

    private GodView() { }

    public static boolean isActive() { return active; }

    public static boolean isLocked() { return locked; }

    public static float getPitch() { return PITCH; }

    public static float getYaw() { return YAW; }

    public static void activate() { activate(null); }

    public static void activate(Vec3 entryAnchor) {
        if (active) return;
        active = true;
        // 0.38.1：默认自由视角，按住空格才跟随受控宝可梦（用户 m00869 要求）。
        locked = false;
        zoomOffset = 0;
        restoreInputPending = false;
        hasLastTarget = false;
        lostSeconds = 0;
        Minecraft mc = Minecraft.getInstance();
        Entity controlled = mc.level == null ? null : mc.level.getEntity(ClientState.getCharizardId());
        if (controlled != null) {
            freeAnchor = controlled.position().add(0, 1, 0);
        } else if (entryAnchor != null) {
            // 进场时客户端还没拿到实体：先站在服务端给的入场锚点，下一帧实体到了再切过去。
            freeAnchor = entryAnchor;
        } else {
            freeAnchor = mc.player == null ? Vec3.ZERO : mc.player.getEyePosition(1);
        }
        renderAnchor = freeAnchor;
        lastTargetAnchor = freeAnchor;
        hasLastTarget = true;
        initialized = true;
        panVelocity = Vec3.ZERO;
        lastFrameNanos = System.nanoTime();
        cameraPathDeadSince = 0;
        cameraPathWarned = false;
        lastTickCameraFrameCount = cameraFrameCount;
        // 0.41.2：上帝视角要看得到自己的本体 —— 第一人称下原版根本不渲染本地玩家，
        // 切到第三人称后模型才会被 PlayerRenderer 画出来（相机位置仍由 GodView 接管）。
        if (previousCameraType == null) {
            previousCameraType = mc.options.getCameraType();
        }
        mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        mc.gameRenderer.setRenderHand(false);
        mc.mouseHandler.releaseMouse();
    }

    /** 进上帝视角前的相机模式，退出时还原。 */
    private static net.minecraft.client.CameraType previousCameraType;

    public static void deactivate() {
        // false 确认必须幂等：即使本地早已 inactive，也仍修复鼠标与输入残留。
        active = false;
        locked = false;
        panVelocity = Vec3.ZERO;
        hasLastTarget = false;
        lostSeconds = 0;
        cameraPathDeadSince = 0;
        Minecraft mc = Minecraft.getInstance();
        if (previousCameraType != null) {
            mc.options.setCameraType(previousCameraType);
            previousCameraType = null;
        }
        mc.gameRenderer.setRenderHand(true);
        restoreInputPending = true;
        restoreInputTicks = 5;
        InputHandler.clearSuppressedInput(mc);
        restorePlayerControl(mc);
    }

    public static void reset() {
        if (active) Minecraft.getInstance().gameRenderer.setRenderHand(true);
        active = false;
        locked = false;
        initialized = false;
        zoomOffset = 0;
        restoreInputPending = false;
        freeAnchor = renderAnchor = panVelocity = Vec3.ZERO;
        lastTargetAnchor = Vec3.ZERO;
        hasLastTarget = false;
        lostSeconds = 0;
        lastFrameNanos = 0;
        cameraPathDeadSince = 0;
        cameraPathWarned = false;
    }

    /**
     * 空格键：按住 = 镜头跟随受控宝可梦，松手 = 自由视角（边缘推屏平移）。
     *
     * <p>0.38.1 按用户要求改回"按住才跟随"：默认自由，按住空格镜头才贴住宝可梦。
     */
    public static void setFreePanFromInput(boolean held) {
        Minecraft mc = Minecraft.getInstance();
        // 硬保险（0.38.2）：跟随必须同时满足"已激活 + 没开 GUI + 没暂停"。
        // 输入侧即使送来脏值（失步的按键状态、旧版本的调用点），locked 也绝不可能被顶成 true。
        boolean wantLocked = held && active && mc.screen == null && !mc.isPaused();
        if (locked == wantLocked) return;
        // 切到自由平移时以当前镜头位置为锚点，避免跳变；切回跟随时交给插值平滑过去。
        if (!wantLocked) freeAnchor = renderAnchor;
        locked = wantLocked;
        panVelocity = Vec3.ZERO;
    }

    /**
     * 硬保险：任何一帧/任何一 tick 发现"当前不该跟随却还在跟随"，立即退出跟随，
     * 不等下一次输入事件（GUI 打开、失焦暂停、被别的代码复位 active 都可能发生在两 tick 之间）。
     */
    private static void enforceLockable(Minecraft mc) {
        if (!locked) return;
        if (active && mc.screen == null && !mc.isPaused()) return;
        locked = false;
        panVelocity = Vec3.ZERO;
        if (active) freeAnchor = renderAnchor;
    }

    /**
     * 自检（0.38.2）：相机接管正常时每帧都会经 {@code CameraMixin} 调一次 {@link #getAnchor}。
     * 视角已激活却连续 1.5 秒一帧都没进来，说明 {@code Camera.setup} 根本没被接管
     * （注入被别的模组挤掉或失败；{@code pkmoba.mixins.json} 的 {@code injectors.defaultRequire: 0}
     * 会让注入失败完全静默）。这种情况下用户看到的其实是原版相机在跟随"被服务端钉在受控宝可梦
     * 上方 12 格的本体"，表现与"上帝视角一直跟着模型"一模一样，但与空格键无关 —— 靠这行日志区分。
     */
    private static void checkCameraPath(Minecraft mc) {
        if (cameraFrameCount != lastTickCameraFrameCount) {
            lastTickCameraFrameCount = cameraFrameCount;
            cameraPathDeadSince = 0;
            return;
        }
        if (!mc.isWindowActive()) return; // 最小化/失焦时不渲染，不算异常
        long now = System.nanoTime();
        if (cameraPathDeadSince == 0) {
            cameraPathDeadSince = now;
            return;
        }
        if (!cameraPathWarned && now - cameraPathDeadSince > 1_500_000_000L) {
            cameraPathWarned = true;
            PkmobaMod.LOGGER.warn("[pkmoba] 上帝视角相机接管未生效：1.5 秒内 Camera.setup 未被接管，"
                    + "用户看到的可能是原版相机跟随被钉住的本体");
        }
    }

    /** 计算本帧跟随锚点；实体丢失时先保持最后位置，超时才退回玩家本体。 */
    private static Vec3 targetAnchor(float partialTick, double dt) {
        Minecraft mc = Minecraft.getInstance();
        Entity target = mc.level == null ? null : mc.level.getEntity(ClientState.getCharizardId());
        if (target != null) {
            Vec3 anchor = target.getPosition(partialTick).add(0, 1, 0);
            lastTargetAnchor = anchor;
            hasLastTarget = true;
            lostSeconds = 0;
            return anchor;
        }
        if (hasLastTarget) {
            lostSeconds += dt;
            if (lostSeconds < LOST_GRACE_SECONDS) return lastTargetAnchor;
        }
        // 实体确实没了（被收回 / 切换物种 / 客户端没追踪到）：平滑退回玩家视角。
        if (mc.player == null) return hasLastTarget ? lastTargetAnchor : renderAnchor;
        return mc.player.getEyePosition(partialTick);
    }

    public static Vec3 getAnchor(float partialTick) {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : Math.max(0, Math.min(0.1, (now - lastFrameNanos) / 1.0E9));
        lastFrameNanos = now;
        Minecraft mc = Minecraft.getInstance();
        enforceLockable(mc);
        cameraFrameCount++;
        if (mc.screen == null && !mc.isPaused()) {
            Vec3 target;
            double rate;
            if (locked) {
                target = targetAnchor(partialTick, dt);
                rate = FOLLOW_RATE;
            } else {
                Vec3 desiredVelocity = SkillHud.isMouseOver() ? Vec3.ZERO : edgeVelocity(mc);
                panVelocity = panVelocity.add(desiredVelocity.subtract(panVelocity).scale(1 - Math.exp(-PAN_RATE * dt)));
                freeAnchor = freeAnchor.add(panVelocity.scale(dt));
                target = freeAnchor;
                rate = PAN_RATE;
            }
            if (!initialized) {
                renderAnchor = target;
                initialized = true;
            }
            renderAnchor = renderAnchor.add(target.subtract(renderAnchor).scale(1 - Math.exp(-rate * dt)));
        } else {
            panVelocity = Vec3.ZERO;
        }
        return renderAnchor;
    }

    public static Vec3 getCameraPos(float partialTick) {
        return getAnchor(partialTick).subtract(lookDirection().scale(getCurrentDistance()));
    }

    public static void adjustZoom(double notches) {
        if (!active) return;
        // 步进随当前距离膨胀：0 附近 1.5 格/格滚轮，拉远后自动变大，128 格约 20 次滚轮到位。
        double step = 1.5 + Math.abs(zoomOffset) * 0.18;
        zoomOffset = Math.max(MIN_DISTANCE - BASE_DISTANCE,
                Math.min(MAX_DISTANCE - BASE_DISTANCE, zoomOffset - notches * step));
    }

    public static double getCurrentDistance() { return BASE_DISTANCE + zoomOffset; }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        enforceLockable(mc);
        if (active) checkCameraPath(mc);
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
