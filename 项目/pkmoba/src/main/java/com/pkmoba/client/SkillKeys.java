package com.pkmoba.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pkmoba.network.AimPayload;
import com.pkmoba.network.SkillCastPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * QWER 技能按键。只在上帝视角下生效，避免和原版背包/丢物品等操作打架。
 */
public final class SkillKeys {
    public static final String CATEGORY = "category.pkmoba";

    private static KeyMapping keyQ, keyW, keyE, keyR;
    private static int lastEntityId = -1;
    /** 引导期间的上报节流计数。 */
    private static int aimTick = 0;
    private static int aimedSkill = -1;
    private static long aimUntil;

    /** 最近使用/正在引导的技能用于显示实际攻击覆盖，超时退回普攻。 */
    public static int getAimedSkillIndex() {
        if (!GodView.isActive()) return -1;
        int channel = ClientState.getChannel();
        if (channel == 1) return 0;
        if (channel == 4) return 3;
        KeyMapping[] keys = {keyQ, keyW, keyE, keyR};
        for (int i = 0; i < keys.length; i++) if (keys[i] != null && keys[i].isDown()) return i;
        return System.nanoTime() < aimUntil ? aimedSkill : -1;
    }

    public static void clearAimPreview() {
        aimedSkill = -1;
        aimUntil = 0;
    }

    private SkillKeys() {
    }

    public static void init() {
        keyQ = reg("key.pkmoba.skill_q", GLFW.GLFW_KEY_Q);
        keyW = reg("key.pkmoba.skill_w", GLFW.GLFW_KEY_W);
        keyE = reg("key.pkmoba.skill_e", GLFW.GLFW_KEY_E);
        keyR = reg("key.pkmoba.skill_r", GLFW.GLFW_KEY_R);
        ClientTickEvents.END_CLIENT_TICK.register(SkillKeys::tick);
    }

    private static KeyMapping reg(String name, int key) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyMapping(name, InputConstants.Type.KEYSYM, key, CATEGORY));
    }

    private static void tick(Minecraft mc) {
        if (!GodView.isActive() || mc.player == null || mc.screen != null || !hasSupportedControlledPokemon(mc)) {
            // 在界面/非上帝视角期间丢弃技能点击，避免关界面后延迟施放。
            for (KeyMapping key : new KeyMapping[]{keyQ, keyW, keyE, keyR}) {
                while (key.consumeClick()) { }
            }
            clearAimPreview();
            lastEntityId = -1;
            aimTick = 0;
            return;
        }
        int entityId = ClientState.getCharizardId();
        if (lastEntityId != entityId) {
            lastEntityId = entityId;
            clearAimPreview();
        }
        Vec3 aim = AimRay.aimPoint(mc);

        // 引导期间持续上报瞄准点，服务端才能每 tick 转向并重算扇形（Q 跟随鼠标的关键）
        if (ClientState.getChannel() != 0) {
            if (aimTick++ % 2 == 0 && ClientPlayNetworking.canSend(AimPayload.TYPE)) {
                ClientPlayNetworking.send(new AimPayload(aim.x, aim.y, aim.z));
            }
        } else {
            aimTick = 0;
        }

        fire(keyQ, 0, aim);
        fire(keyW, 1, aim);
        fire(keyE, 2, aim);
        fire(keyR, 3, aim);
    }

    public static String bindingLabel(int index) {
        KeyMapping key = switch (index) {
            case 0 -> keyQ;
            case 1 -> keyW;
            case 2 -> keyE;
            default -> keyR;
        };
        return key == null ? "?" : key.getTranslatedKeyMessage().getString();
    }

    private static boolean hasSupportedControlledPokemon(Minecraft mc) {
        if (mc.level == null || ClientState.getCharizardId() < 0 || !ClientState.isSkillStateReady()) return false;
        var entity = mc.level.getEntity(ClientState.getCharizardId());
        if (!(entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon) || !pokemon.isAlive()) return false;
        String species = pokemon.getPokemon().getSpecies().getResourceIdentifier().toString();
        return species.equals("cobblemon:charizard") || species.equals("cobblemon:blastoise");
    }

    private static void fire(KeyMapping key, int index, Vec3 aim) {
        // 每个绑定独立的点击队列由 KeyMappingMixin 广播填充，包括快速点按。
        while (key.consumeClick()) {
            aimedSkill = index;
            aimUntil = System.nanoTime() + 1_200_000_000L;
            if (ClientPlayNetworking.canSend(SkillCastPayload.TYPE)) {
                ClientPlayNetworking.send(new SkillCastPayload(index, aim.x, aim.y, aim.z));
            }
        }
    }
}
