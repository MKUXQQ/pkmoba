package com.pkmoba.client;

import com.pkmoba.network.EffectPayload;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Status badges drawn in the HUD layer over marked living entities.
 *
 * <p>Two reasons this lives in the HUD and not in world space:
 * <ul>
 *   <li>the world-space overlay passes in this mod end up batched into a buffer that has already
 *       been submitted, so quads written there never appear on screen;</li>
 *   <li>a HUD badge is never clipped by the (sometimes huge) Pokemon model and never fights depth,
 *       which is exactly what the user asked for: a badge visible above any victim's head.</li>
 * </ul>
 *
 * <p>State arrives from {@link com.pkmoba.combat.StatusBadges} as an {@link EffectPayload} with the
 * id {@code pkmoba:badge/<kind>/<count>/<ticks>} and the target entity id packed into {@code x}.
 * Kinds: {@code mark} (Blaziken Flame Mark), {@code pip} (hypnosis marks), {@code sleep}, {@code clear}.
 */
public final class StatusBadges {
    private static final ResourceLocation FLAME = ResourceLocation.fromNamespaceAndPath("pkmoba",
            "textures/effect/blaziken_mark.png");
    private static final ResourceLocation SLEEP = ResourceLocation.fromNamespaceAndPath("pkmoba",
            "textures/effect/gengar_hypno_status.png");
    private static final ResourceLocation PIP = ResourceLocation.fromNamespaceAndPath("pkmoba",
            "textures/effect/gengar_hypno_pip.png");

    /** One badge: kind, pip count, and the client tick it disappears on. */
    private record Badge(String kind, int count, long until) {
    }

    private static final Map<Integer, Badge> BADGES = new HashMap<>();

    private StatusBadges() {
    }

    public static void init() {
        HudRenderCallback.EVENT.register(StatusBadges::render);
    }

    /** Called from the shared effect receiver; true when the payload was a badge update. */
    public static boolean accept(EffectPayload payload) {
        String id = payload.effectId();
        if (id == null || !id.startsWith(com.pkmoba.combat.StatusBadges.PREFIX)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return true;
        String[] parts = id.substring(com.pkmoba.combat.StatusBadges.PREFIX.length()).split("/");
        if (parts.length < 3) return true;
        String kind = parts[0];
        int count;
        int ticks;
        try {
            count = Integer.parseInt(parts[1]);
            ticks = Integer.parseInt(parts[2]);
        } catch (NumberFormatException ignored) {
            return true;
        }
        int entityId = (int) payload.x();
        if (kind.equals("clear") || ticks <= 0) {
            BADGES.remove(entityId);
        } else {
            BADGES.put(entityId, new Badge(kind, count, mc.level.getGameTime() + ticks));
        }
        return true;
    }

    private static void render(GuiGraphics graphics, net.minecraft.client.DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui || BADGES.isEmpty()) return;
        long now = mc.level.getGameTime();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        int fov = mc.options.fov().get();
        double tanHalf = Math.tan(Math.toRadians(fov) / 2.0);
        double aspect = (double) width / Math.max(1, height);
        Quaternionf cameraRotation = mc.gameRenderer.getMainCamera().rotation();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        Quaternionf inverse = new Quaternionf(cameraRotation).conjugate();
        Iterator<Map.Entry<Integer, Badge>> it = BADGES.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Badge badge = entry.getValue();
            if (now > badge.until()) {
                it.remove();
                continue;
            }
            Entity entity = mc.level.getEntity(entry.getKey());
            if (entity == null || !entity.isAlive()) continue;
            Vec3 head = entity.position().add(0, entity.getBbHeight() + .55, 0);
            Vec3 relative = head.subtract(camera);
            Vector3f view = new Vector3f((float) relative.x, (float) relative.y, (float) relative.z);
            view.rotate(inverse);
            if (view.z > -.25F) continue; // behind or right on the camera plane
            double depth = -view.z;
            double ndcX = (view.x / depth) / (tanHalf * aspect);
            double ndcY = (view.y / depth) / tanHalf;
            double sx = (ndcX * .5 + .5) * width;
            double sy = (.5 - ndcY * .5) * height;
            if (sx < -40 || sx > width + 40 || sy < -40 || sy > height + 40) continue;
            int size = (int) Math.max(10, Math.min(30, 26 * 9.0 / Math.max(3.0, depth)));
            if (badge.kind().equals("sleep")) {
                size += 6;
                draw(graphics, SLEEP, sx, sy, size);
            } else if (badge.kind().equals("mark")) {
                draw(graphics, FLAME, sx, sy, size);
            } else if (badge.kind().equals("pip")) {
                int count = Math.max(1, Math.min(3, badge.count()));
                int step = size * 3 / 4;
                double start = sx - (count - 1) * step * .5;
                for (int i = 0; i < count; i++) {
                    draw(graphics, PIP, start + i * step, sy, Math.max(8, size * 3 / 4));
                }
            }
        }
    }

    private static void draw(GuiGraphics graphics, ResourceLocation texture, double centerX,
                             double centerY, int size) {
        int x = (int) Math.round(centerX - size * .5);
        int y = (int) Math.round(centerY - size * .5);
        graphics.blit(texture, x, y, 0, 0, size, size, 64, 64);
    }
}
