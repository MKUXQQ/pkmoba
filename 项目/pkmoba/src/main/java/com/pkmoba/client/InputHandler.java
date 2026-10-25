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
    public static final String KEY_ROOM_LOBBY = "key.pkmoba.room_lobby";

    private static KeyMapping godViewKey;
    private static KeyMapping lockCameraKey;
    private static KeyMapping switchPokemonKey;
    private static KeyMapping summonPokemonKey;
    private static KeyMapping roomLobbyKey;

    private InputHandler() {
    }

    public static void init() {
        CameraSettings.load();
        CameraCommands.init();
        godViewKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_GOD_VIEW, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, KEY_CATEGORY));
        // 默认空格；和跳跃同键。上帝视角下本体由服务端锚点驱动，误跳没有实际影响。
        lockCameraKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_LOCK_CAMERA, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SPACE, KEY_CATEGORY));
        // 手动换宝可梦：默认 C（0.31 起从 G 改成 C，避免和常用键冲突）
        switchPokemonKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_SWITCH_POKEMON, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, KEY_CATEGORY));
        // X：把宝可梦召唤到准星指向的地面点
        summonPokemonKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_SUMMON_POKEMON, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, KEY_CATEGORY));

        roomLobbyKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_ROOM_LOBBY, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, KEY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(InputHandler::onClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            GodView.reset();
            SwitchFx.reset();
        });
    }

    private static void onClientTick(Minecraft mc) {
        // 房间开局倒计时：数字键选宝可梦（落点由右键点击发送）
        ClientRooms.tickCountdownInput(mc);
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

        // X：在玩家正前方 3 格召唤当前选中的宝可梦（0.40 起取消"召唤到准星点"的旧行为）
        while (summonPokemonKey.consumeClick()) {
            if (mc.screen == null && mc.player != null && mc.level != null
                    && ClientPlayNetworking.canSend(com.pkmoba.network.SummonPayload.TYPE)) {
                net.minecraft.world.phys.Vec3 look = mc.player.getLookAngle();
                net.minecraft.world.phys.Vec3 flat = new net.minecraft.world.phys.Vec3(look.x, 0, look.z);
                if (flat.lengthSqr() < 1.0E-4) flat = new net.minecraft.world.phys.Vec3(0, 0, 1);
                flat = flat.normalize().scale(5.0);
                net.minecraft.world.phys.Vec3 point = mc.player.position().add(flat);
                ClientPlayNetworking.send(new com.pkmoba.network.SummonPayload(
                        point.x, point.y, point.z));
            }
        }

        // H：打开房间大厅（房间系统唯一入口；点位配置仍走道具/命令）
        while (roomLobbyKey.consumeClick()) {
            if (mc.screen == null && mc.player != null) {
                com.pkmoba.client.ClientRooms.openLobby();
            }
        }

        if (GodView.isActive()) {
            // 0.38.2：默认自由视角；按住空格（KEY_LOCK_CAMERA）镜头才跟随受控宝可梦，松手回到自由。
            // 关键：这里不看 lockCameraKey.isDown()。那个字段只是按键事件流缓存下来的布尔值，
            // 只要有一次 key-up 没送到（失焦、Alt+Tab、输入法/驱动吞键、grabMouse 的 setAll 与
            // releaseAll 交错），它就会永久卡在 true，'跟随'再也退不出来 —— 用户 m00869 反馈的
            // "上帝视角一直跟着模型" 就是这种失步。物理状态每 tick 重新采样，不存在失步。
            boolean following = mc.screen == null && isLockKeyPhysicallyDown(mc);
            GodView.setFreePanFromInput(following);
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

    /**
     * 直接轮询 GLFW 的物理按键状态，完全绕开 {@link KeyMapping#isDown()} 的事件缓存。
     *
     * <p>键盘绑定用 {@link InputConstants#isKeyDown(long, int)}（内部就是 {@code glfwGetKey}），
     * 鼠标绑定用 {@code GLFW.glfwGetMouseButton}；只有绑定被改成 scancode 时才退回
     * {@code KeyMapping.isDown()} —— GLFW 没有"按 scancode 查当前状态"的公开 API。
     * 绑定不可解析（UNKNOWN/负值，常见于配置丢失或旧 options.txt）时再回退到空格，
     * 保证改键或丢配置后仍能按出跟随。
     */
    private static boolean isLockKeyPhysicallyDown(Minecraft mc) {
        if (lockCameraKey == null || mc.getWindow() == null) return false;
        long window = mc.getWindow().getWindow();
        InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(lockCameraKey);
        Boolean polled = switch (key.getType()) {
            case KEYSYM -> key.getValue() >= 0 ? InputConstants.isKeyDown(window, key.getValue()) : null;
            case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            case SCANCODE -> null;
        };
        if (polled != null) return polled;
        return lockCameraKey.isDown() || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_SPACE);
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
