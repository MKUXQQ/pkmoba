package com.pkmoba.client;

import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.RenderStateShard;

/**
 * Overlay render types for the overhead bars and the hypnosis badges.
 *
 * <p>Both deliberately <b>ignore the depth buffer</b>: before this the bar sat inside the taller
 * Pokemon models (Gardevoir, Charizard, Tyranitar...) and was simply culled away by the body, so
 * the player could not see it at all. Drawing after the entities with no depth test keeps the bar
 * and the hypnosis marks readable no matter how big the model is.
 */
public final class OverheadBarRenderType {
    public static final RenderType BAR = RenderType.create(
            "pkmoba_overhead_bar",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    /** Textured badge quads drawn on top of everything for the "asleep" badge. */
    public static final RenderType BADGE_SLEEP = badge("pkmoba_hypno_sleep",
            "textures/effect/gengar_hypno_status.png");

    /** Flame Mark badge for Blaziken's branded victims. */
    public static final RenderType BADGE_MARK = badge("pkmoba_blaze_mark",
            "textures/effect/blaziken_mark.png");

    /** Textured pip quads drawn on top of everything for one hypnosis mark. */
    public static final RenderType BADGE_PIP = badge("pkmoba_hypno_pip",
            "textures/effect/gengar_hypno_pip.png");

    private static RenderType badge(String name, String path) {
        return RenderType.create(
                name,
                DefaultVertexFormat.POSITION_TEX,
                VertexFormat.Mode.QUADS,
                256,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.POSITION_TEX_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(
                                ResourceLocation.fromNamespaceAndPath("pkmoba", path), false, false))
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false));
    }

    private OverheadBarRenderType() {
    }
}
