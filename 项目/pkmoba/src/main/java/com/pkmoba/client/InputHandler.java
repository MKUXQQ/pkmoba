package com.pkmoba.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pkmoba.PkmobaMod;
import com.pkmoba.network.GodTogglePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端输入入口：Z 开关上帝视角、空格按住锁定镜头。
 *
 * <p>Z 不在本地改状态，只发 C2S 请求；真正的开关由服务端决定（服务端权威）。
 * 空格锁定是纯客户端行为，不需要服务端参与。
 */
public final class InputHandler {
    public static final String KEY_CATEGORY = "category.pkmoba";
    public static final String KEY_GOD_VIEW = "key.pkmoba.god_view";
    public static final String KEY_LOCK_CAMERA = "key.pkmoba.lock_camera";
    public static final String KEY_SWITCH_POKEMON = "key.pkmoba.switch_pokemon";
    public static final String KEY_SUMMON_POKEMON = "key.pkmoba.summon_pokemon";

    private static KeyMapping godViewKey;
    private static KeyMapping lockCameraKey;
    private static KeyMapping switchPokemonKey;
    private static KeyMapping summonPokemonKey;

    private InputHandler() {
    }

    public static void init() {
        CameraSettings.load();
        CameraCommands.init();
        godViewKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_GOD_VIEW, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, KEY_CATEGORY));
        // 默认空格；和跳跃同键，但上帝视角下移动输入被 KeyboardInputMixin 吃掉了，不会误跳。
        lockCameraKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_LOCK_CAMERA, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SPACE, KEY_CATEGORY));
        // 手动换宝可梦：默认 C（0.31 起从 G 改成 C，避免和常用键冲突）
        switchPokemonKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_SWITCH_POKEMON, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, KEY_CATEGORY));
        // X：把宝可梦召唤到准星指向的地面点
        summonPokemonKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_SUMMON_POKEMON, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, KEY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(InputHandler::onClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            GodView.reset();
            SwitchFx.reset();
        });
    }

    private static void onClientTick(Minecraft mc) {
        // 换人特效的时间轴（纯客户端表现，不影响服务端状态）
        SwitchFx.tick(mc);

        // 映射层为同键的所有功能分别入队；消费队列也不会丢失两 tick 间的快按。
        while (godViewKey.consumeClick()) {
            if (mc.screen == null) requestToggle(mc);
        }

        while (lockCameraKey.consumeClick()) { }

        // 手动换宝可梦：0.35 起不再要求上帝视角——平时也能按 C 收回 / 放出下一只。
        // 具体能不能换、换成谁，全部由服务端 TeamService.switchTarget / advanceSelection 决定。
        while (switchPokemonKey.consumeClick()) {
            if (mc.screen == null && mc.player != null
                    && ClientPlayNetworking.canSend(com.pkmoba.network.SwitchPokemonPayload.TYPE)) {
                // 先本地给反馈，不等服务端往返
                SwitchFx.onPress();
                ClientPlayNetworking.send(new com.pkmoba.network.SwitchPokemonPayload());
            }
        }

        // X：召唤到准星指向的地面点（服务端做距离与落点安全校验）
        while (summonPokemonKey.consumeClick()) {
            if (mc.screen == null && mc.player != null && mc.level != null
                    && ClientPlayNetworking.canSend(com.pkmoba.network.SummonPayload.TYPE)) {
                net.minecraft.world.phys.BlockHitResult aim = AimRay.pick(mc);
                net.minecraft.world.phys.Vec3 point = aim == null ? null : aim.getLocation();
                if (point != null) {
                    ClientPlayNetworking.send(new com.pkmoba.network.SummonPayload(
                            point.x, point.y, point.z));
                }
            }
        }

        if (GodView.isActive()) {
            boolean held = mc.screen == null && lockCameraKey.isDown();
            GodView.setLockedFromInput(held, false);
        }
        // 退出遇到 GUI/失焦时，GodView 会在重新回到游戏后完成鼠标和输入恢复。
        GodView.tick();
    }

    /** 清除被取消的最后一帧；不替换其他模组可能安装的 Input 实例。 */
    public static void clearSuppressedInput(Minecraft mc) {
        if (mc.player == null) return;
        net.minecraft.client.player.Input input = mc.player.input;
        input.forwardImpulse = input.leftImpulse = 0;
        input.up = input.down = input.left = input.right = input.jumping = input.shiftKeyDown = false;
    }

    /** 退出后短暂多 tick 采样，覆盖解锁/窗口焦点恢复的先后顺序。 */
    public static void restorePlayerInput(Minecraft mc) {
        if (mc.screen != null || mc.player == null) return;
        KeyMapping[] movement = {mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft,
                mc.options.keyRight, mc.options.keyJump, mc.options.keyShift, mc.options.keySprint};
        long window = mc.getWindow().getWindow();
        for (KeyMapping mapping : movement) {
            InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mapping);
            boolean down = switch (key.getType()) {
                case KEYSYM -> key.getValue() >= 0 && InputConstants.isKeyDown(window, key.getValue());
                case SCANCODE -> mapping.isDown();
                case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            };
            // ToggleKeyMapping 已保留切换状态，重复 setDown(true) 会反向切换。
            if (!(mapping instanceof net.minecraft.client.ToggleKeyMapping)) mapping.setDown(down);
        }
        mc.player.input.tick(false, 1.0F);
    }

    private static void requestToggle(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) {
            return;
        }
        if (!ClientPlayNetworking.canSend(GodTogglePayload.TYPE)) {
            PkmobaMod.LOGGER.warn("[pkmoba] 服务端未注册 god_toggle 通道，无法切换上帝视角");
            return;
        }
        ClientPlayNetworking.send(new GodTogglePayload());
    }
}
