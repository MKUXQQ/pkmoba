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

# ---------------- client: hypnosis marks, drawn as a badge on top of everything ------------------
icon = Path('src/main/java/com/pkmoba/client/HypnoStatusIcon.java')
icon.write_text('''package com.pkmoba.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * "Hypnotised" badge and hypnosis marks over every victim of Gengar's 催眠术.
 *
 * <p>The server stamps an invisible {@link MobEffects#UNLUCK} effect on the victim: amplifier 9
 * means "asleep", 0..2 is the number of hypnosis marks collected. The effect is synchronised to
 * clients, so this works for any living entity. The badge is drawn with a depth-less render type
 * so it is never swallowed by the Pokemon model or by its own overhead health bar.
 */
public final class HypnoStatusIcon {
    private static final ResourceLocation TEX = ResourceLocation.fromNamespaceAndPath(
            "pkmoba", "textures/effect/gengar_hypno_status.png");
    private static final ResourceLocation PIP = ResourceLocation.fromNamespaceAndPath(
            "pkmoba", "textures/effect/gengar_hypno_pip.png");
    private static final double RANGE_SQR = 64.0 * 64.0;

    private HypnoStatusIcon() {
    }

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(HypnoStatusIcon::render);
    }

    private static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui || context.camera() == null
                || context.consumers() == null || context.matrixStack() == null) {
            return;
        }
        Vec3 camera = context.camera().getPosition();
        Quaternionf rotation = context.camera().rotation();
        PoseStack pose = context.matrixStack();
        MultiBufferSource buffers = context.consumers();
        float partial = context.tickCounter().getGameTimeDeltaPartialTick(false);
        float time = (mc.level.getGameTime() + partial) / 20.0F;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()
                    || !living.hasEffect(MobEffects.UNLUCK)) {
                continue;
            }
            if (camera.distanceToSqr(living.position()) > RANGE_SQR) {
                continue;
            }
            var effect = living.getEffect(MobEffects.UNLUCK);
            int amplifier = effect == null ? 0 : effect.getAmplifier();
            double x = Mth.lerp(partial, living.xo, living.getX());
            // Above the health bar (which itself sits above the head), so nothing can cover it.
            double y = Mth.lerp(partial, living.yo, living.getY()) + living.getBbHeight() + 1.35
                    + Math.sin(time * 2.4) * .05;
            double z = Mth.lerp(partial, living.zo, living.getZ());
            if (amplifier >= 9) {
                double wobble = Math.sin(time * 1.8) * 8.0;
                quad(buffers, pose, camera, rotation, TEX, x, y, z, 1.35, 0.95, wobble);
            } else {
                int marks = Math.min(3, amplifier + 1);
                double spacing = .70;
                double start = -(marks - 1) * spacing * .5;
                for (int i = 0; i < marks; i++) {
   
