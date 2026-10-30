from pathlib import Path

hud = Path('src/main/java/com/pkmoba/client/SkillHud.java')
s = hud.read_text(encoding='utf-8')
anchor = "        ctx.drawString(mc.font, hpText, hpTextX, y + 4, withAlpha(textColor, valueAlpha), false);"
assert anchor in s
add = anchor + """

        // 被催眠状态：服务端用隐形 UNLUCK 标记（amplifier 9 = 已睡着，0..2 = 催眠标记数），
        // 这里在状态栏里直接写出来，玩家不用盯头顶图标也知道自家宝可梦的状态。
        if (pokemon != null) {
            var hypnotised = pokemon.getEffect(net.minecraft.world.effect.MobEffects.UNLUCK);
            if (hypnotised != null) {
                int amp = hypnotised.getAmplifier();
                String tag = amp >= 9 ? "被催眠" : "催眠标记 " + (amp + 1) + "/3";
                int tagColor = amp >= 9 ? 0xFF7B4FD0 : 0xFF9A6FE0;
                ctx.drawString(mc.font, tag, barLeft, y + PANEL_H - 11, tagColor, false);
            }
        }"""
s = s.replace(anchor, add)
hud.write_text(s, encoding='utf-8')

icon = Path('src/main/java/com/pkmoba/client/HypnoStatusIcon.java')
icon.write_text('''package com.pkmoba.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * "Hypnotised" badge / hypnosis marks over every victim of Gengar's 催眠术.
 *
 * <p>The server stamps an invisible {@link MobEffects#UNLUCK} effect on the victim: amplifier 9
 * means "asleep", 0..2 is the number of hypnosis marks collected so far. Because the effect is
 * synchronised to clients this renderer works for <b>any</b> living entity (Pokemon, players,
 * vanilla mobs), so the marks are visible over every creature the move touched.
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
        var rotation = context.camera().rotation();
        float partial = context.tickCounter().getGameTimeDeltaPartialTick(false);
        float time = (mc.level.getGameTime() + partial) / 20.0F;
        var right = rotation.transform(new org.joml.Vector3f(1, 0, 0));
        var up = rotation.transform(new org.joml.Vector3f(0, 1, 0));
        Vec3 axisRight = new Vec3(right.x, right.y, right.z);
        Vec3 axisUp = new Vec3(up.x, up.y, up.z);
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()
                    || !living.hasEffect(MobEffects.UNLUCK)) {
                continue;
            }
            if (camera.distanceToSqr(living.position()) > RANGE_SQR) {
                continue;
            }
            double x = Mth.lerp(partial, living.xo, living.getX());
            double y = Mth.lerp(partial, living.yo, living.getY()) + living.getBbHeight() + .72
                    + Math.sin(time * 2.4) * .05;
            double z = Mth.lerp(partial, living.zo, living.getZ());
            var effect = living.getEffect(MobEffects.UNLUCK);
            int amplifier = effect == null ? 0 : effect.getAmplifier();
            Vec3 center = new Vec3(x, y, z);
            if (amplifier >= 9) {
                // Asleep: one big rocking "Zz" badge plus a slow ring.
                double angle = Math.sin(time * 1.6) * .16;
                double cs = Math.cos(angle), sn = Math.sin(angle);
                Vec3 side = axisRight.scale(cs).add(axisUp.scale(sn)).scale(.78);
                Vec3 lift = axisUp.scale(cs).subtract(axisRight.scale(sn)).scale(.78);
                ExpansionSkillMeshes.raw(context, TEX,
                        center.subtract(side).subtract(lift),
                        center.subtract(side).add(lift),
                        center.add(side).add(lift),
                        center.add(side).subtract(lift),
                        0, 0, 1, 1, .97F);
            } else {
                // Awake but marked: one fat pip per hypnosis mark, so 1/3, 2/3 and 3/3 are readable.
                int marks = Math.min(3, amplifier + 1);
                double pip = .27;
                double spacing = .58;
                double start = -(marks - 1) * spacing * .5;
                for (int i = 0; i < marks; i++) {
                    Vec3 at = center.add(axisRight.scale(start + i * spacing))
                            .add(axisUp.scale(Math.sin(time * 3.0 + i) * .04));
                    Vec3 px = axisRight.scale(pip), py = axisUp.scale(pip);
                    ExpansionSkillMeshes.raw(context, PIP,
                            at.subtract(px).subtract(py), at.subtract(px).add(py),
                            at.add(px).add(py), at.add(px).subtract(py),
                            0, 0, 1, 1, .95F);
                }
            }
        }
    }
}
''', encoding='utf-8')
print('patched hud + hypno icon')
