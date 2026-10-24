package com.pkmoba.mixin;

import com.pkmoba.client.GodView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 上帝视角相机接管：平滑跟随受控宝可梦，并应用边缘推屏偏移。
 *
 * <p>为什么连朝向也要改（用户选 C5-A）：原版 setup 会用玩家视角算 rotation，
 * 而上帝视角下镜头必须永远保持同一个俯角/朝向，否则会跟鼠标一起转，非常晕。
 *
 * <p>注入点是 {@code Camera.setup} 的 TAIL：此时原版已经算好 rotation 和 position，
 * 上帝视角激活时再替换为固定俯角和跟随坐标，非上帝视角完全不干预。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("TAIL"))
    private void pkmoba$godCamera(BlockGetter level, Entity entity, boolean detached,
                                  boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        if (!GodView.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity != mc.player) {
            return;
        }
        // 固定俯角与朝向，再按固定距离摆放位置
        this.setRotation(GodView.getYaw(), GodView.getPitch());
        this.setPosition(GodView.getCameraPos(partialTick));
    }
}
