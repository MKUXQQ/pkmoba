package com.pkmoba.client;

import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.client.render.models.blockbench.bedrock.animation.BedrockAnimationRepository;
import com.cobblemon.mod.common.entity.PosableEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.charizard.CharizardManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.UUID;

/**
 * 喷火龙 Q（喷射火焰）/ R（大字炎爆）的专用施法动画。
 *
 * <p>为什么不再直接播 Cobblemon 的 {@code special}：那条动画是 3 秒整段（起手 0~1.25s /
 * 喷吐保持 1.25~2.0s / 收势 2.0~3s）。我们每次施法都整段播，于是出现：
 * <ul>
 *   <li>“喷了之后还播放一个”——收势段被看成第二个动作；</li>
 *   <li>“嘴没往前就喷”——起手段太长，出特效时模型还没前倾。</li>
 * </ul>
 *
 * <p>现在把同一套骨骼关键帧按阶段切出来（见 {@code tools/gen_charizard_anim.py}，
 * 产物 {@code assets/pkmoba/bedrock/pokemon/animation/pkmoba_charizard.animation.json}），
 * 由这里按引导状态逐阶段驱动，节奏和服务端严格的 tick 数对齐：
 * <pre>
 *   Q: 前摇 q_windup(0.55s) -> 引导 q_sustain(循环) -> 引导结束清空
 *   R: 蓄力 r_charge(1.0s)  -> 出手 r_blast(0.7s)   -> 播完清空
 * </pre>
 * 只做表现，不参与任何判定；任何异常都只停用本类，绝不影响游戏。
 */
public final class CharizardSkillAnimator {
    private static final String GROUP = "pkmoba_charizard";
    private static final String ANIM_Q_WINDUP = "q_windup";
    private static final String ANIM_Q_SUSTAIN = "q_sustain";
    private static final String ANIM_R_CHARGE = "r_charge";
    private static final String ANIM_R_BLAST = "r_blast";

    /** 与服务端 {@code CharizardCombat.Q_WINDUP_TICKS} 对齐：0.55s ≈ 11 tick。 */
    private static final int Q_WINDUP_TICKS = 11;
    /** 与服务端 {@code CharizardCombat.R_CHARGE_TICKS} 对齐：20 tick = 1 秒。 */
    private static final int R_CHARGE_TICKS = 20;
    /** r_blast 时长 0.7s ≈ 14 tick。 */
    private static final int R_BLAST_TICKS = 14;

    private enum Phase { NONE, Q_WINDUP, Q_SUSTAIN, R_CHARGE, R_BLAST }

    private static Phase phase = Phase.NONE;
    private static int phaseTicks;
    private static int lastChannel = -1;
    private static UUID lastEntity;
    /** 上一次驱动动画的实体，切换/离场时用来把它的主动画清干净。 */
    private static PokemonEntity lastPokemon;
    /** 一旦出错就永久停用，避免每 tick 刷异常。 */
    private static boolean disabled;

    private CharizardSkillAnimator() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(CharizardSkillAnimator::tick);
    }

    private static void tick(Minecraft mc) {
        if (disabled) {
            return;
        }
        try {
            tickInner(mc);
        } catch (Throwable t) {
            disabled = true;
            org.slf4j.LoggerFactory.getLogger("pkmoba")
                    .warn("[pkmoba] 喷火龙施法动画已停用：{}", t.toString());
        }
    }

    private static void tickInner(Minecraft mc) {
        int id = ClientState.getCharizardId();
        int channel = ClientState.getChannel();
        if (id < 0 || mc.level == null) {
            reset(null);
            lastChannel = channel;
            return;
        }
        Entity entity = mc.level.getEntity(id);
        if (!(entity instanceof PokemonEntity pokemon) || !isCharizard(pokemon)) {
            reset(null);
            lastChannel = channel;
            return;
        }
        if (!pokemon.getUUID().equals(lastEntity)) {
            reset(null);
            lastEntity = pokemon.getUUID();
            lastChannel = -1;
        }

        if (channel == 1) {
            if (lastChannel != 1) {
                phase = Phase.Q_WINDUP;
                phaseTicks = 0;
                play(pokemon, ANIM_Q_WINDUP);
            } else {
                phaseTicks++;
                if (phase == Phase.Q_WINDUP && phaseTicks >= Q_WINDUP_TICKS) {
                    // 前摇到位（模型已经前倾张口）才切引导姿态
                    phase = Phase.Q_SUSTAIN;
                    play(pokemon, ANIM_Q_SUSTAIN);
                }
            }
        } else if (channel == 4) {
            if (lastChannel != 4) {
                phase = Phase.R_CHARGE;
                phaseTicks = 0;
                play(pokemon, ANIM_R_CHARGE);
            } else if (phase == Phase.R_CHARGE) {
                phaseTicks++;
                if (phaseTicks >= R_CHARGE_TICKS) {
                    // 蓄满即出手：服务端同一 tick 发火球，这里同步切前扑动作
                    phase = Phase.R_BLAST;
                    phaseTicks = 0;
                    play(pokemon, ANIM_R_BLAST);
                }
            }
        } else {
            // 引导已经结束：Q 立刻收；R 让 r_blast 播完再收，避免"喷完还卡在出手动作"
            if (phase == Phase.Q_WINDUP || phase == Phase.Q_SUSTAIN) {
                clear(pokemon);
            } else if (phase == Phase.R_CHARGE) {
                // channel 4 结束 = 服务端这一 tick 发了火球（R_CHARGE_TICKS 到点就出手）。
                // 注意：服务端在"蓄满那一 tick"就把 channel 关掉，所以客户端几乎不可能在
                // channel==4 期间看到 phaseTicks 抵达 R_CHARGE_TICKS —— 出手动作必须在
                // "channel 掉下去的那一刻"接上，否则 r_blast 永远不会播，又变成"喷完没动作"。
                phase = Phase.R_BLAST;
                phaseTicks = 0;
                play(pokemon, ANIM_R_BLAST);
            } else if (phase == Phase.R_BLAST && ++phaseTicks >= R_BLAST_TICKS) {
                clear(pokemon);
            }
        }
        lastChannel = channel;
        lastPokemon = pokemon;
    }

    private static boolean isCharizard(PokemonEntity entity) {
        try {
            return CharizardManager.CHARIZARD_ID.equals(
                    entity.getPokemon().getSpecies().getResourceIdentifier());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 播一条自制动画组里的动画（组名 = 文件名，动画名 = 去掉 animation.&lt;组&gt;. 前缀）。
     *
     * <p>0.38.3：加了一层"先查后播"的保险 —— Cobblemon 的
     * {@code BedrockAnimationRepository} 在资源包加载时把所有
     * {@code bedrock/<type>/animations/*.animation.json} 都收进全局表（组名 = 文件名去后缀），
     * 但资源加载失败/被别的资源包覆盖时查不到。查不到就回退到 Cobblemon 自带的
     * {@code charizard/special}（老效果），保证"至少有个动作"，不会退化成站桩施法。
     */
    private static void play(PokemonEntity entity, String name) {
        try {
            String fullName = "animation." + GROUP + "." + name;
            if (!hasAnimation(fullName)) {
                playAnimString(entity, "q.bedrock_primary('charizard', 'special', q.curve('one'))");
                return;
            }
            playAnimString(entity, "q.bedrock_primary('" + GROUP + "', '" + name + "', q.curve('one'))");
        } catch (Throwable ignored) {
            // 动画组缺失时退化为无动画，技能逻辑不受影响
        }
    }

    private static void playAnimString(PokemonEntity entity, String expression) {
        ((PosableEntity) entity).playAnimation(expression, List.of());
    }

    /** 自制动画组是否真的被 Cobblemon 载入了（组名 + 完整动画键）。 */
    private static boolean hasAnimation(String fullName) {
        try {
            return BedrockAnimationRepository.INSTANCE.getAnimationOrNull(GROUP, fullName) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把主动画清回姿势动画（待机/走路），否则喷完会一直停在最后一帧。 */
    private static void clear(PokemonEntity entity) {
        phase = Phase.NONE;
        phaseTicks = 0;
        try {
            if (entity.getDelegate() instanceof PosableState state) {
                state.setPrimaryAnimation(null);
            }
        } catch (Throwable ignored) {
            // 同上，纯表现
        }
    }

    private static void reset(PokemonEntity entity) {
        // 切换/离场时把"上一次驱动的那只"也清掉，否则它的主动画会永久卡在最后一帧
        if (phase != Phase.NONE) {
            if (entity != null) {
                clear(entity);
            } else if (lastPokemon != null) {
                clear(lastPokemon);
            }
        }
        phase = Phase.NONE;
        phaseTicks = 0;
        lastEntity = null;
        lastPokemon = null;
    }
}
