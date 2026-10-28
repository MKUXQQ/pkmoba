package com.pkmoba.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/** Depth-tested solid color quads for the world-space overhead bar. */
public final class OverheadBarRenderType {
    public static final RenderType BACK = create("background");
    public static final RenderType TRACK = create("track");
    public static final RenderType FILL = create("fill");
    public static final RenderType TRIM = create("trim");

    private OverheadBarRenderType() {
    }

    private static RenderType create(String name) {
        return RenderType.create(
                "pkmoba_overhead_bar_" + name,
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                        .createCompositeState(false));
    }
}
