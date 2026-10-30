"""0.78 client pass: Moonblast launch animation, Psychic range+pull, Blaziken Flame Mark kit, mark badge."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
src = ROOT / "src/main/java/com/pkmoba"

# ---------------------------------------------------------------- badge render type
rt = src / "client/OverheadBarRenderType.java"
text = rt.read_text(encoding="utf-8")
anchor = """    /** Textured pip quads drawn on top of everything for one hypnosis mark. */"""
assert anchor in text
text = text.replace(anchor, """    /** Flame Mark badge for Blaziken's branded victims. */
    public static final RenderType BADGE_MARK = badge("pkmoba_blaze_mark",
            "textures/effect/blaziken_mark.png");

""" + anchor)
rt.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------- mark badge renderer
icon = src / "client/BlazeMarkIcon.java"
icon.write_text('''package com.pkmoba.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Flame Mark badge over every victim branded by Blaziken's Blaze Kick.
 *
 * <p>The server stamps an invisible {@link MobEffects#HERO_OF_THE_VILLAGE} effect (visible=false, so
 * no HUD icon and no particles) for the life of the brand; the effect syncs to clients on its own,
 * which means the badge follows any living victim without extra packets.
 */
public final class BlazeMarkIcon {
    private static final double RANGE_SQR = 64.0 * 64.0;

    private BlazeMarkIcon() {
    }

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(BlazeMarkIcon::render);
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
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
            if (!living.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) continue;
            if (camera.distanceToSqr(living.position()) > RANGE_SQR) continue;
            double x = Mth.lerp(partial, living.xo, living.getX());
            double y = Mth.lerp(partial, living.yo, living.getY()) + living.getBbHeight() + 1.05
                    + Math.sin(time * 3.2) * .05;
            double z = Mth.lerp(partial, living.zo, living.getZ());
            float pulse = .9F + .1F * (float) Math.sin(time * 6.0);
            quad(buffers, pose, camera, rotation, OverheadBarRenderType.BADGE_MARK,
                    x, y, z, .34 * pulse, .34 * pulse);
        }
    }

    private static void quad(MultiBufferSource buffers, PoseStack pose, Vec3 camera, Quaternionf rotation,
                             RenderType renderType, double x, double y, double z,
                             double halfWidth, double halfHeight) {
        pose.pushPose();
        pose.translate(x - camera.x, y - camera.y, z - camera.z);
        pose.mulPose(rotation);
        var matrix = pose.last();
        var consumer = buffers.getBuffer(renderType);
        float w = (float) halfWidth, h = (float) halfHeight;
        consumer.addVertex(matrix, -w, -h, 0).setUv(0, 1).setColor(255, 255, 255, 255);
        consumer.addVertex(matrix, -w, h, 0).setUv(0, 0).setColor(255, 255, 255, 255);
        consumer.addVertex(matrix, w, h, 0).setUv(1, 0).setColor(255, 255, 255, 255);
        consumer.addVertex(matrix, w, -h, 0).setUv(1, 1).setColor(255, 255, 255, 255);
        pose.popPose();
    }
}
''', encoding="utf-8")

client = src / "client/PkmobaClient.java"
text = client.read_text(encoding="utf-8")
assert "HypnoStatusIcon.init();" in text
text = text.replace("HypnoStatusIcon.init();", "HypnoStatusIcon.init();\n        BlazeMarkIcon.init();")
client.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------- gardevoir fx
gard = src / "client/fx/GardevoirFx.java"
text = gard.read_text(encoding="utf-8")

start = text.index("    private static void psychic(WorldRenderContext c, FxView v, float a) {")
end = text.index("    // ---- W 月亮之力")
new_psychic = '''    private static void psychic(WorldRenderContext c, FxView v, float a) {
        Vec3 centre = v.pos.add(0, .12, 0);
        if (v.is("start")) {
            range(c, v, 4.0, a, 1.0);
            face(c, GLOW, v.pos.add(0, 1.0, 0), 1.4, 1.4, 0, a * .5f);
        } else if (v.is("pull")) {
            // The well is drawn at its real radius (4 blocks) with the rim marked, then twelve
            // textured arms are dragged from the rim into the centre so the pull is unmistakable.
            range(c, v, 4.0, a * .95f, .9);
            double tighten = 1.0 - .55 * v.progress;
            for (int i = 0; i < 12; i++) {
                double fast = v.time * 1.6;
                double angle = fast + i * (Math.PI * 2 / 12);
                double rr = 3.85 * tighten;
                Vec3 from = centre.add(Math.cos(angle) * rr, .75 + .25 * Math.sin(i * 1.7 + v.time),
                        Math.sin(angle) * rr);
                Vec3 to = centre.add(Math.cos(angle + .45) * .35, 1.0, Math.sin(angle + .45) * .35);
                v.fx.flow(from, to, .55, v.time + i * .3, a * .95f);
            }
            for (int i = 0; i < 4; i++) {
                face(c, CRUSH, v.pos.add(0, 1.0, 0), 3.6 - .7 * i, 3.6 - .7 * i,
                        v.time * (1.2 + i * .6), a * (.55f - .1f * i));
            }
            face(c, GLOW, v.pos.add(0, 1.05, 0), 1.5 + .5 * v.progress, 1.5 + .5 * v.progress, 0,
                    a * .85f);
        } else if (v.is("hold")) {
            // Everything caught by the well is pinned: the rim snaps inward and the core swells.
            range(c, v, 4.0 - 1.9 * v.progress, a, 1.0);
            ExpansionSkillMeshes.sphere(c, v.pos.add(0, 1.0, 0),
                    new Vec3(.6 + .7 * v.progress, .6 + .7 * v.progress, .6 + .7 * v.progress),
                    v.time * 1.4, CRUSH, a * .95f, true);
            face(c, GLOW, v.pos.add(0, 1.0, 0), 2.2 + 1.2 * v.progress, 2.2 + 1.2 * v.progress, 0,
                    a * .95f);
            ExpansionSkillMeshes.ring(c, v.pos.add(0, 1.02, 0), 2.0 - 1.0 * v.progress, .14, RING,
                    a * .9f);
        } else if (v.in("impact", "detonate")) {
            float burst = (float) Math.min(1.0, v.progress * 1.8);
            face(c, FLASH, v.pos.add(0, 1.0, 0), 2.0 + 2.6 * burst, 2.0 + 2.6 * burst, v.time,
                    a * (.9f - .3f * burst));
            ground(c, RING, centre, 2.6 + 3.0 * burst, a * (.8f - .25f * burst));
            for (int i = 0; i < 4; i++) {
                double angle = i * Math.PI / 2 + .5;
                face(c, SPARK, v.pos.add(Math.cos(angle) * (1.4 + 1.8 * burst), .7,
                        Math.sin(angle) * (1.4 + 1.8 * burst)), .5, .5, v.time * 2, a * .8f);
            }
        }
    }

    /** Draws the skill's real radius on the ground with a bright rim and 16 tick marks. */
    private static void range(WorldRenderContext c, FxView v, double radius, float a, double rim) {
        Vec3 base = v.pos.add(0, .06, 0);
        ground(c, RING, base, radius * 2.0, a * .55f);
        ExpansionSkillMeshes.ring(c, base.add(0, .02, 0), radius, .16, RING, a * (float) rim);
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8.0;
            face(c, SPARK, base.add(Math.cos(angle) * radius, .05, Math.sin(angle) * radius),
                    .34, .34, angle, a * .9f);
        }
    }

'''
text = text[:start] + new_psychic + text[end:]

# moonblast: replace the falling-moon branch with a self-animated launch
old_moon = """        } else if (v.is("moon")) {"""
new_moon = """        } else if (v.is("launch")) {
            // Fire-and-forget: the client animates the whole fall from the single launch cue, so the
            // moon keeps dropping even if the caster starts Psychic halfway through.
            double q = Math.min(1.0, v.progress);
            double height = 8.5 * (1 - q * q);
            Vec3 at = v.pos.add(0, height, 0);
            double radius = 2.6;
            ExpansionSkillMeshes.sphere(c, at, new Vec3(radius * 1.02, radius * 1.02, radius * 1.02),
                    v.time * .3, MOON_CLASSIC, a * .4f, true);
            ExpansionSkillMeshes.sphere(c, at, new Vec3(radius, radius, radius), v.time * .55,
                    MOON_CLASSIC, a, true);
            for (int i = 1; i <= 3; i++) {
                double trail = Math.max(0, q * 26 - i * 2.2);
                double tq = Math.min(1.0, trail / 26.0);
                Vec3 past = v.pos.add(0, 8.5 * (1 - tq * tq), 0);
                ExpansionSkillMeshes.sphere(c, past,
                        new Vec3(radius * .55, radius * .55, radius * .55), v.time * .4,
                        MOON_CLASSIC, a * (.22f - .05f * i), true);
            }
        } else if (v.is("moon")) {"""
assert old_moon in text
text = text.replace(old_moon, new_moon)

gard.write_text(text, encoding="utf-8")
print("client patched (gardevoir, badge)")
