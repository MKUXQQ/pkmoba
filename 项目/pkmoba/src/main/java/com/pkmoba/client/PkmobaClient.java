package com.pkmoba.client;

import com.cobblemon.mod.common.client.particle.BedrockParticleOptionsRepository;
import com.cobblemon.mod.common.client.particle.ParticleStorm;
import com.pkmoba.network.CharizardSyncPayload;
import com.pkmoba.network.EffectPayload;
import com.pkmoba.network.GodStatePayload;
import com.pkmoba.network.MoveAckPayload;
import com.pkmoba.network.PlacementPayload;
import com.pkmoba.network.SkillStatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口：按键、相机、HUD、技能状态、招式特效。
 *
 * <p>特效为什么在客户端生成：{@code ParticleStorm} 与粒子仓库都是客户端类，
 * 服务端只发 effectId，客户端查表后生成 —— 这样拿到的就是 Cobblemon 原版战斗特效。
 */
public class PkmobaClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("pkmoba-client");

    @Override
    public void onInitializeClient() {
        InputHandler.init();
        SkillKeys.init();
        WalkAnimator.init();
        HudOverlay.init();
        SkillHud.init();
        SelectionOutlineRenderer.init();
        TornadoEffects.init();
        WaterEffects.init();
        MatchResultOverlay.init();

        // 上帝视角开关
        ClientPlayNetworking.registerGlobalReceiver(GodStatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (payload.active()) {
                        Vec3 entryAnchor = Double.isFinite(payload.anchorX()) && Double.isFinite(payload.anchorY())
                                && Double.isFinite(payload.anchorZ())
                                ? new Vec3(payload.anchorX(), payload.anchorY(), payload.anchorZ()) : null;
                        GodView.activate(entryAnchor);
                    } else {
                        GodView.deactivate();
                    }
                }));

        // 当前喷火龙实体
        ClientPlayNetworking.registerGlobalReceiver(CharizardSyncPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientState.setCharizardId(payload.entityId())));

        // 选点召唤状态
        ClientPlayNetworking.registerGlobalReceiver(PlacementPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientState.setPlacementArmed(payload.armed())));

        // 移动指令回执
        ClientPlayNetworking.registerGlobalReceiver(MoveAckPayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        ClientState.setServerAck(payload.x(), payload.y(), payload.z(), payload.accepted())));

        // 技能状态
        ClientPlayNetworking.registerGlobalReceiver(SkillStatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientState.setSkillState(
                        payload.hp(), payload.maxHp(), payload.points(),
                        payload.cdQ(), payload.cdW(), payload.cdE(), payload.cdR(),
                        payload.channel(), payload.charge(),
                        payload.arenaDist(), payload.arenaCx(), payload.arenaCy(), payload.arenaCz(),
                        payload.arenaRadius())));

        // 招式粒子特效
        ClientPlayNetworking.registerGlobalReceiver(EffectPayload.TYPE, (payload, context) ->
                context.client().execute(() -> pkmoba$spawnEffect(payload.effectId(),
                        payload.x(), payload.y(), payload.z())));

        // 批量特效（Q 火流 / W 龙卷 / R 大字）
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.MultiEffectPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    double[] c = payload.coords();
                    for (int i = 0; i + 2 < c.length; i += 3) {
                        pkmoba$spawnEffect(payload.effectId(), c[i], c[i + 1], c[i + 2]);
                    }
                }));

        // 批量原版粒子（Q 火柱 / W 龙卷 / R 大字 / 场地边界）
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.VanillaParticlePayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    var level = Minecraft.getInstance().level;
                    if (level == null) {
                        return;
                    }
                    net.minecraft.core.particles.SimpleParticleType type = switch (payload.kind()) {
                        case "lava" -> net.minecraft.core.particles.ParticleTypes.LAVA;
                        case "smoke" -> net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE;
                        case "end_rod" -> net.minecraft.core.particles.ParticleTypes.END_ROD;
                        case "small_flame" -> net.minecraft.core.particles.ParticleTypes.SMALL_FLAME;
                        default -> net.minecraft.core.particles.ParticleTypes.FLAME;
                    };
                    double[] c = payload.coords();
                    for (int i = 0; i + 2 < c.length; i += 3) {
                        level.addParticle(type, c[i], c[i + 1], c[i + 2], 0.0, 0.0, 0.0);
                    }
                }));

        // 自定义火焰粒子：三档尺寸（小=W 龙卷 / 中=Q 火柱 / 大=R 大字）
        var particleReg = net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry.getInstance();
        particleReg.register(com.pkmoba.particle.PkmobaParticles.FLAME_PUFF_SMALL,
                sprites -> new com.pkmoba.client.particle.FlamePuffProvider(sprites, 0.35F, 8));
        particleReg.register(com.pkmoba.particle.PkmobaParticles.FLAME_PUFF,
                sprites -> new com.pkmoba.client.particle.FlamePuffProvider(sprites, 0.60F, 9));
        particleReg.register(com.pkmoba.particle.PkmobaParticles.FLAME_PUFF_GLYPH,
                sprites -> new com.pkmoba.client.particle.FlamePuffProvider(sprites, 0.70F, 4));
        particleReg.register(com.pkmoba.particle.PkmobaParticles.FLAME_PUFF_BIG,
                sprites -> new com.pkmoba.client.particle.FlamePuffProvider(sprites, 1.00F, 12));

        // 批量火焰团（Q 火柱 / W 龙卷 / R 大字）
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.FlamePayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    var level = Minecraft.getInstance().level;
                    if (level == null) {
                        return;
                    }
                    double[] c = payload.coords();
                    var rnd = level.random;
                    net.minecraft.core.particles.SimpleParticleType ptype = switch (payload.tier()) {
                        case "small" -> com.pkmoba.particle.PkmobaParticles.FLAME_PUFF_SMALL;
                        case "glyph" -> com.pkmoba.particle.PkmobaParticles.FLAME_PUFF_GLYPH;
                        case "big" -> com.pkmoba.particle.PkmobaParticles.FLAME_PUFF_BIG;
                        default -> com.pkmoba.particle.PkmobaParticles.FLAME_PUFF;
                    };
                    for (int i = 0; i + 2 < c.length; i += 3) {
                        double vx = 0.0;
                        double vy = 0.0;
                        double vz = 0.0;
                        if ("vortex".equals(payload.mode())) {
                            // 切向速度：让粒子真的绕着中心转，而不是各飞各的
                            double rx = c[i] - payload.cx();
                            double rz = c[i + 2] - payload.cz();
                            double rl = Math.sqrt(rx * rx + rz * rz);
                            if (rl > 1.0E-4) {
                                vx = -rz / rl * 0.28;
                                vz = rx / rl * 0.28;
                            }
                            vy = 0.10 + rnd.nextDouble() * 0.10;
                        } else {
                            vx = (rnd.nextDouble() - 0.5) * 0.03;
                            vy = 0.01 + rnd.nextDouble() * 0.02;
                            vz = (rnd.nextDouble() - 0.5) * 0.03;
                        }
                        level.addParticle(ptype, c[i], c[i + 1], c[i + 2], vx, vy, vz);
                    }
                }));

        GlyphRenderer.init();

        // Q: authoritative server origin/direction/range; model mouth is only a short cosmetic bridge.
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.JetPayload.TYPE, (payload, context) ->
                context.client().execute(() -> FlameJetEffects.spawn(context.client(), payload)));

        // 对战道具菜单：服务端授权后下发在线玩家名单，客户端只负责显示与回发意图
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.MenuOpenPayload.TYPE, (payload, context) ->
                context.client().execute(() -> BattleMenuScreen.handleMenuOpen(payload)));

        // 对局中对手宝可梦的真实血量（服务端真实值直发，客户端只渲染，不做本地推算）
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.DuelStatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientState.setOpponentState(
                        payload.active(), payload.hp(), payload.maxHp(),
                        payload.ownerId(), payload.ownerName(),
                        payload.pokemonName(), payload.species(), payload.teamRemaining())));

        // 1v1 挑战弹窗（服务端每秒下发剩余秒数；open=false 表示关闭）
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.ChallengePopupPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ChallengePopupScreen.handle(payload.open(),
                        payload.requestId(), payload.challengerName(), payload.secondsLeft())));

        // 开局/更换宝可梦：服务端下发可选种族，客户端弹选择界面
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.StarterListPayload.TYPE, (payload, context) ->
                context.client().execute(() -> StarterScreen.refresh(payload)));

        // 自己的队伍状态（HUD 左侧头像 + 圆点 + 换人倒计时）
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.TeamStatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    ClientState.setTeamState(payload.team(), payload.fainted(), payload.active(),
                            payload.summoned(), payload.pendingTicks(), payload.next());
                    // "下一只"预览变化时闪一下，让玩家看到切换目标更新
                    SwitchFx.onTeamStateChanged();
                }));

        // 战败动画：服务端标记某只宝可梦倒下并预留动画时长
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.FaintPayload.TYPE, (payload, context) ->
                context.client().execute(() -> FaintEffects.play(payload.entityId(), payload.ticks())));

        // 对局结果横幅
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.MatchResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> MatchResultOverlay.show(
                        payload.winnerName(), payload.youWin(), payload.reason())));

        // R 大字弹道：位置由服务端给，字面冒火/拖尾由客户端按笔画几何生成
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.GlyphPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    ClientState.setGlyph(payload.active(), payload.x(), payload.y(), payload.z(),
                            payload.size(), payload.dx(), payload.dy(), payload.dz());
                    if (!payload.active()) {
                        return;
                    }
                    var mc = Minecraft.getInstance();
                    var level = mc.level;
                    if (level == null) {
                        return;
                    }
                    Vec3 pos = new Vec3(payload.x(), payload.y(), payload.z());
                    Vec3 dir = new Vec3(payload.dx(), payload.dy(), payload.dz());
                    if (dir.lengthSqr() < 1.0E-6) {
                        return;
                    }
                    dir = dir.normalize();

                    // 字面基：与渲染一致（法线 = 飞行方向），所以火星正好贴在字面上
                    Vec3 upV = new Vec3(0, 1, 0);
                    Vec3 rightV = upV.cross(dir);
                    if (rightV.lengthSqr() < 1.0E-6) {
                        rightV = new Vec3(1, 0, 0);
                    }
                    rightV = rightV.normalize();
                    upV = dir.cross(rightV).normalize();
                    Vec3 right = new Vec3(-rightV.x, -rightV.y, -rightV.z); // 与渲染同镜像
                    Vec3 up = upV;
                    var rnd = level.random;
                    double size = payload.size();

                    // 字面冒火：沿笔画随机取点（含向外扩），粒子用最小档、数量加密
                    for (int i = 0; i < 20; i++) {
                        Vec3 g = GlyphGeometry.randomPoint(rnd);
                        Vec3 p = pos.add(right.scale(g.x * size)).add(up.scale(g.y * size));
                        level.addParticle(com.pkmoba.particle.PkmobaParticles.FLAME_PUFF_SMALL,
                                p.x, p.y, p.z,
                                (rnd.nextDouble() - 0.5) * 0.06,
                                0.04 + rnd.nextDouble() * 0.06,
                                (rnd.nextDouble() - 0.5) * 0.06);
                    }
                    // 字后面的拖尾
                    for (int i = 1; i <= 4; i++) {
                        Vec3 p = pos.subtract(dir.scale(0.25 * i));
                        level.addParticle(com.pkmoba.particle.PkmobaParticles.FLAME_PUFF_SMALL,
                                p.x, p.y, p.z,
                                (rnd.nextDouble() - 0.5) * 0.04,
                                0.02 + rnd.nextDouble() * 0.03,
                                (rnd.nextDouble() - 0.5) * 0.04);
                    }
                }));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientState.reset();
            GodView.reset();
            ChallengePopupScreen.reset();
        });

        LOGGER.info("[pkmoba] init (client) done");
    }

    private static void pkmoba$spawnEffect(String effectId, double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(effectId);
        if (id == null) {
            return;
        }
        try {
            // Kotlin object + companion：Java 侧要用 INSTANCE / Companion 访问
            var effect = BedrockParticleOptionsRepository.INSTANCE.getEffect(id);
            if (effect == null) {
                LOGGER.warn("[pkmoba] 找不到粒子特效 {}", id);
                return;
            }
            ParticleStorm.Companion.createAtPosition(mc.level, effect, new Vec3(x, y, z)).spawn();
        } catch (Throwable t) {
            LOGGER.warn("[pkmoba] 生成特效失败 {}: {}", id, t.toString());
        }
    }
}
