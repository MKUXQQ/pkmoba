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
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * R 的「大」字：加粗火焰字形 + 立体厚度 + 固定朝向。
 *
 * <p><b>朝向</b>：按用户的定义，字面**垂直于飞行方向**（法线 = 弹道方向），
 * 在发射瞬间由飞行方向唯一确定，**不随镜头移动**。
 * 因为玩家是在弹道后方看，所以 UV 的 U 轴做了一次镜像，保证看到的是正的「大」。
 *
 * <p><b>厚度</b>：沿弹道方向把同一张字贴图叠 3 层（远层压暗 → 近层最亮）。
 *
 * <p><b>燃烧</b>：最前面再叠一层加法混合的火焰动画。
 */
public final class GlyphRenderer {
    private static final ResourceLocation BASE =
            ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/glyph_flame_base.png");
    private static final ResourceLocation[] FIRE = new ResourceLocation[10];

    static {
        for (int i = 0; i < 10; i++) {
            FIRE[i] = ResourceLocation.fromNamespaceAndPath("pkmoba", "textures/effect/glyph_flame_fire_" + i + ".png");
        }
    }

    /** 沿弹道方向的层偏移（正=更远）与亮度。绘画顺序：远 → 近。 */
    private static final double[] LAYER_OFFSET = {0.18, 0.0, -0.18};
    private static final int[] LAYER_TINT = {0xFF8A4A20, 0xFFD08040, 0xFFFFFFFF};

    private GlyphRenderer() {
    }

    public static void init() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(GlyphRenderer::render);
    }

    private static void render(WorldRenderContext ctx) {
        if (!ClientState.hasGlyph()) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        Vec3 dir = ClientState.getGlyphDir();
        if (dir.lengthSqr() < 1.0E-6) {
            dir = new Vec3(0, 0, 1);
        }
        dir = dir.normalize();

        // 字面基：法线 = 飞行方向，所以字是"正面朝前冲"
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 right = up.cross(dir);
        if (right.lengthSqr() < 1.0E-6) {
            right = new Vec3(1, 0, 0);
        }
        right = right.normalize();
        up = dir.cross(right).normalize();

        Vec3 cam = ctx.camera().getPosition();
        Vec3 worldPos = ClientState.getGlyphPos();
        float half = (float) (ClientState.getGlyphSize() * 0.5);
        PoseStack pose = ctx.matrixStack();
        Matrix4f m = pose.last().pose();

        // 1) 三层厚度
        for (int i = 0; i < LAYER_OFFSET.length; i++) {
            Vec3 center = worldPos.add(dir.scale(LAYER_OFFSET[i])).subtract(cam);
            int tint = LAYER_TINT[i];
            quad(ctx.consumers().getBuffer(RenderType.entityTranslucentEmissive(BASE)),
                    m, center, right, up, half,
                    (tint >> 16) & 0xFF, (tint >> 8) & 0xFF, tint & 0xFF);
        }

        // 2) 最前面叠流动火焰
        int frame = (int) ((mc.level.getGameTime() / 2) % FIRE.length);
        Vec3 fireCenter = worldPos.add(dir.scale(-0.20)).subtract(cam);
        quad(ctx.consumers().getBuffer(RenderType.eyes(FIRE[frame])),
                m, fireCenter, right, up, half, 255, 255, 255);
    }

    /**
     * 画一个面片。U 轴做了镜像：玩家在弹道后方，看到的是背面，
     * 不镜像的话「大」会左右翻转。
     */
    private static void quad(VertexConsumer vc, Matrix4f m, Vec3 center, Vec3 right, Vec3 up,
                             float half, int r, int g, int b) {
        Vec3 bl = center.add(right.scale(-half)).add(up.scale(-half));
        Vec3 br = center.add(right.scale(half)).add(up.scale(-half));
        Vec3 tr = center.add(right.scale(half)).add(up.scale(half));
        Vec3 tl = center.add(right.scale(-half)).add(up.scale(half));

        vertex(vc, m, bl, 1.0F, 1.0F, r, g, b);
        vertex(vc, m, br, 0.0F, 1.0F, r, g, b);
        vertex(vc, m, tr, 0.0F, 0.0F, r, g, b);
        vertex(vc, m, tl, 1.0F, 0.0F, r, g, b);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vec3 p, float u, float v,
                               int r, int g, int b) {
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z)
                .setColor(r, g, b, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                // 0.64：法线必须朝上。entityTranslucentEmissive 的顶点着色器会乘
                // minecraft_mix_light()，(0,0,1) 只拿到 0.74 亮度 → 字形整体发灰。
                .setNormal(0.0F, 1.0F, 0.0F);
    }
}
