package com.pkmoba.mixin;

import com.pkmoba.client.ClientState;
import com.pkmoba.client.GodView;
import com.pkmoba.network.MoveOrderPayload;
import com.pkmoba.network.PlaceOrderPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鼠标拦截：上帝视角下右键有两种含义 —— 选点召唤 / 点地移动。
 *
 * <p>本版改动：
 * <ul>
 *   <li>移动指令用**精确命中点** {@code hit.getLocation()}，不再取方块中心（用户选 D2-B）；</li>
 *   <li>去掉"点到视野外就推镜头"（用户 X1-A：镜头只允许边缘推屏移动，否则画面会跳）。</li>
 * </ul>
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    /** 上帝视角下滚轮改为缩放镜头（往前滚放大、往后滚缩小）。 */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void pkmoba$godScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (GodView.isActive() && Minecraft.getInstance().screen == null) {
            GodView.adjustZoom(vertical);
            ci.cancel();
        }
    }

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void pkmoba$godPress(long window, int button, int action, int modifiers, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            return;
        }
        boolean god = GodView.isActive();
        boolean placing = ClientState.isPlacementArmed();
        if (!god && !placing) {
            return;
        }

        // HUD mouse presses must not fall through into world orders or rebound attacks.
        if (god && com.pkmoba.client.SkillHud.isMouseOver() && button <= 1 && action == 1) {
            ci.cancel();
            return;
        }
        // button: 0=左键 1=右键；action: 1=按下
        // 右键点地 = 只移动（普攻已删除，不再有"右键点敌人开打"）。
        if (button == 1 && action == 1) {
            BlockHitResult hit = pkmoba$pick(mc);
            if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                if (placing) {
                    if (ClientPlayNetworking.canSend(PlaceOrderPayload.TYPE)) {
                        ClientPlayNetworking.send(new PlaceOrderPayload(hit.getBlockPos()));
                    }
                } else {
                    // 发送"格心+贴地高度"，而不是斜面的精确命中点。
                    // 旧版发 hit.getLocation()：点地板得到 y=floorY+1，点台阶斜面得到空中点，
                    // 服务端寻路会为了够到那个够不着的点绕到旁边再蹭回来 —— 这就是"迂回一下"。
                    Vec3 goal = pkmoba$walkGoal(hit);
                    ClientState.setClickedPoint(goal);
                    if (ClientPlayNetworking.canSend(MoveOrderPayload.TYPE)) {
                        ClientPlayNetworking.send(new MoveOrderPayload(goal));
                        com.pkmoba.client.ClickMarker.mark(goal);
                    }
                    pkmoba$maybeSendLanding(goal);
                }
            }
        }
        // 原版 onPress 被取消以阻止攻击/交互，但仍要向所有鼠标绑定派发按下和点击事件。
        // 否则技能重绑到鼠标后会被这里提前吞掉。
        var input = com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(button);
        net.minecraft.client.KeyMapping.set(input, action != 0);
        if (action == 1) net.minecraft.client.KeyMapping.click(input);
        ci.cancel();
    }

    /**
     * 把命中结果转成"可站立的落点"：XZ 取格心，Y 取可站表面高度。
     *
     * <p>为什么不再用 {@code hit.getLocation()}：射线命中的是方块碰撞箱表面。
     * 打到地板顶面时 y = 方块上沿（1.0），寻路会把它当成一个悬空目标；
     * 打到台阶/斜坡的侧面时，XZ 还会落在不可站立的薄边上。
     * 统一成"格心 + 站立面高度"后，落点和寻路节点天然对齐，不会再为了够点而绕路。
     */
    /** 倒计时期间点地 = 选定出战落点（方案 §三.2）。 */
    private static void pkmoba$maybeSendLanding(Vec3 goal) {
        if (!com.pkmoba.client.ClientRooms.inCountdown()) return;
        int roomId = com.pkmoba.client.ClientRooms.myRoomId();
        if (roomId == 0) return;
        com.pkmoba.client.ClientRooms.send("LANDING", roomId,
                goal.x + " " + goal.y + " " + goal.z);
    }

    private static Vec3 pkmoba$walkGoal(BlockHitResult hit) {
        net.minecraft.core.BlockPos pos = hit.getBlockPos();
        net.minecraft.world.level.Level level = Minecraft.getInstance().level;
        boolean onSurface = hit.getDirection() == net.minecraft.core.Direction.UP;
        if (level != null && onSurface) {
            var box = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    ? null : level.getBlockState(pos).getCollisionShape(level, pos).bounds();
            double top = box == null ? pos.getY() : pos.getY() + box.maxY;
            return new Vec3(pos.getX() + 0.5, top, pos.getZ() + 0.5);
        }
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }

    /** 从镜头 + 光标位置（上帝视角）或玩家视线（普通视角）做一次射线。 */
    private static BlockHitResult pkmoba$pick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return null;
        }

        Vec3 start;
        Vec3 dir;

        if (GodView.isActive()) {
            Camera cam = mc.gameRenderer.getMainCamera();
            double[] ndc = GodView.cursorNdc(mc);

            var look = cam.getLookVector();
            var up = cam.getUpVector();
            var left = cam.getLeftVector();
            Vec3 forward = new Vec3(look.x(), look.y(), look.z());
            Vec3 upV = new Vec3(up.x(), up.y(), up.z());
            Vec3 leftV = new Vec3(left.x(), left.y(), left.z());

            double tanHalf = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
            double aspect = (double) mc.getWindow().getWidth() / (double) mc.getWindow().getHeight();
            // 符号说明（这里错过一次，导致"点上方却走到下方"）：
            //   ndcX > 0 表示光标在右半屏，要往屏幕右偏 → left 向量指左，所以取 -ndcX；
            //   ndcY > 0 表示光标在上半屏，要往屏幕上偏 → up 向量指上，必须取 +ndcY。
            //   原来写成 -ndcY，垂直方向被翻转，表现为"上下颠倒"。
            dir = forward
                    .add(leftV.scale(-ndc[0] * tanHalf * aspect))
                    .add(upV.scale(ndc[1] * tanHalf))
                    .normalize();
            start = cam.getPosition();
        } else {
            start = mc.player.getEyePosition(1.0F);
            dir = mc.player.getLookAngle();
        }

        return mc.level.clip(new ClipContext(start, start.add(dir.scale(256.0)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
    }
}
