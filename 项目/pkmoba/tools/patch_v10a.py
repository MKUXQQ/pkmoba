from pathlib import Path

# ---------------- server: psychic recovers fast, dazzling gleam is self centred ----------------
combat = Path('src/main/java/com/pkmoba/combat/ExpansionCombat.java')
s = combat.read_text(encoding='utf-8')
s = s.replace("""                if (t <= 18) {
                    if (t % 2 == 0) cue(e, s, "pull", s.point, s.direction);""",
              """                if (t <= 8) {
                    if (t % 2 == 0) cue(e, s, "pull", s.point, s.direction);""")
s = s.replace("""                if (t == 20 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);""",
              """                if (t == 10 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);""")
combat.write_text(s, encoding='utf-8')

roster = Path('src/main/java/com/pkmoba/combat/ExpansionRoster.java')
r = roster.read_text(encoding='utf-8')
r = r.replace('m("精神强念", Shape.POINT, 2, 5, 8, 20, 13, 4.0, 14),',
              'm("精神强念", Shape.POINT, 2, 5, 4, 10, 13, 4.0, 14),')
r = r.replace('m("魔法闪耀", Shape.POINT, 2, 11, 6, 16, 7, 4.6, 34)',
              'm("魔法闪耀", Shape.SELF, 2, 11, 6, 16, 7, 5.0, 34)')
roster.write_text(r, encoding='utf-8')

# ---------------- client: bar never hidden by the model, a bit smaller and higher ---------------
bar = Path('src/main/java/com/pkmoba/client/PokemonOverheadBar.java')
b = bar.read_text(encoding='utf-8')
b = b.replace('private static final float WORLD_SCALE = 0.0185F;', 'private static final float WORLD_SCALE = 0.0155F;')
b = b.replace('private static final int BAR_WIDTH = 54;', 'private static final int BAR_WIDTH = 46;')
b = b.replace('private static final int BAR_HEIGHT = 5;', 'private static final int BAR_HEIGHT = 4;')
b = b.replace('private static final double BAR_LIFT = 0.30;', 'private static final double BAR_LIFT = 0.62;')
bar.write_text(b, encoding='utf-8')

rt = Path('src/main/java/com/pkmoba/client/OverheadBarRenderType.java')
rt.write_text('''package com.pkmoba.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

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

    /** Textured badge/mark quads drawn on top of everything for the hypnosis status. */
    public static final RenderType BADGE = RenderType.create(
            "pkmoba_hypno_badge",
            DefaultVertexFormat.POSITION_TEX,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_TEX_SHADER)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    private OverheadBarRenderType() {
    }
}
''', encoding='utf-8')
print('patched bar + render types')
