package com.pkmoba.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 技能极限范围指示（0.39.4 新增，用于把"普攻白圈"与"技能范围"统一成一套读法）。
 *
 * <p>用户原话："技能（如加农水炮/水流喷射）也画自己的极限范围，避免玩家靠猜"。
 *
 * <p>交互：把鼠标悬停在 HUD 的技能图标上，就在世界里画出这个技能的判定范围：
 * <ul>
 *   <li><b>直线/扇形类</b>（加农水炮、喷射火焰、冲浪术、喷水）：从宝可梦出发、沿瞄准方向的一条矩形；</li>
 *   <li><b>落点圆形类</b>（火焰漩涡、大字爆炎）：在瞄准落点画一个圆盘（复用普攻白圈的贴图）；</li>
 *   <li><b>自身范围类</b>（高速旋转）：以宝可梦为中心画圆盘。</li>
 * </ul>
 *
 * <p>所有数值都与服务端常量对齐（{@code CANNON_RANGE}、{@code Q_RANGE}、{@code W_RADIUS}…），
 * 所以"画多大就是打多远"，不是另编一套。
 */
public final class SkillRangeRenderer {
    private static final ResourceLocation RING_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/range_ring.png");
    /** 贴图里圆环半径占贴图半径的比例。 */
    private static final double TEXTURE_RADIUS_RATIO = 0.93;
    private static final double GROUND_LIFT = 0.05;
    private static final double MAX_RENDER_DISTANCE_SQR = 96.0 * 96.0;

    private static final float FILL_R = 0.62F;
    private static final float FILL_G = 0.92F;
    private static final float FILL_B = 1.0F;
    private static final float FILL_ALPHA = 0.13F;
    private static final float DISC_ALPHA = 0.85F;

    private static boolean initialized;
    private static boolean crashLogged;

    private SkillRangeRenderer() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        WorldRenderEvents.AFTER_TRANSLUCENT.register(SkillRangeRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) {
            return;
        }
        if (context.matrixStack() == null || context.consumers() == null || context.camera() == null) {
            return;
        }
        int slot = SkillHud.hoveredSlot();
        if (slot < 0 || slot > 3) {
            return;
        }
        LivingEntity self = controlledPokemon(mc);
        if (self == null) {
            return;
        }
        Vec3 camera = context.camera().getPosition();
        if (self.position().distanceToSqr(camera) > MAX_RENDER_DISTANCE_SQR) {
            return;
        }
        try {
            draw(context, mc, self, slot);
        } catch (Throwable t) {
            // 渲染线程抛异常会拖崩客户端：这一帧不画就行，别把游戏带下去。
            if (!crashLogged) {
                crashLogged = true;
                org.slf4j.LoggerFactory.getLogger("pkmoba-skillrange")
                        .warn("[pkmoba] 技能范围指示渲染失败，已跳过（后续不再刷日志）", t);
            }
        }
    }

    private static void draw(WorldRenderContext context, Minecraft mc, LivingEntity self, int slot) {
        boolean blastoise = isBlastoise(self);
        Vec3 origin = self.position();
        Vec3 aim = aimPoint(mc, self);
        Vec3 flat = new Vec3(aim.x - origin.x, 0.0, aim.z - origin.z);
        if (flat.lengthSqr() < 1.0E-4) {
            flat = new Vec3(0.0, 0.0, 1.0);
        }
        flat = flat.normalize();

        PoseStack poseStack = context.matrixStack();
        poseStack.pushPose();
        poseStack.translate(-context.camera().getPosition().x, -context.camera().getPosition().y,
                -context.camera().getPosition().z);
        PoseStack.Pose pose = poseStack.last();

        if (blastoise) {
            switch (slot) {
                case 0 -> rect(mc, context, pose, origin, flat, 16.0, 1.6);
                case 1 -> disc(context, mc, pose, origin, 3.0);
                case 2 -> rect(mc, context, pose, origin, flat, 6.0, 2.0);
                case 3 -> rect(mc, context, pose, origin, flat, 14.0, 0.9);
                default -> { }
            }
        } else {
            switch (slot) {
                case 0 -> rect(mc, context, pose, origin, flat, 11.0, 0.9);
                case 1 -> {
                    // 火焰漩涡：在瞄准落点（截断到 12 格）画半径 4.5 的圆
                    Vec3 land = origin.add(flat.scale(Math.min(12.0, horizontalDistance(origin, aim))));
                    disc(context, mc, pose, land, 4.5);
                }
                case 2 -> rect(mc, context, pose, origin, flat, 12.0, 1.0);
                case 3 -> {
                    // 大字爆炎：落点（最多 20 格）画半径 3.0 的爆炸圈
                    Vec3 land = origin.add(flat.scale(Math.min(20.0, horizontalDistance(origin, aim))));
                    disc(context, mc, pose, land, 3.0);
                }
                default -> { }
            }
        }
        poseStack.popPose();
    }

    /** 从 origin 沿 dir 的一条矩形（长度 length、半宽 halfWidth），四条边贴地采样。 */
    private static void rect(Minecraft mc, WorldRenderContext context, PoseStack.Pose pose,
                             Vec3 origin, Vec3 dir, double length, double halfWidth) {
        Vec3 side = new Vec3(-dir.z, 0.0, dir.x).scale(halfWidth);
        Vec3 forward = dir.scale(length);
        Vec3 a = origin.add(side);
        Vec3 b = origin.subtract(side);
        Vec3 c = b.add(forward);
        Vec3 d = a.add(forward);
        double ya = groundY(mc, a) + GROUND_LIFT;
        double yb = groundY(mc, b) + GROUND_LIFT;
        double yc = groundY(mc, c) + GROUND_LIFT;
        double yd = groundY(mc, d) + GROUND_LIFT;
        VertexConsumer buffer = context.consumers().getBuffer(RenderType.debugQuads());
        solid(buffer, pose, a.x, ya, a.z, b.x, yb, b.z, c.x, yc, c.z);
        solid(buffer, pose, a.x, ya, a.z, c.x, yc, c.z, d.x, yd, d.z);
    }

    /** 在 center 画一个半径 radius 的圆盘（用普攻白圈那张软边贴图，保证平滑）。 */
    private static void disc(WorldRenderContext context, Minecraft mc, PoseStack.Pose pose,
                             Vec3 center, double radius) {
        double y = groundY(mc, center) + GROUND_LIFT;
        double half = radius / TEXTURE_RADIUS_RATIO;
        VertexConsumer buffer = context.consumers().getBuffer(RenderType.entityTranslucentCull(RING_TEXTURE));
        float x0 = (float) (center.x - half);
        float x1 = (float) (center.x + half);
        float z0 = (float) (center.z - half);
        float z1 = (float) (center.z + half);
        float v = (float) y;
        textured(buffer, pose, x0, v, z0, 0.0F, 0.0F, DISC_ALPHA);
        textured(buffer, pose, x0, v, z1, 0.0F, 1.0F, DISC_ALPHA);
        textured(buffer, pose, x1, v, z1, 1.0F, 1.0F, DISC_ALPHA);
        textured(buffer, pose, x1, v, z0, 1.0F, 0.0F, DISC_ALPHA);
        textured(buffer, pose, x1, v, z0, 1.0F, 0.0F, DISC_ALPHA);
        textured(buffer, pose, x1, v, z1, 1.0F, 1.0F, DISC_ALPHA);
        textured(buffer, pose, x0, v, z1, 0.0F, 1.0F, DISC_ALPHA);
        textured(buffer, pose, x0, v, z0, 0.0F, 0.0F, DISC_ALPHA);
    }

    private static void solid(VertexConsumer buffer, PoseStack.Pose pose,
                              double ax, double ay, double az,
                              double bx, double by, double bz,
                              double cx, double cy, double cz) {
        planeVertex(buffer, pose, ax, ay, az);
        planeVertex(buffer, pose, bx, by, bz);
        planeVertex(buffer, pose, cx, cy, cz);
        planeVertex(buffer, pose, cx, cy, cz);
    }

    private static void planeVertex(VertexConsumer buffer, PoseStack.Pose pose,
                                    double x, double y, double z) {
        buffer.addVertex(pose, (float) x, (float) y, (float) z)
                .setColor(FILL_R, FILL_G, FILL_B, FILL_ALPHA)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    private static void textured(VertexConsumer buffer, PoseStack.Pose pose,
                                 float x, float y, float z, float u, float v, float alpha) {
        buffer.addVertex(pose, x, y, z)
                .setColor(1.0F, 1.0F, 1.0F, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    private static boolean isBlastoise(LivingEntity entity) {
        try {
            var pokemon = ((com.cobblemon.mod.common.entity.pokemon.PokemonEntity) entity).getPokemon();
            return "cobblemon:blastoise".equals(pokemon.getSpecies().getResourceIdentifier().toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static LivingEntity controlledPokemon(Minecraft mc) {
        if (mc.level == null) {
            return null;
        }
        var found = mc.level.getEntity(ClientState.getCharizardId());
        return found instanceof LivingEntity living ? living : null;
    }

    /** 瞄准点：上帝视角取光标地面点，否则取宝可梦正前方 8 格。 */
    private static Vec3 aimPoint(Minecraft mc, LivingEntity self) {
        BlockHitResult hit = AimRay.pick(mc);
        if (hit != null) {
            return hit.getLocation();
        }
        Vec3 look = new Vec3(-Math.sin(Math.toRadians(self.getYRot())), 0.0,
                Math.cos(Math.toRadians(self.getYRot())));
        return self.position().add(look.scale(8.0));
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z));
    }

    private static double groundY(Minecraft mc, Vec3 pos) {
        if (mc.player == null || mc.level == null) {
            return pos.y;
        }
        Vec3 from = new Vec3(pos.x, pos.y + 3.0, pos.z);
        Vec3 to = new Vec3(pos.x, pos.y - 6.0, pos.z);
        BlockHitResult hit = mc.level.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().y : pos.y;
    }
}
