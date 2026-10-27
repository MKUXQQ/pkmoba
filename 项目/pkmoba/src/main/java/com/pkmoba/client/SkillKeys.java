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
    /** 上一 tick 的物理按下状态，用于把施放改成"按下边沿触发"（长按不重复施放）。 */
    private static final boolean[] KEY_DOWN = new boolean[4];
    private static int lastEntityId = -1;
    private static int aimedSkill = -1;
    private static long aimUntil;

    /** 最近使用/正在引导的技能用于显示实际攻击覆盖，超时退回普攻。 */
    public static int getAimedSkillIndex() {
        if (!GodView.isActive()) return -1;
        int slot = slotForChannel(ClientState.getChannel());
        if (slot >= 0) return slot;
        KeyMapping[] keys = {keyQ, keyW, keyE, keyR};
        for (int i = 0; i < keys.length; i++) if (keys[i] != null && keys[i].isDown()) return i;
        return System.nanoTime() < aimUntil ? aimedSkill : -1;
    }

    /**
     * channel → 技能槽位（纯函数，按物种区分）。
     *
     * <p>0.38 槽位重排后 channel 号不再通用：水箭龟 Q/W/E/R = channel 4/3/5/6；
     * 喷火龙只有 Q/R 会引导（channel 1/4，其中 R 是 4），飞翔用的 channel 3 没有槽位。
     * 瞄准预览和 HUD 都读这一份映射，任何一方都不许再自己写死 channel 号。
     *
     * @param speciesId 物种 id（可带/不带 {@code cobblemon:} 前缀，未召唤传 null 按喷火龙处理）
     * @return 槽位 0..3，无对应槽位返回 -1
     */
    public static int slotForChannel(String speciesId, int channel) {
        if (channel == 0) return -1;
        if (com.pkmoba.combat.Skill.isPikachu(speciesId)) return channel >= 11 && channel <= 14 ? channel - 11 : -1;
        if (com.pkmoba.combat.Skill.isVenusaur(speciesId)) {
            return switch (channel) {
                case 7 -> 0;
                case 8 -> 1;
                case 9 -> 2;
                case 10 -> 3;
                default -> -1;
            };
        }
        if (com.pkmoba.combat.Skill.isBlastoise(speciesId)) {
            return switch (channel) {
                case 4 -> 0;
                case 3 -> 1;
                case 5 -> 2;
                case 6 -> 3;
                default -> -1;
            };
        }
        return switch (channel) {
            case 1 -> 0;
            case 2 -> 1;
            case 3 -> 2;
            case 4 -> 3;
            default -> -1;
        };
    }

    private static int slotForChannel(int channel) {
        return slotForChannel(controllingSpeciesId(), channel);
    }

    /** 当前受控物种 id；未召唤时退回服务端下发的已选宝可梦。 */
    public static String controllingSpeciesId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && ClientState.getCharizardId() >= 0) {
            var entity = mc.level.getEntity(ClientState.getCharizardId());
            if (entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon) {
                return pokemon.getPokemon().getSpecies().getResourceIdentifier().toString();
            }
        }
        return ClientState.getTeamActive();
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
            java.util.Arrays.fill(KEY_DOWN, false);
            clearAimPreview();
            lastEntityId = -1;
            return;
        }
        int entityId = ClientState.getCharizardId();
        if (lastEntityId != entityId) {
            lastEntityId = entityId;
            // 换实体时重新建立按下基线：切换那一帧按住不放的键不算"新的一次按下"。
            java.util.Arrays.fill(KEY_DOWN, false);
            clearAimPreview();
        }
        Vec3 aim = aimPoint(mc);

        // 0.38.1：服务端要让模型时刻缓慢转向鼠标，所以只要处于受控状态就每 tick 上报瞄准点，
        // 不再只在引导中（channel != 0）才发。
        if (ClientPlayNetworking.canSend(AimPayload.TYPE)) {
            ClientPlayNetworking.send(new AimPayload(aim.x, aim.y, aim.z));
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
        return species.equals("cobblemon:charizard") || species.equals("cobblemon:blastoise")
                || species.equals("cobblemon:venusaur") || species.equals("cobblemon:pikachu");
    }

    /**
     * 取本次施法的瞄准点。
     *
     * <p>0.39.5（用户 m03653）："锁定敌人的时候就往敌人的方向释放，不跟随鼠标"。
     * 所以只要光标锁着一个敌人（{@link Targeting#hoveredTarget}），就用敌人身上那个点；
     * 没有锁定目标时才退回鼠标射线点。
     *
     * <p>方向在"按下技能"这一刻就被服务端记进 channels 里（见 CharizardCombat 的
     * {@code channelAim}），引导期间不再被鼠标覆盖 —— 也就是"朝第一次释放的位置"。
     */
    private static Vec3 aimPoint(net.minecraft.client.Minecraft mc) {
        net.minecraft.world.entity.LivingEntity locked = Targeting.hoveredTarget(mc);
        if (locked != null && locked.isAlive()) {
            return locked.position().add(0.0, locked.getBbHeight() * 0.55, 0.0);
        }
        return AimRay.aimPoint(mc);
    }

    private static void fire(KeyMapping key, int index, Vec3 aim) {
        // 边沿触发：只有"本 tick 由松变按"的那一帧发一次 SkillCastPayload。
        //
        // <p>为什么不能再用 while (consumeClick())：原版 {@code KeyboardHandler.keyPress} 在 GLFW
        // 自动重复（action=2）时同样会调用 {@code KeyMapping.click}（已 javap 核对），长按期间
        // clickCount 会不断累积；逐个发送会让服务端把同技能的第二次施放当成"取消引导"
        // （{@code CharizardCombat.cast} 里 channel 非 0 且同技能 → channel = 0），表现就是
        // "长按执行了一下又取消"。现在长按只在第一次按下时施放，按住期间不再重复发。
        //
        // <p>松开时什么都不发：引导技能由服务端计时自然结束（channel 1/4 有各自的上限 tick），
        // 不主动发取消包。
        InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(key);
        long window = Minecraft.getInstance().getWindow().getWindow();
        // W also belongs to vanilla movement; poll the binding instead of its shared event cache.
        boolean down = switch (bound.getType()) {
            case KEYSYM -> bound.getValue() >= 0 && InputConstants.isKeyDown(window, bound.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(window, bound.getValue()) == GLFW.GLFW_PRESS;
            case SCANCODE -> key.isDown();
        };
        boolean pressed = down && !KEY_DOWN[index];
        KEY_DOWN[index] = down;
        // 点击队列只清空、不再逐个施放：长按重复的 click 直接丢弃，避免残留到下一 tick 造成二次触发。
        while (key.consumeClick()) { }
        if (!pressed) return;
        aimedSkill = index;
        aimUntil = System.nanoTime() + 1_200_000_000L;
        if (ClientPlayNetworking.canSend(SkillCastPayload.TYPE)) {
            ClientPlayNetworking.send(new SkillCastPayload(index, aim.x, aim.y, aim.z));
        }
    }
}
