package com.pkmoba.mixin;

import com.pkmoba.client.GodView;
import com.pkmoba.client.HitFeedback;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 相机接管：① 上帝视角固定俯角/跟随；② 命中反馈的震屏与 R 大招推镜。
 *
 * <p>为什么两件事必须在<b>同一个</b> {@code @Inject} 里做：Mixin 对同一注入点的多个
 * 回调执行顺序是声明顺序，但"GodView 先 setPosition、HitFeedback 后 add"这种依赖顺序
 * 靠两个独立 {@code @Inject} 保证不了（一旦顺序反了，震屏会被 GodView 的
 * {@code setPosition} 整个覆盖掉，表现为"命中完全没手感"）。所以这里先做上帝视角接管，
 * 再在它算出的基准位置上叠加命中反馈，顺序是代码顺序，确定性的。
 *
 * <p>注入点仍是 {@code Camera.setup} 的 TAIL：此时原版已经算好 rotation 和 position。
 * 两个效果都在 {@link HitFeedback} 内部按剩余 tick 衰减，所以效果结束时下一帧立刻回到
 * 原版视角，不存在"卡住"状态。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("TAIL"))
    private void pkmoba$cameraEffects(BlockGetter level, Entity entity, boolean detached,
                                      boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity != mc.player) {
            return;
        }
        // 目标类里的 getter 是 public，直接强转调用，省掉 @Shadow 的匹配风险。
        Camera self = (Camera) (Object) this;

        // ① 上帝视角：固定俯角与朝向，再按固定距离摆放位置（保持原逻辑不变）
        boolean godView = GodView.isActive();
        if (godView) {
            this.setRotation(GodView.getYaw(), GodView.getPitch());
            this.setPosition(GodView.getCameraPos(partialTick));
        }

        // ② 命中反馈：先叠旋转抖动，再算沿视线的推镜，最后叠位移抖动
        float shakeYaw = HitFeedback.shakeYaw(partialTick);
        float shakePitch = HitFeedback.shakePitch(partialTick);
        if (shakeYaw != 0.0F || shakePitch != 0.0F) {
            this.setRotation(self.getYRot() + shakeYaw, self.getXRot() + shakePitch);
        }

        Vec3 position = self.getPosition();
        boolean moved = false;
        float dolly = HitFeedback.cameraDolly(partialTick);
        if (dolly > 0.0005F) {
            // R 大招推镜：沿当前视线方向把镜头往前挪（不改 FOV，不影响其他渲染）
            Vector3f look = self.getLookVector();
            position = position.add(look.x() * dolly, look.y() * dolly, look.z() * dolly);
            moved = true;
        }
        Vec3 shake = HitFeedback.shakeOffset(self, partialTick);
        double shakeLength = shake.lengthSqr();
        if (shakeLength > 1.0E-8) {
            position = position.add(shake);
            moved = true;
        }
        if (moved) {
            this.setPosition(position);
        }
    }
}
