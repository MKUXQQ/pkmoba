package com.pkmoba.client;

import com.pkmoba.combat.BlastoiseCombat;
import com.pkmoba.combat.Skill;
import com.pkmoba.network.EffectPayload;
import com.pkmoba.particle.PkmobaParticles;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * 命中反馈：命中点特效 + 局部顿帧 + 震屏 + 屏幕闪 + R 大招推镜/色偏 + 斩杀提示。
 *
 * <h2>数据来源</h2>
 * 只认服务端 {@code pkmoba:hit/...} 包（{@link EffectPayload}），由
 * {@code combat/CombatDamage.apply} 在 {@code hurt()} <b>真正被接受</b>之后发出，
 * 所以"没打中"、"被无敌帧挡掉"、"被事件取消"都不会有任何反馈。
 *
 * <h2>顿帧</h2>
 * <b>绝不冻结世界、绝不改 tickrate、绝不碰服务端</b>。做法是客户端自己的
 * "impact hold"：在 {@link EffectPayload.Power#hitstopTicks()} 个客户端 tick 内
 * <ul>
 *   <li>把震屏钉在峰值并暂停衰减（视觉上世界像被"顶住"了一瞬）；</li>
 *   <li>屏闪保持不衰减；</li>
 *   <li>本类自己的 vfx 时钟（{@link #effectClock}）暂停推进，命中的特效不会继续演化。</li>
 * </ul>
 * 服务端 tick、实体物理、伤害结算全程不受影响。
 *
 * <h2>震屏</h2>
 * 相机世界空间位移 = 左向量 × A·sin(φ·6.0) + 上向量 × A·sin(φ·7.4+1.3)·0.7 + 视线 × A·sin(φ·5.1)·0.45，
 * 其中 {@code A = 峰值 × ((剩余tick/总tick)²) × 微扰}。衰减收敛到 0（永不会突然复位），
 * 新命中不是简单相加而是 {@code A' = min(上限, A×0.55 + 新峰值)}，所以连击不会越震越晕。
 * 与 {@code GodView} 的固定机位不冲突：两者没有共享状态，GodView 先摆位，本节再叠加偏移。
 *
 * <h2>R 大招</h2>
 * 镜头沿视线推近（{@link #cameraDolly}，单位格，只在喷火龙 channel 4 蓄力/引导期间为正值），
 * 结束平滑回落；色偏是 Hud 叠加的暖橙淡色（透明度 ≤ 16%），不影响可读性。
 */
public final class HitFeedback {
    private HitFeedback() { }

    // ---------------------------------------------------------------- 常量
    /** 命中特效/反馈的最大距离（格），更远的包仍然接受，只是不生成粒子。 */
    private static final double FEEDBACK_RANGE_SQR = 96.0 * 96.0;
    /** 震屏峰值上限（格）：再大就影响瞄准了。 */
    private static final double SHAKE_CAP = 1.8;
    /** 顿帧上限（tick）：2~3 个逻辑 tick 的量级，最多 6 也不至于卡顿。 */
    private static final int HITSTOP_CAP = 8;
    /** R 大招推镜量（格）。 */
    private static final float ULT_DOLLY = 1.25F;
    /** R 大招色偏最大不透明度（0~1）。 */
    private static final float ULT_TINT_MAX = 0.16F;
    private static final int KILL_TICKS = 26;
    /** 喷火龙 R（大字爆）的引导 channel。 */
    private static final int CHARIZARD_ULT_CHANNEL = 4;

    // ---------------------------------------------------------------- 震屏
    private static double shakeAmplitude;
    private static int shakeTicks;
    private static int shakeTotal;
    private static double shakePhase;
    private static Vec3 shakeBias = Vec3.ZERO;

    // ---------------------------------------------------------------- 顿帧 / 闪白
    private static int hitstopTicks;
    private static int hitstopTotal;
    private static float flashStrength;
    private static int flashTicks;
    private static int flashTotal;
    private static int flashColor = 0xFFFFFF;

    // ---------------------------------------------------------------- R 大招
    private static float dolly;
    private static float dollyPrev;
    private static float dollyTarget;
    private static float ultTint;
    private static boolean ultActive;

    // ---------------------------------------------------------------- 斩杀提示
    private static int killTicks;
    private static int killColor = 0xFFE0B0;

    // ---------------------------------------------------------------- vfx 时钟（顿帧时暂停）
    private static long effectClock;
    private static boolean initialized;
    /** 上一次做镜头反馈的客户端 tick（同 tick 的多段命中只反馈一次）。 */
    private static long lastCameraTick = Long.MIN_VALUE;
    private static int lastPikachuStrikeEntity = Integer.MIN_VALUE;
    private static long lastPikachuStrikeTick = Long.MIN_VALUE;

    /** 注册 tick 与 HUD 渲染。由 {@code PkmobaClient.onInitializeClient()} 调用一次。 */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(HitFeedback::tick);
        HudRenderCallback.EVENT.register(HitFeedback::renderOverlay);
    }

    /** 断开连接/换世界时清空全部状态，避免把上一局的震屏带进来。 */
    public static void reset() {
        shakeAmplitude = 0;
        shakeTicks = 0;
        shakeTotal = 0;
        shakePhase = 0;
        shakeBias = Vec3.ZERO;
        hitstopTicks = 0;
        hitstopTotal = 0;
        flashStrength = 0;
        flashTicks = 0;
        flashTotal = 0;
        dolly = 0;
        dollyPrev = 0;
        dollyTarget = 0;
        ultTint = 0;
        ultActive = false;
        killTicks = 0;
        effectClock = 0;
        lastCameraTick = Long.MIN_VALUE;
        lastPikachuStrikeEntity = Integer.MIN_VALUE;
        lastPikachuStrikeTick = Long.MIN_VALUE;
        CombatAudio.reset();
    }

    /** One camera shake per locally controlled Pikachu R cast, independent of target hits. */
    public static void onPikachuThunderStrike(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || entityId != ClientState.getCharizardId()) return;
        long now = mc.level.getGameTime();
        if (lastPikachuStrikeEntity == entityId && now - lastPikachuStrikeTick < 100) return;
        lastPikachuStrikeEntity = entityId;
        lastPikachuStrikeTick = now;
        addShake(EffectPayload.Power.ULTIMATE, Vec3.ZERO);
        beginHitstop(EffectPayload.Power.HEAVY);
    }

    /**
     * 处理一条 S2C 特效包：命中事件返回 {@code true}（已消费，不要再当 Cobblemon 粒子 id 查表），
     * 其他返回 {@code false} 交给原来的特效逻辑。
     */
    public static boolean handle(EffectPayload payload) {
        if (payload == null || !payload.isHit()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return true;
        }
        EffectPayload.Element element = payload.hitElement();
        EffectPayload.Power power = payload.hitPower();
        Vec3 point = payload.hitPoint();
        Vec3 dir = payload.hitDirection();
        if (element == null || !finite(point)) {
            return true;
        }
        boolean lethal = payload.hitLethal();
        boolean near = mc.player.position().distanceToSqr(point) <= FEEDBACK_RANGE_SQR;
        if (near) {
            spawnHitParticles(mc, point, dir, element, power, lethal);
        }
        // 0.39：区分"我打别人"和"我被打"——旧版只要附近有命中就震屏 + 闪屏，
        // 导致玩家自己普攻时屏幕一直在闪（用户反馈"屏幕闪红"）。
        // 现在：命中点粒子两种情况都给；镜头反馈只有"打出去"的轻震 + 顿帧，
        // 全屏元素色闪屏只保留"自己挨打"这一种，并且把强度压到很低。
        boolean inCameraRange = near && mc.player.position().distanceToSqr(point) <= 40.0 * 40.0;
        if (inCameraRange) {
            // 同一次爆发的多段命中（R 大字爆会一 tick 命中多个目标）只做一次镜头反馈，
            // 否则震屏/顿帧会被叠加成"卡死几帧"，反而破坏手感。
            long now = mc.level.getGameTime();
            if (now != lastCameraTick) {
                lastCameraTick = now;
                // m04707：用户明确"只能大招才有屏幕特效，其他都没有"。
                // 所以震屏 + 顿帧只在 ULTIMATE 命中时生效；普攻/普通技能/击杀一律不动屏幕。
                // 水箭龟 Q（加农水炮）与 E（冲浪）走普通伤害通道，天然不会进这里。
                // Electric thunder has its own deduped landing cue, even when it misses.
                boolean pikachuThunder = mc.level.getEntity(ClientState.getCharizardId())
                        instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity controlled
                        && Skill.isPikachu(controlled);
                if (power == EffectPayload.Power.ULTIMATE && !pikachuThunder) {
                    addShake(EffectPayload.Power.ULTIMATE, dir);
                    beginHitstop(EffectPayload.Power.HEAVY);
                }
            }
        }
        CombatAudio.onHit(element, power, lethal);
        // 0.39.2：只要这一下打死人（不论普攻/技能/大招），都弹"斩 杀"。
        if (lethal && near) {
            showKill(element);
        }
        return true;
    }

    /**
     * 判断这次命中的受害者是不是"我方视角的自己"（玩家本体或当前受控宝可梦）。
     *
     * <p>用于把全屏闪屏/震屏限制在"自己挨打"，避免自己普攻也在闪。
     * 拿不到目标 id（旧服务端/旧包）时返回 false——宁可少闪，也不要乱闪。
     */
    private static boolean isOwnChampion(int targetId) {
        if (targetId <= 0) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == targetId) {
            return true;
        }
        // ClientState 里登记的就是"当前受控宝可梦"的实体 id（历史命名 charizardId，两物种通用）。
        int ownId = ClientState.getCharizardId();
        return ownId > 0 && ownId == targetId;
    }

    // ---------------------------------------------------------------- 命中点特效

    private static void spawnHitParticles(Minecraft mc, Vec3 point, Vec3 dir,
                                          EffectPayload.Element element,
                                          EffectPayload.Power power, boolean lethal) {
        if (mc.level == null) {
            return;
        }
        int count = switch (power) {
            case LIGHT -> 10;
            case HEAVY -> 18;
            case ULTIMATE -> 30;
        };
        var rnd = mc.level.random;
        double spread = 0.22 + power.shakeAmplitude() * 0.12;
        switch (element) {
            case FIRE -> {
                for (int i = 0; i < count; i++) {
                    add(mc, i % 3 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, point, dir, spread, 0.10, rnd);
                }
                for (int i = 0; i < count / 3 + 2; i++) {
                    add(mc, ParticleTypes.SMOKE, point, dir, spread * 1.6, 0.05, rnd);
                }
                mc.level.addParticle(PkmobaParticles.FLAME_PUFF_SMALL,
                        point.x, point.y, point.z, dir.x * 0.12, 0.05, dir.z * 0.12);
                if (power == EffectPayload.Power.ULTIMATE) {
                    mc.level.addParticle(PkmobaParticles.FLAME_PUFF_BIG,
                            point.x, point.y, point.z, 0.0, 0.02, 0.0);
                }
            }
            case WATER -> {
                for (int i = 0; i < count; i++) {
                    add(mc, i % 4 == 0 ? ParticleTypes.BUBBLE_POP : ParticleTypes.SPLASH,
                            point, dir, spread, 0.16, rnd);
                }
                for (int i = 0; i < count / 3 + 2; i++) {
                    add(mc, ParticleTypes.BUBBLE, point, dir, spread * 1.2, 0.10, rnd);
                }
            }
            case PHYSICAL -> {
                for (int i = 0; i < count; i++) {
                    add(mc, i % 2 == 0 ? ParticleTypes.CRIT : ParticleTypes.ENCHANTED_HIT,
                            point, dir, spread, 0.12, rnd);
                }
                if (power != EffectPayload.Power.LIGHT) {
                    mc.level.addParticle(ParticleTypes.SWEEP_ATTACK,
                            point.x, point.y, point.z, dir.x * 0.2, 0.0, dir.z * 0.2);
                }
            }
        }
        if (lethal) {
            // 击杀瞬间多一炸白烟，和斩杀提示同时出现（纯视觉）。
            for (int i = 0; i < 6; i++) {
                add(mc, ParticleTypes.POOF, point, dir, 0.5, 0.08, rnd);
            }
        }
    }

    private static void add(Minecraft mc, net.minecraft.core.particles.SimpleParticleType type, Vec3 point,
                            Vec3 dir, double spread, double back, RandomSource rnd) {
        if (mc.level == null) {
            return;
        }
        // 主方向：命中方向的反向"溅回"（打中的一面往攻击者方向崩），再叠随机扩散
        double vx = dir.x * back + (rnd.nextDouble() - 0.5) * spread;
        double vy = 0.06 + rnd.nextDouble() * spread;
        double vz = dir.z * back + (rnd.nextDouble() - 0.5) * spread;
        mc.level.addParticle(type, point.x, point.y, point.z, vx, vy, vz);
    }

    // ---------------------------------------------------------------- 震屏 / 顿帧 / 闪白

    private static void addShake(EffectPayload.Power power, Vec3 dir) {
        double amp = power.shakeAmplitude();
        // 连击不累积成"晕船"：新峰值只保留旧值的 55%
        shakeAmplitude = Math.min(SHAKE_CAP, shakeAmplitude * 0.55 + amp);
        shakeTicks = Math.max(shakeTicks, shakeTicksFor(power));
        shakeTotal = Math.max(1, shakeTicks);
        shakePhase += 1.7 + (dir.lengthSqr() > 1.0E-6 ? 0.8 : 0.0);
        shakeBias = dir;
    }

    private static int shakeTicksFor(EffectPayload.Power power) {
        return switch (power) {
            case LIGHT -> 5;
            case HEAVY -> 7;
            case ULTIMATE -> 11;
        };
    }

    private static void beginHitstop(EffectPayload.Power power) {
        int ticks = Math.min(HITSTOP_CAP, power.hitstopTicks());
        hitstopTicks = Math.max(hitstopTicks, ticks);
        hitstopTotal = Math.max(1, hitstopTicks);
    }

    private static void beginFlash(EffectPayload.Element element, EffectPayload.Power power) {
        flashStrength = 1.0F;
        flashTicks = Math.max(flashTicks, power.flashTicks());
        flashTotal = Math.max(1, flashTicks);
        flashColor = switch (element) {
            case FIRE -> 0xFF7A2E;
            case WATER -> 0x6FC8FF;
            case PHYSICAL -> 0xFFFFFF;
        };
    }

    private static void showKill(EffectPayload.Element element) {
        killTicks = KILL_TICKS;
        killColor = switch (element) {
            case FIRE -> 0xFFC46B;
            case WATER -> 0x9FE4FF;
            case PHYSICAL -> 0xFFFFFF;
        };
    }

    /** 每个客户端 tick 推进一次：衰减、R 大招推镜、斩杀计时。 */
    private static void tick(Minecraft mc) {
        if (mc.level == null) {
            reset();
            return;
        }
        dollyPrev = dolly;
        // 顿帧期间：震屏/闪屏/本类 vfx 时钟全部冻结（视觉"顶住"，世界与服务端照常运行）
        boolean frozen = hitstopTicks > 0;
        if (frozen) {
            hitstopTicks--;
        } else {
            effectClock++;
            if (shakeTicks > 0) {
                shakeTicks--;
                if (shakeTicks == 0) {
                    shakeAmplitude = 0;
                }
            }
            if (flashTicks > 0) {
                flashTicks--;
                if (flashTicks == 0) {
                    flashStrength = 0;
                }
            }
        }
        if (killTicks > 0) {
            killTicks--;
        }
        updateUlt(mc, frozen);
        CombatAudio.tick(mc);
    }

    /** All three R skills share a charge push-in; only the outer vignette is tinted. */
    private static void updateUlt(Minecraft mc, boolean frozen) {
        boolean ulting = ClientState.isSkillStateReady() && mc.level != null
                && isUltimateChannel(ClientState.getChannel());
        ultActive = ulting;
        if (ulting) {
            float charge = Math.max(0.0F, Math.min(1.0F, ClientState.getCharge()));
            dollyTarget = ULT_DOLLY * (0.55F + 0.45F * charge);
            ultTint = Math.min(ULT_TINT_MAX, ultTint + ULT_TINT_MAX / 9.0F);
        } else {
            dollyTarget = 0.0F;
            ultTint = Math.max(0.0F, ultTint - ULT_TINT_MAX / 7.0F);
        }
        if (!frozen) {
            // 平滑跟随目标：推近慢（0.16），复员稍快（0.22），都不存在"突然复位"
            float rate = dollyTarget > dolly ? 0.16F : 0.22F;
            dolly += (dollyTarget - dolly) * rate;
            if (Math.abs(dollyTarget - dolly) < 0.002F) {
                dolly = dollyTarget;
            }
        }
    }

    // ---------------------------------------------------------------- 相机接口（给 CameraMixin 用）

    /** 相机世界空间抖动偏移（格）。{@code partialTick} 只用于把衰减插值到渲染帧。 */
    public static Vec3 shakeOffset(Camera camera, float partialTick) {
        double amp = shakeAmplitudeNow(partialTick);
        if (amp <= 1.0E-4 || camera == null) {
            return Vec3.ZERO;
        }
        double phase = shakePhase + effectClock * 0.9 + partialTick * 0.9;
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        Vector3f look = camera.getLookVector();
        double l = Math.sin(phase * 6.0) * amp;
        double u = Math.sin(phase * 7.4 + 1.3) * amp * 0.70;
        double f = Math.sin(phase * 5.1) * amp * 0.45;
        // 命中方向会给一点"被打的一侧"偏置，但不超过峰值的 25%
        double bx = shakeBias.x * amp * 0.25;
        double bz = shakeBias.z * amp * 0.25;
        return new Vec3(left.x() * l + up.x() * u + look.x() * f + bx,
                left.y() * l + up.y() * u + look.y() * f,
                left.z() * l + up.z() * u + look.z() * f + bz);
    }

    /** 相机偏航抖动（度），与位移用同一包络，幅度很小（≤ 1.6°）。 */
    public static float shakeYaw(float partialTick) {
        double amp = shakeAmplitudeNow(partialTick);
        if (amp <= 1.0E-4) {
            return 0.0F;
        }
        double phase = shakePhase + effectClock * 0.9 + partialTick * 0.9;
        return (float) (Math.sin(phase * 4.3 + 0.7) * amp * 1.15);
    }

    /** 相机俯仰抖动（度）。 */
    public static float shakePitch(float partialTick) {
        double amp = shakeAmplitudeNow(partialTick);
        if (amp <= 1.0E-4) {
            return 0.0F;
        }
        double phase = shakePhase + effectClock * 0.9 + partialTick * 0.9;
        return (float) (Math.sin(phase * 3.7 + 2.1) * amp * 0.85);
    }

    /** R 大招推镜量（格，沿相机视线方向前移）；用渲染帧 partialTick 插值避免抖动。 */
    public static float cameraDolly(float partialTick) {
        return dollyPrev + (dolly - dollyPrev) * clamp01(partialTick);
    }

    /** 当前是否处于局部顿帧窗口（供其它表现层查询，例如暂停自己的时钟）。 */
    public static boolean hitstopActive() {
        return hitstopTicks > 0;
    }

    /** 震屏包络：剩余 proportion 的平方衰减，结束时必然为 0，不会"突然复位"。 */
    private static double shakeAmplitudeNow(float partialTick) {
        if (shakeTicks <= 0 || shakeAmplitude <= 0) {
            return 0.0;
        }
        double remain = (shakeTicks - partialTick) / (double) Math.max(1, shakeTotal);
        if (remain <= 0) {
            return 0.0;
        }
        return shakeAmplitude * remain * remain;
    }

    // ---------------------------------------------------------------- HUD 叠加

    private static void renderOverlay(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        // 0.39：全屏元素色覆盖已按用户要求删除（"不要显示屏幕覆盖的蓝色红色"）。
        // 命中反馈只保留命中点粒子 + （大招特供的）镜头抖动。
        if (false && flashTicks > 0 && flashStrength > 0.01F) {
            float k = flashTicks / (float) Math.max(1, flashTotal);
            int alpha = (int) Math.min(70.0F, 34.0F * flashStrength * k + 10.0F);
            graphics.fill(0, 0, w, h, (alpha << 24) | (flashColor & 0xFFFFFF));
        }
        // Keep the playfield readable: ultimate color stays at the edges.

        // 蓄力感（0.39.2 用户要求"蓄力时屏幕要有蓄力的感觉"）：
        // 用贴边的暗角带 + 随蓄力加深的内缩，不用整屏色块（用户明确讨厌全屏覆盖色）。
        drawChargeVignette(graphics, mc, w, h);
        // 斩杀提示（服务端真实致死标记）
        if (killTicks > 0 && mc.font != null) {
            float k = killTicks > 6 ? 1.0F : killTicks / 6.0F;
            int alpha = (int) Math.min(255.0F, 235.0F * k);
            String text = "斩 杀";
            int tw = mc.font.width(text);
            int x = (w - tw) / 2;
            int y = h / 2 - 54;
            graphics.drawString(mc.font, text, x + 1, y + 1, (int) (alpha * 0.55F) << 24, false);
            graphics.drawString(mc.font, text, x, y, (alpha << 24) | (killColor & 0xFFFFFF), true);
            int lineWidth = tw + 44;
            int lx = (w - lineWidth) / 2;
            graphics.fill(lx, y + 13, lx + lineWidth, y + 14, (alpha << 24) | (killColor & 0xFFFFFF));
        }
    }

    /**
     * 蓄力暗角（0.39.2）。
     *
     * <p>用户在 m03117 要求"蓄力时屏幕要有蓄力的感觉"，同时又在同一段里明确说
     * "不要显示屏幕覆盖的蓝色红色"。所以这里<b>不画整屏色块</b>，而是只在四边画
     * 逐渐加深的暗角带：蓄力越满，边缘越暗、内缩越窄，视觉上像镜头在往中间收气。
     *
     * <p>数据全来自服务端 {@code SkillStatePayload.charge}（0..1），不自己推算，
     * 两边不同步时最多是暗角少亮一两帧，不会出现"屏幕一直黑着"。
     */
    private static void drawChargeVignette(GuiGraphics graphics, Minecraft mc, int w, int h) {
        if (!ClientState.isSkillStateReady() || mc.level == null) {
            return;
        }
        int channel = ClientState.getChannel();
        // 用户要求"所有屏幕特效除了大招其余的全部删除"：蓄力暗角也只有大招能出。
        // 水箭龟的 Q/E 蓄力（channel 4/5）以前也会压暗四边，现在一律不出。
        if (!isUltimateChannel(channel)) {
            return;
        }
        float charge = Mth.clamp(ClientState.getCharge(), 0.0F, 1.0F);
        if (charge <= 0.01F) {
            return;
        }
        // 蓄力颜色：喷火龙偏暖橙、水箭龟偏青蓝；两种都压得很暗，只做"压边"不做"盖屏"。
        String species = SkillKeys.controllingSpeciesId();
        int rgb = Skill.isVenusaur(species) ? 0x30113E : Skill.isBlastoise(species) ? 0x0B3A4E : 0x3A1204;
        int maxAlpha = (int) (150.0F * charge);
        // 呼吸：越接近满蓄力脉动越快，给"能量聚满"的节奏感。
        double phase = (System.nanoTime() / 1.0E9) * (0.35F + 0.45F * charge) * 6.2831853
                + charge * 12.0;
        float pulse = 0.82F + 0.18F * (float) Math.sin(phase);
        int peak = (int) (maxAlpha * pulse);
        if (peak <= 1) {
            return;
        }
        // 四条边带：越往里 alpha 越低，用若干层薄带模拟渐变（GuiGraphics 只有纯色矩形）。
        int layers = 5;
        int depth = (int) (h * (0.16F + 0.10F * charge));
        for (int i = 0; i < layers; i++) {
            int alpha = peak * (layers - i) / (layers * 2);
            if (alpha <= 0) continue;
            int band = Math.max(1, depth / layers);
            int y0 = i * band;
            int color = (alpha << 24) | rgb;
            graphics.fill(0, y0, w, y0 + band, color);                       // 上
            graphics.fill(0, h - y0 - band, w, h - y0, color);               // 下
            graphics.fill(0, y0, band, h - y0, color);                       // 左
            graphics.fill(w - band, y0, w, h - y0, color);                   // 右
        }
    }

    // ---------------------------------------------------------------- 小工具

    /**
     * 当前引导中的 channel 是不是"大招"（R）的 channel。
     *
     * <p>喷火龙 R（大字爆）走 channel 4；水箭龟 R（水炮风暴）走 {@link BlastoiseCombat#CHANNEL_SPOUT}（6）。
     * 两物种的 channel 编号不通用，所以必须按物种判，不能只比一个数字。
     */
    private static boolean isUltimateChannel(int channel) {
        if (channel <= 0) {
            return false;
        }
        String species = SkillKeys.controllingSpeciesId();
        if (Skill.isPikachu(species)) return channel == 14;
        if (Skill.isVenusaur(species)) return channel == 10;
        if (Skill.isBlastoise(species)) return channel == BlastoiseCombat.CHANNEL_SPOUT;
        return ("cobblemon:charizard".equals(species) || "charizard".equals(species))
                && channel == CHARIZARD_ULT_CHANNEL;
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }
}
