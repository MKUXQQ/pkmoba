package com.pkmoba.client;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.entity.pokemon.PokemonBehaviourFlag;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 客户端走路动画兜底（用户选 A1-A：客户端按实际速度自己切 walk/idle）。
 *
 * <p>为什么需要这一层：Cobblemon 判断是否在走路用的是
 * {@code isPokemonWalking = walkDist - walkDistO > 0.005F}（PokemonEntity.kt:654），
 * 而 walkDist 由实体自身移动推进。我们是服务端驱动 + 客户端插值，客户端这个增量经常为 0，
 * 于是它一直播待机姿势 —— 表现就是"滑行"。
 *
 * <p>这里每 tick 检查实体的实际位移：动了就把 isPokemonWalking 置真。
 * 时机很关键——必须在本 tick 的实体 tick **之后**（END_CLIENT_TICK）设置，
 * 因为 Cobblemon 每 tick 会先把该值重算为 false，渲染发生在 tick 之后。
 */
public final class WalkAnimator {
    /** 位移平方阈值：约 0.02 格，足以区分"站着"与"在走"，又能滤掉同步抖动。 */
    private static final double MOVE_EPSILON_SQR = 4.0E-4;

    private static Vec3 lastPos;
    private static java.util.UUID lastEntityId;
    private static boolean wasSkillFlying;
    private static boolean wasBlastoiseShellCurl;
    private static PokemonEntity shellEntity;
    private static PoseType lastSkillPose;
    /** 一旦出错就永久停用，避免每 tick 刷异常。 */
    private static boolean disabled = false;

    private WalkAnimator() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(WalkAnimator::tick);
    }

    private static void tick(Minecraft mc) {
        if (disabled) {
            return;
        }
        try {
            pkmoba$tickInner(mc);
        } catch (Throwable t) {
            // 走路动画只是表现层兜底，绝不能因为它让游戏崩掉
            disabled = true;
            org.slf4j.LoggerFactory.getLogger("pkmoba")
                    .warn("[pkmoba] 走路动画兜底已停用：{}", t.toString());
        }
    }

    private static void pkmoba$tickInner(Minecraft mc) {
        int id = ClientState.getCharizardId();
        if (id < 0 || mc.level == null) {
            lastPos = null;
            lastEntityId = null;
            wasSkillFlying = false;
            clearShellAnimation();
            return;
        }
        Entity entity = mc.level.getEntity(id);
        if (!(entity instanceof PokemonEntity pokemonEntity)) {
            lastPos = null;
            lastEntityId = null;
            wasSkillFlying = false;
            clearShellAnimation();
            return;
        }
        boolean shellCurl = ShellAnimator.appliesTo(pokemonEntity) && ClientState.getChannel() == 3;
        if (shellEntity != pokemonEntity) {
            clearShellAnimation();
            shellEntity = pokemonEntity;
        }
        ShellAnimator.tick(pokemonEntity, shellCurl);
        wasBlastoiseShellCurl = shellCurl;

        Vec3 pos = pokemonEntity.position();
        if (!pokemonEntity.getUUID().equals(lastEntityId)) {
            lastPos = null;
            wasSkillFlying = false;
            lastEntityId = pokemonEntity.getUUID();
        }
        boolean skillFlying = ClientState.getChannel() == 3
                && !com.pkmoba.charizard.CharizardManager.isBlastoise(pokemonEntity);
        Vec3 displacement = lastPos == null ? Vec3.ZERO : pos.subtract(lastPos);
        boolean moved = displacement.horizontalDistanceSqr() > MOVE_EPSILON_SQR;
        // Entity.tick recalculates flying/walking from distances that client interpolation
        // does not advance reliably. Apply the skill pose AFTER that tick, before rendering.
        if (skillFlying || wasSkillFlying) {
            boolean translating = skillFlying && moved;
            PoseType pose = skillFlying ? (translating ? PoseType.FLY : PoseType.HOVER)
                    : (moved && pokemonEntity.onGround() ? PoseType.WALK : PoseType.STAND);
            pokemonEntity.setBehaviourFlag(PokemonBehaviourFlag.FLYING, skillFlying);
            pokemonEntity.setPokemonFlying(translating);
            pokemonEntity.setPokemonWalking(!skillFlying && moved);
            pokemonEntity.getEntityData().set(PokemonEntity.getMOVING(), skillFlying ? translating : moved);
            pokemonEntity.getEntityData().set(PokemonEntity.getPOSE_TYPE(), pose);
            if (pokemonEntity.getDelegate() instanceof com.cobblemon.mod.common.client.render.models.blockbench.PosableState state) {
                // An old melee/special primary animation otherwise masks the wing cycle.
                if (skillFlying && !wasSkillFlying) state.setPrimaryAnimation(null);
                if (!wasSkillFlying || pose != lastSkillPose) state.setPoseToFirstSuitable(pose);
            }
            lastSkillPose = pose;
        } else {
            boolean flying = pokemonEntity.getBehaviourFlag(PokemonBehaviourFlag.FLYING)
                    || pokemonEntity.getCurrentPoseType() == PoseType.FLY
                    || pokemonEntity.getCurrentPoseType() == PoseType.HOVER;
            pokemonEntity.setPokemonWalking(!flying && moved);
        }
        wasSkillFlying = skillFlying;
        lastPos = pos;
    }

    private static void clearShellAnimation() {
        if (shellEntity != null && wasBlastoiseShellCurl) {
            ShellAnimator.clear(shellEntity);
        }
        shellEntity = null;
        wasBlastoiseShellCurl = false;
    }
}
