package com.pkmoba.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

/**
 * 瞄准射线：从镜头 + 光标位置算出"鼠标指向的世界坐标"。
 *
 * <p>技能释放和点地移动都要用同一套算法，否则会出现"点地和放技能方向不一样"的怪现象。
 */
public final class AimRay {
    private AimRay() {
    }

    /** 返回鼠标指向的世界点；没有命中方块时退化为"沿视线 8 格处"。 */
    public static Vec3 aimPoint(Minecraft mc) {
        BlockHitResult hit = pick(mc);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getLocation();
        }
        if (mc.player == null) {
            return Vec3.ZERO;
        }
        return mc.player.getEyePosition(1.0F).add(mc.player.getLookAngle().scale(8.0));
    }

    public static BlockHitResult pick(Minecraft mc) {
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
