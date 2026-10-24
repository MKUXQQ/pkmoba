package com.pkmoba.mixin;

import com.pkmoba.client.GodView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 上帝视角下吃掉键盘移动输入。
 *
 * 为什么在 KeyboardInput.tick 的 HEAD 处 cancel：
 * tick(boolean, float) 每 tick 都会按当前按键重新计算 forwardImpulse / leftImpulse / jumping，
 * 事后清零会被下一 tick 直接覆盖，等于没做。只有在这里清零并取消才能保证这一 tick 输入恒为 0。
 *
 * 这只是客户端手感辅助：真正拦住玩家的是服务端每 tick 回写位置（PlayerLockService）。
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void pkmoba$lockMovementInput(boolean isMoving, float partialTick, CallbackInfo ci) {
        if (!GodView.isActive()) {
            return;
        }
        // 锁定期间包括 GUI 帧都写零；退出后完全交回原版 tick，不能留下半帧输入。
        Input self = (Input) (Object) this;
        self.forwardImpulse = 0.0F;
        self.leftImpulse = 0.0F;
        self.up = false;
        self.down = false;
        self.left = false;
        self.right = false;
        self.jumping = false;
        self.shiftKeyDown = false;
        ci.cancel();
    }
}
