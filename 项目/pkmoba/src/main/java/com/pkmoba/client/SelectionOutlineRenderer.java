package com.pkmoba.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 上帝视角或召唤选点时，高亮鼠标当前指向的方块。 */
public final class SelectionOutlineRenderer {
    private SelectionOutlineRenderer() {
    }

    public static void init() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(SelectionOutlineRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null
                || mc.screen != null
                || (!GodView.isActive() && !ClientState.isPlacementArmed())) {
            return;
        }

        Vec3 camera = context.camera().getPosition();
        var pose = context.matrixStack();
        if (pose == null || context.consumers() == null) return;
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer lines = context.consumers().getBuffer(RenderType.lines());
        var target = Targeting.pick(mc);
        if (target != null && GodView.isActive()) {
            boolean covered = Targeting.covered(mc, target);
            LevelRenderer.renderLineBox(pose, lines, target.getBoundingBox().inflate(0.01),
                    covered ? 1.0F : 0.5F, covered ? 0.15F : 0.5F, covered ? 0.15F : 0.5F, 1.0F);
        }
        BlockHitResult hit = AimRay.pick(mc);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
            if (!shape.isEmpty()) {
                LevelRenderer.renderLineBox(pose, lines, shape.bounds().move(pos), 1.0F, 0.9F, 0.1F, 1.0F);
            }
        }
        pose.popPose();
    }
}
