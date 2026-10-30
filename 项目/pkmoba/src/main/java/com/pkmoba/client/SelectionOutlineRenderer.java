package com.pkmoba.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;

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
        // 0.39.2：用户明确要"删掉瞄准的白框、只保留地下那个"。
        // 实体包围盒（白色/红色方框）在上帝视角里既挡视线又和技能特效打架，直接不画；
        // 方块高亮只在"召唤选点"时才有意义（告诉玩家宝可梦会站在哪一格），其他时候也全部取消。
        if (!ClientState.isPlacementArmed()) {
            pose.popPose();
            return;
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
