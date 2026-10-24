package com.pkmoba.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Compact cream moveset panel for the currently controlled supported Pokemon. */
public final class SkillHud {
    private static final int PANEL_W = 306;
    private static final int PANEL_H = 98;
    /** 左侧队伍模块占用的宽度，右侧技能内容整体右移这么多。 */
    private static final int TEAM_W = 52;
    private static final int CONTENT_X = 57;
    private static final int MARGIN = 8;
    /** 整体缩放：用户反馈面板偏大，整体缩到 86%。 */
    private static final float SCALE = 0.86F;    private static final int DRAW_W = Math.round(PANEL_W * SCALE);
    private static final int DRAW_H = Math.round(PANEL_H * SCALE);
    /** "下一只"预览铭牌的高度。 */
    private static final int NEXT_H = 22;
    private static final int PANEL = 0xFFF4E7C5;
    private static final int BORDER = 0xFF765638;
    private static final int TEXT = 0xFF493522;
    private static final int MUTED = 0xFF8C7A60;
    private static final int WATER_PANEL = 0xFFD9EFF2;
    private static final int WATER_BORDER = 0xFF245875;
    private static final int WATER_TEXT = 0xFF173D55;
    private static final int WATER_MUTED = 0xFF6E8B9A;

    private SkillHud() { }

    public static void init() {
        HudRenderCallback.EVENT.register(SkillHud::render);
    }

    public static boolean isMouseOver() {
        Minecraft mc = Minecraft.getInstance();
        if (!isVisible(mc)) return false;
        double[] ndc = GodView.cursorNdc(mc);
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        double x = (ndc[0] + 1) * width / 2;
        double y = (1 - ndc[1]) * height / 2;
        int left = (width - DRAW_W) / 2;
        int top = height - DRAW_H - MARGIN - (hasNextPreview() ? NEXT_H : 0);
        return x >= left - 3 && x <= left + DRAW_W + 3 && y >= top - 3 && y <= top + DRAW_H + 3;
    }

    private static com.cobblemon.mod.common.entity.pokemon.PokemonEntity controlledPokemon(Minecraft mc) {
        if (mc.level == null || ClientState.getCharizardId() < 0 || !ClientState.isSkillStateReady()) return null;
        var entity = mc.level.getEntity(ClientState.getCharizardId());
        if (!(entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon) || !pokemon.isAlive()) return null;
        String species = pokemon.getPokemon().getSpecies().getResourceIdentifier().toString();
        return species.equals("cobblemon:charizard") || species.equals("cobblemon:blastoise") ? pokemon : null;
    }

    /**
     * 当前 HUD 应展示的物种：已召唤用实体真实物种，未召唤用服务端下发的"已选宝可梦"，
     * 这样玩家选完宝可梦即便还没按 X 召唤，界面上也能看到自己选了什么。
     */
    private static String hudSpecies(Minecraft mc) {
        var pokemon = controlledPokemon(mc);
        if (pokemon != null) {
            return pokemon.getPokemon().getSpecies().getResourceIdentifier().toString();
        }
        String active = ClientState.getTeamActive();
        return active == null ? "" : active;
    }

    private static boolean isVisible(Minecraft mc) {
        return mc.player != null && GodView.isActive() && !hudSpecies(mc).isEmpty();
    }

    private static void render(GuiGraphics ctx, DeltaTracker tick) {
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        if (GodView.isActive() && CameraSettings.isPreviewVisible()) drawPreview(ctx, sw, sh);
        boolean ownPanel = isVisible(mc);
        // 自己宝可梦倒下后，自己的技能面板不画，但对手血条仍要显示（对局还没结束）
        if (!ownPanel) {
            if (GodView.isActive() && ClientState.isOpponentActive()) {
                drawOpponent(ctx, mc);
            }
            return;
        }

        drawOpponent(ctx, mc);
        // 技能栏正下方的"下一只"预览：有预览时整个技能栏上移，给它留出位置
        boolean nextPreview = hasNextPreview();
        int previewH = nextPreview ? NEXT_H : 0;
        int x = (sw - DRAW_W) / 2;
        int y = sh - DRAW_H - MARGIN - previewH;
        ctx.pose().pushPose();
        ctx.pose().translate(x, y, 0);
        ctx.pose().scale(SCALE, SCALE, 1);
        drawPanel(ctx, mc, 0, 0);
        ctx.pose().popPose();
        if (nextPreview) {
            drawNextPreview(ctx, mc, x, sh - MARGIN - NEXT_H);
        }
        drawHoveredTooltip(ctx, mc, x, y);
    }

    /** 是否展示"下一只"预览：队伍里还有别的可用宝可梦。 */
    private static boolean hasNextPreview() {
        String next = ClientState.getTeamNext();
        return next != null && !next.isBlank();
    }

    /**
     * "下一只"预览铭牌（技能栏正下方、水平居中）。
     *
     * <pre>
     * [头像] 水箭龟  下一只 2/6
     * </pre>
     *
     * 物种来自服务端 {@code TeamStatePayload.next}，与按 C 时实际会切换到的目标
     * 共用 {@link com.pkmoba.pokemon.TeamService#switchTarget} 的同一套选择逻辑。
     */
    private static void drawNextPreview(GuiGraphics ctx, Minecraft mc, int panelX, int plateY) {
        String next = ClientState.getTeamNext();
        if (next == null || next.isBlank()) return;
        net.minecraft.resources.ResourceLocation speciesRl = net.minecraft.resources.ResourceLocation.tryParse(
                next.contains(":") ? next : "cobblemon:" + next);
        String name = com.pkmoba.pokemon.SpeciesRegistry.displayName(speciesRl);
        if (name == null || name.isEmpty()) name = next;

        String tag = "下一只";
        java.util.List<String> team = ClientState.getTeam();
        int index = team.indexOf(next);
        String order = index >= 0 ? (index + 1) + "/" + team.size() : "";
        int textW = mc.font.width(name) + mc.font.width(tag) + mc.font.width(order) + 62;
        int w = Math.max(128, Math.min(210, textW));
        int h = NEXT_H;
        int x = panelX + (DRAW_W - w) / 2;

        float pulse = SwitchFx.previewPulse();
        int border = pulse > 0.02F ? 0xFFE4C77D : BORDER;
        ctx.fill(x - 1, plateY - 1, x + w + 1, plateY + h + 1, border);
        ctx.fill(x, plateY, x + w, plateY + h, PANEL);
        ctx.fill(x + 1, plateY + 1, x + w - 1, plateY + 2, 0xFFFFF8E8);
        if (pulse > 0.02F) {
            // 按下 C 的瞬间给一道内描边呼吸，提示"切换已开始"
            int glow = ((int) (pulse * 120.0F) & 0xFF) << 24 | 0x00E4C77D;
            ctx.fill(x, plateY, x + w, plateY + h, glow);
        }

        ResourceLocation portrait = portraitOf(next);
        int ps = 16;
        int px = x + 4;
        int py = plateY + (h - ps) / 2;
        if (portrait != null) {
            drawPortrait(ctx, portrait, px, py, ps, SwitchFx.entranceAlpha());
        } else {
            ctx.fill(px, py, px + ps, py + ps, 0xFFB5A88F);
        }
        ctx.fill(px - 1, py - 1, px + ps + 1, py, BORDER);
        ctx.fill(px - 1, py + ps, px + ps + 1, py + ps + 1, BORDER);
        ctx.fill(px - 1, py, px, py + ps, BORDER);
        ctx.fill(px + ps, py, px + ps + 1, py + ps, BORDER);

        int textY = plateY + (h - 8) / 2;
        ctx.drawString(mc.font, name, px + ps + 5, textY, TEXT, false);
        int tagX = x + w - 4 - mc.font.width(tag) - (order.isEmpty() ? 0 : mc.font.width(order) + 4);
        ctx.drawString(mc.font, tag, tagX, textY, MUTED, false);
        if (!order.isEmpty()) {
            ctx.drawString(mc.font, order, x + w - 4 - mc.font.width(order), textY, MUTED, false);
        }
    }

    /**
     * 对局对手面板（用户指定：贴左上角、放大玩家头）。
     * <pre>
     * [玩家头]  玩家名
     * [宝可梦头像] ████████░░  72/100  水箭龟
     * </pre>
     * 数值全部来自服务端下发的真实 getHealth()/getMaxHealth()，客户端不推算。
     */
    private static void drawOpponent(GuiGraphics ctx, Minecraft mc) {
        if (!ClientState.isOpponentActive()) return;
        // 尺寸按"两行 + 稳定基线"重排：第一行头像/名字，第二行宝可梦头像/血条/数值/名称，
        // 行与行之间留 4px 空隙，任何一行文字变长都不会压到另一行。
        int w = 208;
        int h = 58;
        int x = 8;
        int y = 8;
        float maxHp = Math.max(1.0F, ClientState.getOpponentMaxHp());
        float ratio = Math.max(0.0F, Math.min(1.0F, ClientState.getOpponentHp() / maxHp));

        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        ctx.fill(x, y, x + w, y + h, PANEL);
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFFFFF8E8);

        // 第一行：玩家头 + 玩家名
        java.util.UUID ownerId = ClientState.getOpponentOwnerId();
        ResourceLocation skin = resolveSkin(mc, ownerId);
        int headSize = 18;
        int headX = x + 6;
        int headY = y + 6;
        if (skin != null) {
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(ctx, skin, headX, headY, headSize);
        } else {
            ctx.fill(headX, headY, headX + headSize, headY + headSize, 0xFF8C7A60);
        }
        ctx.fill(headX - 1, headY - 1, headX + headSize + 1, headY, BORDER);
        ctx.fill(headX - 1, headY + headSize, headX + headSize + 1, headY + headSize + 1, BORDER);
        ctx.fill(headX - 1, headY, headX, headY + headSize, BORDER);
        ctx.fill(headX + headSize, headY, headX + headSize + 1, headY + headSize, BORDER);

        String ownerName = ClientState.getOpponentOwnerName();
        if (ownerName == null || ownerName.isEmpty()) ownerName = "对手";
        ctx.drawString(mc.font, mc.font.plainSubstrByWidth(ownerName, w - headSize - 20),
                headX + headSize + 7, headY + 5, TEXT, false);

        // 第二行：宝可梦头像 + 血条 + 数值 + 宝可梦名称
        ResourceLocation portrait = portraitOf(ClientState.getOpponentSpecies());
        int row2 = y + 30;
        int portraitSize = 22;
        if (portrait != null) {
            drawPortrait(ctx, portrait, headX, row2, portraitSize, 1.0F);
        } else {
            ctx.fill(headX, row2, headX + portraitSize, row2 + portraitSize, 0xFFB5A88F);
        }
        ctx.fill(headX - 1, row2 - 1, headX + portraitSize + 1, row2, BORDER);
        ctx.fill(headX - 1, row2 + portraitSize, headX + portraitSize + 1, row2 + portraitSize + 1, BORDER);
        ctx.fill(headX - 1, row2, headX, row2 + portraitSize, BORDER);
        ctx.fill(headX + portraitSize, row2, headX + portraitSize + 1, row2 + portraitSize, BORDER);

        int barX = headX + portraitSize + 8;
        int barY = row2 + 4;
        int barW = 116;
        int barH = 8;
        ctx.fill(barX, barY, barX + barW, barY + barH, 0xFFB5A88F);
        ctx.fill(barX, barY, barX + (int) (barW * ratio), barY + barH,
                ratio < 0.3F ? 0xFFC94F3D : 0xFFB5483F);
        ctx.fill(barX, barY, barX + barW, barY + 1, 0x55FFFFFF);

        String hpText = (int) Math.ceil(ClientState.getOpponentHp()) + "/" + (int) maxHp;
        // 血量数字固定在面板右侧，永远不压住血条
        ctx.drawString(mc.font, hpText, x + w - 6 - mc.font.width(hpText), row2 + 2, TEXT, false);

        String pokemonName = ClientState.getOpponentPokemonName();
        if (pokemonName == null || pokemonName.isEmpty()) pokemonName = "宝可梦";
        ctx.drawString(mc.font, mc.font.plainSubstrByWidth(pokemonName, barW),
                barX, row2 + 14, MUTED, false);
    }

    /** 对手玩家皮肤：优先真实皮肤，失败回退默认皮肤，再失败返回 null（画灰块）。 */
    private static ResourceLocation resolveSkin(Minecraft mc, java.util.UUID ownerId) {
        if (ownerId == null) return null;
        try {
            if (mc.getConnection() != null) {
                var info = mc.getConnection().getPlayerInfo(ownerId);
                if (info != null) return info.getSkin().texture();
            }
            return net.minecraft.client.resources.DefaultPlayerSkin.get(ownerId).texture();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ResourceLocation portraitOf(String species) {
        if (species == null || species.isBlank()) return null;
        var entry = com.pkmoba.pokemon.SpeciesRegistry.byId(
                ResourceLocation.tryParse(species.contains(":") ? species : "cobblemon:" + species));
        if (entry == null) return null;
        return ResourceLocation.fromNamespaceAndPath("pkmoba", entry.portrait());
    }

    private static void drawPanel(GuiGraphics ctx, Minecraft mc, int x, int y) {
        var pokemon = controlledPokemon(mc);
        String speciesId = hudSpecies(mc);
        boolean summoned = pokemon != null;
        boolean water = com.pkmoba.combat.Skill.isBlastoise(speciesId);
        int panelColor = water ? WATER_PANEL : PANEL;
        int borderColor = water ? WATER_BORDER : BORDER;
        int textColor = water ? WATER_TEXT : TEXT;
        int mutedColor = water ? WATER_MUTED : MUTED;
        net.minecraft.resources.ResourceLocation speciesRl = net.minecraft.resources.ResourceLocation.tryParse(
                speciesId.contains(":") ? speciesId : "cobblemon:" + speciesId);
        String displayName = summoned
                ? pokemon.getDisplayName().getString()
                : com.pkmoba.pokemon.SpeciesRegistry.displayName(speciesRl);
        ctx.fill(x - 1, y + 2, x + PANEL_W + 1, y + PANEL_H + 2, 0x66000000);
        ctx.fill(x + 2, y - 2, x + PANEL_W - 2, y + PANEL_H + 2, borderColor);
        ctx.fill(x - 2, y + 2, x + PANEL_W + 2, y + PANEL_H - 2, borderColor);
        ctx.fill(x, y, x + PANEL_W, y + PANEL_H, panelColor);
        ctx.fill(x + 3, y + 3, x + PANEL_W - 3, y + 4, water ? 0xFFE9F8FA : 0xFFFFF8E8);

        // 标题做成挂在面板上沿的小铭牌（原先是浮在面板外的裸文字，与米黄 UI 不搭）
        drawNamePlate(ctx, mc, displayName, x + (CONTENT_X + PANEL_W - 9) / 2, y,
                panelColor, borderColor, textColor);

        drawTeamModule(ctx, mc, x, y, speciesId, borderColor, textColor, mutedColor, water);

        float maxHp = Math.max(1.0F, ClientState.getMaxHp());
        float hp = Math.max(0.0F, Math.min(1.0F, ClientState.getHp() / maxHp));
        int barLeft = x + CONTENT_X;
        int barRight = x + PANEL_W - 9;
        ctx.fill(barLeft, y + 7, barRight, y + 12, water ? 0xFF87A8B5 : 0xFFB5A88F);
        ctx.fill(barLeft, y + 7, barLeft + (int) ((barRight - barLeft) * hp), y + 12,
                hp < 0.3F ? 0xFFC94F3D : 0xFF6E9A52);
        String hpText = (int) Math.ceil(ClientState.getHp()) + "/" + (int) maxHp;
        ctx.drawCenteredString(mc.font, hpText, (barLeft + barRight) / 2, y + 4, textColor);

        int[] cooldowns = {ClientState.getCdQ(), ClientState.getCdW(), ClientState.getCdE(), ClientState.getCdR()};
        for (int i = 0; i < SkillPresentation.COUNT; i++) {
            int cellX = barLeft + i * 59;
            int iconX = cellX + 12;
            int iconY = y + 16;
            int cost = com.pkmoba.combat.Skill.cost(pokemon, i);
            boolean affordable = summoned && ClientState.getPoints() >= cost;
            ctx.fill(cellX, y + 15, cellX + 54, y + 78, water ? 0x334A829B : 0x33765A3C);
            ctx.fill(cellX + 1, y + 16, cellX + 53, y + 17, 0x55FFFFFF);
            ResourceLocation icon = SkillPresentation.icon(speciesId, i);
            drawIcon(ctx, icon, iconX, iconY, 30, affordable ? 1.0F : 0.48F);

            if (cooldowns[i] > 0) {
                int mask = Math.max(1, Math.min(30, (int) (30 * cooldowns[i] / 40.0F)));
                ctx.fill(iconX, iconY + 30 - mask, iconX + 30, iconY + 30, water ? 0xAA174666 : 0xAA34291E);
                String remaining = String.format(java.util.Locale.ROOT, "%.1f", cooldowns[i] / 20.0F);
                ctx.drawCenteredString(mc.font, remaining, iconX + 15, iconY + 10, 0xFFFFFFFF);
            } else if (!affordable) {
                ctx.fill(iconX, iconY, iconX + 30, iconY + 30, 0x66000000);
            }

            ctx.fill(cellX + 14, y + 48, cellX + 40, y + 60, borderColor);
            ctx.drawCenteredString(mc.font, SkillKeys.bindingLabel(i), cellX + 27, y + 49, water ? 0xFFE4F5F8 : 0xFFFFF3D4);
            String skillName = Component.translatable(
                    com.pkmoba.combat.Skill.translationKey(speciesId, i)).getString();
            ctx.drawCenteredString(mc.font, skillName, cellX + 27, y + 64, affordable ? textColor : mutedColor);
            if (i == 2 && ClientState.getChannel() == 3) {
                ctx.fill(iconX + 1, iconY + 27, iconX + 29, iconY + 30, water ? 0xFF62C8E2 : 0xFF80AE5A);
            }
            if (i == 3 && ClientState.getChannel() == 4) {
                ctx.fill(iconX + 1, iconY + 27, iconX + 1 + (int) (28 * Math.max(0, Math.min(1, ClientState.getCharge()))), iconY + 30, water ? 0xFF65CBE5 : 0xFFFFC35A);
            }
        }

        int points = Math.max(0, Math.min(10, (int) ClientState.getPoints()));
        String pointsLabel = points + "/10";
        int beadStep = 12;
        int beadWidth = 9;
        int beadGroupWidth = 10 * beadStep - (beadStep - beadWidth);
        int resourceWidth = beadGroupWidth + 5 + mc.font.width(pointsLabel);
        int resourceX = barLeft + ((barRight - barLeft) - resourceWidth) / 2;
        int beadY = y + 84;
        int beadOutline = water ? 0xFF173D55 : 0xFF7B5426;
        int beadFill = water ? 0xFF56B8D8 : 0xFFE0AD45;
        int beadHighlight = water ? 0xFFBDEFFF : 0xFFFFD977;
        int beadGloss = water ? 0xFFE7FAFF : 0xFFFFF0B2;
        int beadBottom = water ? 0xFF287A9B : 0xFFB77A2F;
        for (int i = 0; i < 10; i++) {
            int bx = resourceX + i * beadStep;
            if (i < points) {
                ctx.fill(bx + 1, beadY + 1, bx + beadWidth - 1, beadY + 6, beadOutline);
                ctx.fill(bx + 2, beadY, bx + beadWidth - 2, beadY + 6, beadFill);
                ctx.fill(bx + 3, beadY + 1, bx + beadWidth - 3, beadY + 4, beadHighlight);
                ctx.fill(bx + 3, beadY + 1, bx + 5, beadY + 2, beadGloss);
                ctx.fill(bx + 2, beadY + 5, bx + beadWidth - 2, beadY + 6, beadBottom);
            } else {
                ctx.fill(bx + 1, beadY + 1, bx + beadWidth - 1, beadY + 6, 0xFF9B8061);
                ctx.fill(bx + 2, beadY + 1, bx + beadWidth - 2, beadY + 5, 0xFFE5D6B8);
                ctx.fill(bx + 3, beadY + 2, bx + beadWidth - 3, beadY + 4, 0xFFD3C09D);
                ctx.fill(bx + 3, beadY + 2, bx + 5, beadY + 3, 0xFFF1E5CC);
            }
        }
        ctx.drawString(mc.font, pointsLabel, resourceX + beadGroupWidth + 5, y + 82, textColor, false);
    }

    /**
     * 挂在面板上沿中间的宝可梦名字铭牌：深棕描边 + 米黄底 + 内高光，和面板同一套像素风。
     */
    private static void drawNamePlate(GuiGraphics ctx, Minecraft mc, String text, int centerX, int panelTop,
                                      int panelColor, int borderColor, int textColor) {
        int w = mc.font.width(text) + 12;
        int x = centerX - w / 2;
        int y = panelTop - 11;
        ctx.fill(x - 1, y - 1, x + w + 1, y + 12, borderColor);
        ctx.fill(x, y, x + w, y + 11, panelColor);
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFFFFF8E8);
        ctx.drawString(mc.font, text, x + 6, y + 2, textColor, false);
    }

    /**
     * 左侧队伍模块：40×40 的头像 + 队伍圆点 + "C 换" 提示（换人倒计时时显示剩余秒数）。
     * 队伍只有一只时隐藏圆点行，避免空行干扰。
     *
     * <p>头像与"当前"标记一律按面板正在展示的物种（{@code speciesId}）来画，
     * 不能再用 ClientState.getTeamActive() —— 否则换人瞬间会出现"头像是水箭龟、技能是喷火龙"的错乱。
     */
    private static void drawTeamModule(GuiGraphics ctx, Minecraft mc, int x, int y,
                                       String speciesId, int borderColor, int textColor,
                                       int mutedColor, boolean water) {
        // 分隔线
        ctx.fill(x + 50, y + 6, x + 51, y + PANEL_H - 6, water ? 0x55245875 : 0x55765638);

        java.util.List<String> team = ClientState.getTeam();
        String active = speciesId == null || speciesId.isBlank()
                ? ClientState.getTeamActive()
                : speciesId;
        if (active == null || active.isBlank()) {
            active = team.isEmpty() ? "" : team.get(0);
        }
        boolean summoned = ClientState.isTeamSummoned();

        ResourceLocation portrait = portraitOf(active);
        int px = x + 5;
        int py = y + 14;
        if (portrait != null) {
            // 登场瞬间做一次轻微淡入，让"新宝可梦上场"有过渡而不是硬切
            float base = summoned ? 1.0F : 0.55F;
            float alpha = summoned ? base * SwitchFx.entranceAlpha() : base;
            drawPortrait(ctx, portrait, px, py, 40, alpha);
        } else {
            ctx.fill(px, py, px + 40, py + 40, water ? 0xFF87A8B5 : 0xFFB5A88F);
        }
        if (!summoned) {
            // 未召唤：压暗 + 画一个 X，提示"按 X 召唤"
            ctx.fill(px, py, px + 40, py + 40, 0x66000000);
            int c = 0xFFE8E0CE;
            for (int i = 0; i < 4; i++) {
                ctx.fill(px + 8 + i, py + 8 + i, px + 9 + i, py + 9 + i, c);
                ctx.fill(px + 47 - i, py + 8 + i, px + 48 - i, py + 9 + i, c);
                ctx.fill(px + 8 + i, py + 47 - i, px + 9 + i, py + 48 - i, c);
                ctx.fill(px + 47 - i, py + 47 - i, px + 48 - i, py + 48 - i, c);
            }
        }

        // 队伍圆点（最多 6 个，只有 1 只时不画）
        if (team.size() > 1) {
            int dotSize = 6;
            int gap = 2;
            int total = team.size() * (dotSize + gap) - gap;
            int dx = x + 5 + (40 - total) / 2;
            for (int i = 0; i < team.size() && i < 6; i++) {
                String species = team.get(i);
                boolean current = species.equals(active);
                boolean fainted = ClientState.isTeamFainted(species);
                int ox = dx + i * (dotSize + gap);
                int oy = y + 60;
                ctx.fill(ox, oy, ox + dotSize, oy + dotSize, borderColor);
                if (current) {
                    ctx.fill(ox - 1, oy - 1, ox + dotSize + 1, oy - 1, 0xFFE4C77D);
                    ctx.fill(ox - 1, oy + dotSize, ox + dotSize + 1, oy + dotSize + 1, 0xFFE4C77D);
                    ctx.fill(ox - 1, oy - 1, ox - 1, oy + dotSize + 1, 0xFFE4C77D);
                    ctx.fill(ox + dotSize, oy - 1, ox + dotSize + 1, oy + dotSize + 1, 0xFFE4C77D);
                }
                if (fainted) {
                    ctx.fill(ox + 2, oy + 2, ox + dotSize - 2, oy + dotSize - 2,
                            water ? 0xFF173D55 : 0xFF7B5426);
                } else {
                    ctx.fill(ox + 1, oy + 1, ox + dotSize - 1, oy + dotSize - 1,
                            current ? 0xFFE4C77D : 0xFFD8C7A6);
                }
            }
        }

        // 换人倒计时 / 按键提示（在场时 C 是收回，不在场时 C 是放出下一只）
        int pending = ClientState.getTeamPendingTicks();
        String hint;
        int hintColor;
        if (pending > 0) {
            hint = (pending + 19) / 20 + "s";
            hintColor = 0xFFC94F3D;
        } else {
            hint = summoned ? "C 收回" : "C 换";
            hintColor = mutedColor;
        }
        ctx.drawCenteredString(mc.font, hint, x + 25, y + 70, hintColor);
    }

    /** 宝可梦头像是 64×64 的网络抓取图，单独走一套缩放，别和 32×32 技能图标混用。 */
    private static void drawPortrait(GuiGraphics graphics, ResourceLocation texture,
                                     int x, int y, int size, float alpha) {
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(size / 64.0F, size / 64.0F, 1);
        graphics.blit(texture, 0, 0, 0, 0, 64, 64, 64, 64);
        graphics.pose().popPose();
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawIcon(GuiGraphics graphics, ResourceLocation texture, int x, int y, int size, float alpha) {
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(size / 32.0F, size / 32.0F, 1);
        graphics.blit(texture, 0, 0, 0, 0, 32, 32, 32, 32);
        graphics.pose().popPose();
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawHoveredTooltip(GuiGraphics ctx, Minecraft mc, int x, int y) {
        double[] cursor = GodView.cursorNdc(mc);
        double mouseX = (cursor[0] + 1) * mc.getWindow().getGuiScaledWidth() / 2.0;
        double mouseY = (1 - cursor[1]) * mc.getWindow().getGuiScaledHeight() / 2.0;
        for (int i = 0; i < SkillPresentation.COUNT; i++) {
            if (SkillPresentation.isSkillIconHovered(mc, i, x, y, SCALE, mouseX, mouseY)) {
                String speciesId = hudSpecies(mc);
                if (speciesId.isEmpty()) return;
                String skillName = Component.translatable(
                        com.pkmoba.combat.Skill.translationKey(speciesId, i)).getString();
                int cost = com.pkmoba.combat.Skill.byIndex(i).cost;
                ctx.renderTooltip(mc.font, Component.literal(skillName + " · 消耗 " + cost + " 豆"),
                        (int) mouseX, (int) mouseY);
                return;
            }
        }
    }

    private static void drawPreview(GuiGraphics ctx, int width, int height) {
        int color = 0x334A9DCC;
        ctx.fill(0, 0, (int) (width * CameraSettings.left / 100), height, color);
        ctx.fill(width - (int) (width * CameraSettings.right / 100), 0, width, height, color);
        ctx.fill(0, 0, width, (int) (height * CameraSettings.top / 100), color);
        ctx.fill(0, height - (int) (height * CameraSettings.bottom / 100), width, height, color);
        int left = (int) (width * CameraSettings.left / 100);
        int right = width - (int) (width * CameraSettings.right / 100);
        int top = (int) (height * CameraSettings.top / 100);
        int bottom = height - (int) (height * CameraSettings.bottom / 100);
        int line = 0xDDFFD077;
        ctx.fill(left, top, right, top + 1, line);
        ctx.fill(left, bottom - 1, right, bottom, line);
        ctx.fill(left, top, left + 1, bottom, line);
        ctx.fill(right - 1, top, right, bottom, line);
    }
}
