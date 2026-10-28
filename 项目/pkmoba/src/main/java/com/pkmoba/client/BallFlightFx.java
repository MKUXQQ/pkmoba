package com.pkmoba.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 投球召唤的精灵球飞行（0.41.8，纯客户端渲染）。
 *
 * <p>用户 m05076：「宝可梦球没有飞出去一直卡在头顶」。
 * 之前是服务端每 tick {@code ItemEntity.setPos()}，物品实体的位置同步在客户端会抖/卡；
 * 现在服务端只广播 {@link com.pkmoba.network.BallFlightPayload}，客户端用同一条抛物线
 * 自己插值渲染一个旋转的精灵球 —— 帧率高、绝对平滑、世界里不留实体。
 *
 * <p>抛物线公式与服务端 {@code BallThrow.parabola} 完全一致，所以"球落地"与
 * "宝可梦出现在落点"在视觉上是同一个瞬间。
 */
public final class BallFlightFx {
    /** 单个飞行中的球。 */
    private static final class Flight {
        List<Vec3> points;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        long startNanos;
        int durationTicks;
        long seed;
        ItemStack stack;
    }

    private static final List<Flight> FLIGHTS = new ArrayList<>();
    /** 球模型的显示大小。 */
    private static final float BALL_SCALE = 0.55F;
    /** 每 tick 自转角度。 */
    private static final float SPIN_DEG_PER_TICK = 26.0F;

    private static boolean initialized;

    private BallFlightFx() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BallFlightFx::render);
    }

    /** 收到服务端广播后登记一次飞行。 */
    public static void start(long seed, List<Vec3> points) {
        FLIGHTS.removeIf(f -> f.seed==seed);
        Minecraft mc=Minecraft.getInstance();
        if(points.size()<2 || mc.level==null) return;
        String itemId="cobblemon:poke_ball";
        Item item = null;
        try {
            item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        } catch (Throwable ignored) {
            // 拿不到就用雪球兜底，至少还有东西在飞。
        }
        if (item == null || item == Items.AIR) {
            item = Items.SNOWBALL;
        }
        Flight flight = new Flight();
        flight.points = List.copyOf(points);
        flight.dimension = mc.level.dimension();
        flight.startNanos = System.nanoTime();
        flight.durationTicks = points.size()-1;
        flight.seed = seed;
        flight.stack = new ItemStack(item);
        FLIGHTS.add(flight);
    }

    private static void render(WorldRenderContext context) {
        if (FLIGHTS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            FLIGHTS.clear();
            return;
        }
        if (context.matrixStack() == null || context.consumers() == null || context.camera() == null) {
            return;
        }
        PoseStack poseStack = context.matrixStack();
        Vec3 camera = context.camera().getPosition();
        long now = System.nanoTime();
        Iterator<Flight> iterator = FLIGHTS.iterator();
        while (iterator.hasNext()) {
            Flight flight = iterator.next();
            double seconds = (now - flight.startNanos) / 1.0E9;
            double t = seconds / (flight.durationTicks / 20.0D);
            if (t >= 1.0D || !flight.dimension.equals(mc.level.dimension())) {
                iterator.remove();
                continue;
            }
            double sample=t*flight.durationTicks;
            int index=Math.min(flight.durationTicks-1,(int)sample);
            Vec3 pos=flight.points.get(index).lerp(flight.points.get(index+1),sample-index);
            poseStack.pushPose();
            poseStack.translate(pos.x - camera.x, pos.y - camera.y, pos.z - camera.z);
            // 自转 + 一点倾斜，看起来是"投出去的球"而不是漂浮的图标。
            poseStack.mulPose(Axis.YP.rotationDegrees((float) (t * flight.durationTicks * SPIN_DEG_PER_TICK)));
            poseStack.mulPose(Axis.XP.rotationDegrees(28.0F));
            poseStack.scale(BALL_SCALE, BALL_SCALE, BALL_SCALE);
            try {
                var model = mc.getItemRenderer().getModel(flight.stack, mc.level, mc.player, 0);
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                Lighting.setupForFlatItems();
                mc.getItemRenderer().render(flight.stack, ItemDisplayContext.GROUND, false,
                        poseStack, context.consumers(), 0xF000F0,
                        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, model);
            } catch (Throwable ignored) {
                // 渲染线程抛异常会把客户端整个崩掉，这里宁可这一帧不画球。
            }
            poseStack.popPose();
        }
    }

    /** 与服务端 {@code BallThrow.parabola} 同一条曲线。 */
    private static Vec3 parabola(Vec3 from, Vec3 to, double t) {
        Vec3 flat = from.lerp(to, t);
        double peak = 0.85D + from.distanceTo(to) * 0.10D;
        double lift = 4.0D * peak * t * (1.0D - t);
        return flat.add(0.0D, lift, 0.0D);
    }
}
