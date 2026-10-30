package com.pkmoba.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;

/** Dispatch physical input to every binding, not only the last entry in vanilla's MAP. */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
    @Shadow @Final
    private static Map<String, KeyMapping> ALL;

    @Shadow
    private InputConstants.Key key;

    @Shadow
    private int clickCount;

    /**
     * 把一次物理按键点击广播给所有绑定同一按键的 KeyMapping（原版只喂给 MAP 里的"最后一个"）。
     *
     * <p><b>0.38.3 排查结论：</b>这里本身一次事件只 +1，没有重复计数。真正导致"长按技能
     * 执行一下又取消"的是原版 {@code KeyboardHandler.keyPress}：GLFW 自动重复（action=2）时
     * 它同样会走 {@code KeyMapping.set(key,true)} + {@code KeyMapping.click(key)}，
     * 于是按住期间 click 队列不断累积。修法放在消费端（{@code SkillKeys} 改成按下边沿触发），
     * 本类保持"一次事件一次广播"的语义不动。
     */
    @Inject(method = "click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void pkmoba$broadcastClick(InputConstants.Key input, CallbackInfo ci) {
        for (KeyMapping mapping : ALL.values()) {
            KeyMappingMixin binding = (KeyMappingMixin) (Object) mapping;
            if (binding.key.equals(input)) {
                binding.clickCount++;
            }
        }
        // Do not let vanilla increment the MAP winner a second time.
        ci.cancel();
    }

    @Inject(method = "set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void pkmoba$broadcastSet(InputConstants.Key input, boolean down, CallbackInfo ci) {
        for (KeyMapping mapping : ALL.values()) {
            KeyMappingMixin binding = (KeyMappingMixin) (Object) mapping;
            if (binding.key.equals(input)) {
                // Preserve virtual dispatch, including ToggleKeyMapping semantics.
                mapping.setDown(down);
            }
        }
        ci.cancel();
    }
}
