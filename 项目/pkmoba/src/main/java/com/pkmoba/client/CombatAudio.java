package com.pkmoba.client;

import com.pkmoba.combat.Skill;
import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 战斗音效管理：<b>循环音只在状态真正开始/结束时启动/淡出一次，绝不每 tick 重播</b>。
 *
 * <h2>分工</h2>
 * <ul>
 *   <li><b>一次性音</b>（起手/出手/结束/中断/斩杀）由本类在客户端播放，
 *       触发点是服务端权威状态的变化（{@code ClientState.getChannel()} / {@code getCharge()}
 *       的跳变），而不是"本地按键"——所以被服务端拒绝/被打断时表现也正确。</li>
 *   <li><b>命中音</b>由服务端 {@code combat/CombatDamage} 在真实命中的那一刻
 *       {@code level.playSound(...)} 广播（与命中特效同一个 tick），本类只补一个"斩杀"重音，
 *       避免同一次命中响两遍。</li>
 *   <li><b>循环音</b>（喷火、旋涡、冲浪、水柱、引导蓄力）用 {@link LoopInstance} 一直挂着，
 *       音量按 tick 渐入渐出，状态消失时淡到 0 再 {@code stop}，
 *       不存在"每帧 new 一个声音"导致的叠音。</li>
 * </ul>
 *
 * <h2>为什么不用服务端 playSound 做循环</h2>
 * 原版 {@code playSound} 是单次事件，做持续音只能每 tick 发一次 —— 那正是用户明确禁止的
 * "循环音效每帧重播"。客户端持有 {@code SoundInstance} 才能一次播放、随时调音量、结束淡出。
 */
public final class CombatAudio {
    private CombatAudio() { }

    /** 循环音峰值音量（整体压低，避免盖住命中音）。 */
    private static final float PEAK_VOLUME = 0.40F;
    /** 每 tick 渐入/渐出的音量步长。 */
    private static final float FADE_IN_STEP = 0.10F;
    private static final float FADE_OUT_STEP = 0.07F;
    /** 喷火引导的前摇 tick 数（服务端 Q_WINDUP_TICKS=11），用来判断"蓄力 → 喷发"。 */
    private static final int FLAME_WINDUP_TICKS = 11;

    /** 当前需要的循环音种类；由服务端 channel + 受控宝可梦物种推断。 */
    private enum LoopKind {
        NONE, FLAME, FLAME_CHARGE, SPIN, FLY, SURF, SPOUT, CANNON
    }

    private static boolean initialized;
    private static LoopKind current = LoopKind.NONE;
    private static LoopInstance active;
    /** 正在淡出的实例（音量归零后 stop 并丢弃）。 */
    private static LoopInstance fading;

    private static int lastChannel = -1;
    private static int channelTicks;
    private static boolean fireCued;
    private static boolean lastChargeHighOnChannel;
    /** 斩杀重音的最小间隔（tick）：多目标同 tick 死亡时只响一次。 */
    private static final int KILL_SOUND_GAP_TICKS = 8;
    private static long lastKillSoundTick = Long.MIN_VALUE;

    /** 注册 tick。由 {@code PkmobaClient.onInitializeClient()} 调用一次。 */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(CombatAudio::tick);
    }

    /** 断开连接/换世界：全部停掉。 */
    public static void reset() {
        stopQuietly(active);
        stopQuietly(fading);
        active = null;
        fading = null;
        current = LoopKind.NONE;
        lastChannel = -1;
        channelTicks = 0;
        fireCued = false;
        lastChargeHighOnChannel = false;
    }

    /** 命中时调用（来自 {@link HitFeedback}）：只补"斩杀"提示音，命中音服务端已经播过。 */
    public static void onHit(EffectPayload.Element element, EffectPayload.Power power, boolean lethal) {
        if (!lethal || element == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        // 一次大招可能同一 tick 打死多个目标：斩杀重音限流，避免叠成一声爆音
        long now = mc.level.getGameTime();
        if (now - lastKillSoundTick < KILL_SOUND_GAP_TICKS) {
            return;
        }
        lastKillSoundTick = now;
        float pitch = power == EffectPayload.Power.ULTIMATE ? 1.05F : 1.30F;
        playAt(mc, SoundEvents.TOTEM_USE, 0.32F, pitch, mc.player.position());
    }

    /** 每个客户端 tick：按服务端权威状态切循环音 + 一次性事件。 */
    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            reset();
            return;
        }
        int channel = ClientState.getChannel();
        if (channel != lastChannel) {
            onChannelChanged(mc, lastChannel, channel);
            lastChannel = channel;
            channelTicks = 0;
            fireCued = false;
        } else {
            channelTicks++;
        }
        cueFireOnce(mc, channel);
        LoopKind desired = desiredLoop(mc, channel);
        applyLoop(mc, desired);
    }

    // ---------------------------------------------------------------- 状态跳变 → 一次性音

    private static void onChannelChanged(Minecraft mc, int from, int to) {
        // 从引导状态淡出：结束音
        if (from > 0 && to == 0) {
            playAt(mc, SoundEvents.FIRE_EXTINGUISH, 0.35F, 1.15F, mc.player.position());
            return;
        }
        // 一个技能直接换到另一个技能：先给"中断"音，再给新技能的起手音
        if (from > 0 && to > 0) {
            playAt(mc, SoundEvents.FIRE_EXTINGUISH, 0.28F, 0.95F, mc.player.position());
        }
        if (to > 0) {
            playAt(mc, SoundEvents.BEACON_ACTIVATE, 0.30F, 1.20F, mc.player.position());
        }
    }

    /** "蓄力 → 喷发"的那一下：channel 4/6 用 charge 掉回 0 判定，channel 1 用前摇时长判定。 */
    private static void cueFireOnce(Minecraft mc, int channel) {
        if (fireCued || channel <= 0) {
            return;
        }
        boolean chargeHigh = ClientState.getCharge() >= 0.75F;
        boolean fire;
        if (channel == 1) {
            fire = channelTicks >= FLAME_WINDUP_TICKS;
        } else if (channel == 4 || channel == 6) {
            fire = lastChargeHighOnChannel && !chargeHigh;
        } else {
            fire = channelTicks >= 2;
        }
        lastChargeHighOnChannel = chargeHigh;
        if (!fire) {
            return;
        }
        fireCued = true;
        SoundEvent event = switch (channel) {
            case 1 -> SoundEvents.GHAST_SHOOT;
            case 4, 6 -> SoundEvents.GHAST_SHOOT;
            default -> SoundEvents.PLAYER_ATTACK_STRONG;
        };
        playAt(mc, event, 0.55F, channel == 1 ? 0.85F : 1.0F, mc.player.position());
    }

    // ---------------------------------------------------------------- 循环音

    private static LoopKind desiredLoop(Minecraft mc, int channel) {
        if (channel <= 0) {
            return LoopKind.NONE;
        }
        boolean blastoise = controlledIsBlastoise(mc);
        return switch (channel) {
            // 喷火龙 Q 喷射火焰
            case 1 -> LoopKind.FLAME;
            // 喷火龙 E 冲刺/飞行 与 水箭龟 W 旋转 共用 channel 3：按物种分开
            case 3 -> blastoise ? LoopKind.SPIN : LoopKind.FLY;
            // 喷火龙 R 蓄力（channel 4）= 火球聚气；水箭龟 channel 4 = 加农水炮束流
            case 4 -> blastoise ? LoopKind.CANNON : LoopKind.FLAME_CHARGE;
            // 水箭龟 E 冲浪
            case 5 -> blastoise ? LoopKind.SURF : LoopKind.NONE;
            // 水箭龟 R 喷水
            case 6 -> LoopKind.SPOUT;
            default -> LoopKind.NONE;
        };
    }

    private static boolean controlledIsBlastoise(Minecraft mc) {
        if (!ClientState.isSkillStateReady() || mc.level == null) {
            return false;
        }
        Entity entity = mc.level.getEntity(ClientState.getCharizardId());
        return entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
                && Skill.isBlastoise(pokemon);
    }

    /** 把实际播放的循环音切到 {@code desired}：新音渐入，旧音渐出后 stop。 */
    private static void applyLoop(Minecraft mc, LoopKind desired) {
        Vec3 anchor = mc.player == null ? Vec3.ZERO : mc.player.position();
        if (desired != current) {
            // 旧的开始淡出
            if (active != null) {
                fading = active;
                active = null;
            }
            current = desired;
            if (desired != LoopKind.NONE) {
                active = createLoop(mc, desired, anchor);
                if (active != null) {
                    mc.getSoundManager().play(active);
                }
            }
        }
        if (active != null) {
            active.moveTo(anchor);
            active.approach(PEAK_VOLUME, FADE_IN_STEP);
        }
        if (fading != null) {
            fading.approach(0.0F, FADE_OUT_STEP);
            if (fading.volume() <= 0.005F) {
                stopQuietly(fading);
                fading = null;
            }
        }
    }

    private static LoopInstance createLoop(Minecraft mc, LoopKind kind, Vec3 pos) {
        SoundEvent event = switch (kind) {
            case FLAME -> SoundEvents.BLAZE_BURN;
            case FLAME_CHARGE -> SoundEvents.FIRE_AMBIENT;
            case SPIN -> SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT;
            case FLY -> SoundEvents.FIRE_AMBIENT;
            case SURF -> SoundEvents.PLAYER_SWIM;
            case SPOUT -> SoundEvents.PLAYER_SWIM;
            case CANNON -> SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT;
            case NONE -> null;
        };
        if (event == null) {
            return null;
        }
        float pitch = switch (kind) {
            case FLAME -> 0.72F;
            case FLAME_CHARGE -> 0.62F;
            case SPIN -> 1.35F;
            case FLY -> 1.70F;
            case SURF -> 0.60F;
            case SPOUT -> 0.52F;
            case CANNON -> 0.85F;
            case NONE -> 1.0F;
        };
        return new LoopInstance(event, SoundSource.PLAYERS, pos, pitch);
    }

    // ---------------------------------------------------------------- 播放工具

    private static void playAt(Minecraft mc, SoundEvent event, float volume, float pitch, Vec3 pos) {
        if (mc.level == null || event == null) {
            return;
        }
        mc.level.playLocalSound(pos.x, pos.y, pos.z, event, SoundSource.PLAYERS, volume, pitch, false);
    }

    private static void stopQuietly(SoundInstance instance) {
        if (instance == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().stop(instance);
        }
    }

    /**
     * 可调音量的循环声音实例。
     *
     * <p>{@code AbstractSoundInstance.volume} 是 protected 且原版没有 setter 语义上的
     * 平滑渐变，所以这里覆写 {@link #getVolume()} 读自己的字段 —— 混音器每帧都会读一次，
     * 于是音量变化是采样级平滑的，不需要重播声音。
     */
    private static final class LoopInstance extends AbstractSoundInstance {
        private volatile float currentVolume;
        private final float pitchValue;

        LoopInstance(SoundEvent event, SoundSource source, Vec3 pos, float pitch) {
            super(event, source, RandomSource.create());
            this.pitchValue = pitch;
            this.pitch = pitch;
            this.looping = true;
            this.relative = false;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            this.volume = 0.0F;
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
        }

        @Override
        public float getVolume() {
            return currentVolume;
        }

        @Override
        public float getPitch() {
            return pitchValue;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        float volume() {
            return currentVolume;
        }

        void moveTo(Vec3 pos) {
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
        }

        /** 朝目标音量走一步（渐入/渐出）。 */
        void approach(float target, float step) {
            if (currentVolume < target) {
                currentVolume = Math.min(target, currentVolume + step);
            } else if (currentVolume > target) {
                currentVolume = Math.max(target, currentVolume - step);
            }
        }
    }
}
