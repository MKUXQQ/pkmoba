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
        // 0.38.3：喷火龙 Q/R 专用施法动画（自制动画组 pkmoba_charizard，按引导阶段切换）
        CharizardSkillAnimator.init();
        HudOverlay.init();
        SkillHud.init();
        SelectionOutlineRenderer.init();
        // m04660：普通攻击与普攻白圈已整体删除，不再注册 AttackRangeRenderer。
        ClickMarker.init();
        BallFlightFx.init();
        RecallFx.init();
        SkillRangeRenderer.init();
        TornadoEffects.init();
        WaterEffects.init();
        // 0.43：水箭龟 R「水炮风暴」——地面范围圈 + 6 道旋转水柱 + 螺旋水带 + 扩散水刃
        com.pkmoba.client.StormEffects.init();
        // 0.38：水箭龟 R「喷水」降雨（收到 SpoutPayload 后本地生成雨滴）
        SpoutEffects.init();
        // 0.38.1：水箭龟 E「冲浪术」（channel 5）脚下的水墙/水花
        SurfEffects.init();
        MatchResultOverlay.init();
        // 0.39：命中反馈（粒子/顿帧/震屏/斩杀提示）与战斗音效（循环音量淡入淡出）
        HitFeedback.init();
        CombatAudio.init();
        TargetOverlay.init();

        // 投球召唤的精灵球飞行（客户端自己按抛物线渲染）
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.BallFlightPayload.TYPE, (payload, context) ->
                context.client().execute(() -> BallFlightFx.start(payload.fx(), payload.fy(), payload.fz(),
                        payload.tx(), payload.ty(), payload.tz(), payload.durationTicks(),
                        payload.item(), payload.seed())));

        // 红光召回 / 放出（客户端自绘光束）
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.RecallFxPayload.TYPE, (payload, context) ->
                context.client().execute(() -> RecallFx.start(payload.entityId(), payload.playerId(),
                        payload.durationTicks(), payload.recall())));

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
                context.client().execute(() -> {
                    // 0.38.3：SkillStatePayload 里没有 entityId，客户端只能用本地实体引用自证归属。
                    // 切换/阵亡的那一两帧里旧实体已经不在客户端，这一包很可能还是上一只的状态，
                    // 直接写进 ClientState 就会把旧血量/冷却画到新物种的 HUD 上（"血条不同步"）。
                    int entityId = ClientState.getCharizardId();
                    var entity = context.client().level == null ? null : context.client().level.getEntity(entityId);
                    boolean alive = entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
                            && pokemon.isAlive();
                    if (!alive) {
                        if (payload.hp() <= 0.0F) {
                            // 阵亡/收回：没有活实体也接受服务端给的设计上限，HUD 画 0/上限。
                            ClientState.setFaintedState(payload.maxHp());
                        } else {
                            // 还带血量却对不上任何活实体：只可能是过期帧，清成"同步中"并丢弃。
                            ClientState.clearCombatState();
                        }
                        return;
                    }
                    ClientState.setSkillState(
                            payload.hp(), payload.maxHp(), payload.points(),
                            payload.cdQ(), payload.cdW(), payload.cdE(), payload.cdR(),
                            payload.channel(), payload.charge(),
                            payload.arenaDist(), payload.arenaCx(), payload.arenaCy(), payload.arenaCz(),
                            payload.arenaRadius());
                    // 让本地 Pokemon 对象认同服务端下发的血量上限（P 界面与血条一致）
                    ClientHpSync.apply(entityId, payload.maxHp());
                }));

        // 招式粒子特效
        ClientPlayNetworking.registerGlobalReceiver(EffectPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    // 0.39：命中反馈复用同一个 EffectPayload（effectId 前缀 pkmoba:hit/），
                    // 由 HitFeedback 解析，命中包不再当普通粒子特效处理。
                    if (HitFeedback.handle(payload)) {
                        TargetOverlay.captureHit(payload);
                        return;
                    }
                    pkmoba$spawnEffect(payload.effectId(), payload.x(), payload.y(), payload.z());
                }));

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
        // H 键房间大厅快照
        ClientPlayNetworking.registerGlobalReceiver(com.pkmoba.network.RoomListPayload.TYPE,
                (payload, context) -> com.pkmoba.client.ClientRooms.handle(payload));

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
            // 0.39：断开时清掉震屏/顿帧/斩杀提示并停掉所有循环音（否则残留实例会跨世界播放）
            HitFeedback.reset();
            CombatAudio.reset();
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
