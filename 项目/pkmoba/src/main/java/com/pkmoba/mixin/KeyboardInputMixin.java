package com.pkmoba.mixin;

import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 0.38：不再吞掉上帝视角下的键盘移动输入。
 *
 * <p>旧版在这里 cancel {@code KeyboardInput.tick} 并清零 forwardImpulse 等字段，因为那时本体被
 * "钉"在升空点，移动没意义、还会让空格误跳。现在本体是挂在受控宝可梦上方的动态锚点
 * （{@code lock/PlayerLockService}），位置每个服务端 tick 由宝可梦位置覆盖，客户端按键不会把
 * 玩家带偏；继续吞输入反而让 W/A/S/D 手感像坏了。因此本 mixin 只保留类名
 * （{@code pkmoba.mixins.json} 里仍登记着它），不再注入任何逻辑。
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
}
