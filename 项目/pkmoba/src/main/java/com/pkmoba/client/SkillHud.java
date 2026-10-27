package com.pkmoba.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Compact cream moveset panel for the currently controlled supported Pokemon. */
public final class SkillHud {
    private static final int PANEL_W = 4 * SkillPresentation.CELL_W + 3 * SkillPresentation.CELL_GAP + 12;
    private static final int PANEL_H = 98;
    /** 技能面板独立居中；队伍列表由 PartyHud 在左侧单独绘制。 */
    private static final int TEAM_W = 0;
    private static final int CONTENT_X = 0;
    private static final int MARGIN = 8;
    private static final int NEXT_H = 22; // retained for binary/source compatibility; preview is no longer rendered
    /**
     * 整体缩放：用户反馈面板偏大，缩到 72%。
     *
     * <p>注意：这是非整数的 pose 缩放，面板内的文字必须用 {@link #drawCenteredText} 这类
     * <b>无阴影</b>方式绘制 —— 带阴影的 {@code drawCenteredString} 会把 1px 阴影一起缩放到
     * 0.72px 再重采样，字形和阴影糊成一团双影（用户反馈的"血量数字重影叠字"）。
     */
    private static final float SCALE = 0.86F;
    private static final int PANEL = 0xFFF4E7C5;
    private static final int BORDER = 0xFF765638;
    private static final int TEXT = 0xFF493522;
    private static final int MUTED = 0xFF8C7A60;
    private static final int WATER_PANEL = 0xFFD9EFF2;
    private static final int WATER_BORDER = 0xFF245875;
    private static final int WATER_TEXT = 0xFF173D55;
    private static final int WATER_MUTED = 0xFF6E8B9A;

    // ---- 0.38.10 技能栏统一化：按下反馈、冷却读秒、施法灰化 ----

    /** 按下反馈动画时长（ms）：按下瞬间缩到 {@link #PRESS_SCALE}，之后回弹到 1.0。 */
    private static final long PRESS_ANIM_MS = 140L;
    /** 按下瞬间的缩放比例（一次按键只播一次）。 */
    /** 0.39.5（用户 m03603）："W 技能不能有缩放" —— 按下不再缩图标，保持 1.0。 */
    private static final float PRESS_SCALE = 1.0F;
    /** 四个技能键的翻译键，与 {@link SkillKeys} 里注册的 KeyMapping 一一对应。 */
    private static final String[] KEY_NAMES = {
            "key.pkmoba.skill_q", "key.pkmoba.skill_w", "key.pkmoba.skill_e", "key.pkmoba.skill_r"
    };
    /** 反查到的按键绑定（惰性）；查不到时该槽不做按下反馈，不影响施法。 */
    private static final KeyMapping[] KEY_BINDINGS = new KeyMapping[SkillPresentation.COUNT];
    /** 上一帧按键是否按下，用来取"按下沿"，保证一次按键只播一次动画。 */
    private static final boolean[] KEY_WAS_DOWN = new boolean[SkillPresentation.COUNT];
    /** 按下动画起始时间（ms），0 表示当前没有动画。 */
    private static final long[] PRESS_START_MS = new long[SkillPresentation.COUNT];
    /** 上一帧引导 channel，用于把 0→N 的施法开始兜底当作一次按下反馈。 */
    private static int lastFeedbackChannel;
    /** 反馈状态对应的物种/实体；切换宝可梦时所有一次性状态归零。 */
    private static String feedbackSpecies = "";
    private static int feedbackEntityId = -1;
    /**
     * 冷却剩余秒数文案预生成（tick→"x.x"）。
     *
     * <p>最长冷却 140t，这里取 0..200，避免每帧、每格再做一次 {@code String.format} 分配。
     */
    private static final String[] REMAIN_TEXT = new String[201];
    /** 技能名/图标按物种缓存，避免每帧重建 4 个 Component/ResourceLocation。 */
    private static String cachedSpecies = "";
    private static String[] cachedSkillNames;
    private static ResourceLocation[] cachedIcons;
    /** 按键提示文字缓存（绑定键值变化时自动刷新，支持游戏内改键）。 */
    private static final String[] BINDING_LABELS = new String[SkillPresentation.COUNT];
    private static final int[] BINDING_KEY_VALUES = new int[SkillPresentation.COUNT];

    static {
        for (int i = 0; i < REMAIN_TEXT.length; i++) {
            REMAIN_TEXT[i] = String.format(java.util.Locale.ROOT, "%.1f", i / 20.0F);
        }
    }

    private SkillHud() { }

    public static void init() {
        HudRenderCallback.EVENT.register(SkillHud::render);
    }

    /** 四个技能图标最近一帧的屏幕左上角（GUI 缩放坐标），-1 表示还没画过。 */
    private static final int[] LAST_ICON_X = {-1, -1, -1, -1};
    private static final int[] LAST_ICON_Y = {-1, -1, -1, -1};

    /**
     * 鼠标正悬停在哪个技能图标上（0..3），没有则 -1。
     *
     * <p>用于"悬停技能图标 → 世界内画出该技能的极限范围"（用户要求把普攻白圈和技能指示统一）。
     */
    public static int hoveredSlot() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.getWindow() == null) {
            return -1;
        }
        double scale = mc.getWindow().getGuiScale();
        if (scale <= 0.0) {
            return -1;
        }
        int mouseX = (int) (mc.mouseHandler.xpos() / scale);
        int mouseY = (int) (mc.mouseHandler.ypos() / scale);
        int size = SkillPresentation.ICON_SIZE;
        for (int i = 0; i < 4; i++) {
            int iconX = LAST_ICON_X[i];
            int iconY = LAST_ICON_Y[i];
            if (iconX < 0 || iconY < 0) {
                continue;
            }
            if (mouseX >= iconX && mouseX < iconX + size && mouseY >= iconY && mouseY < iconY + size) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isMouseOver() {
        Minecraft mc = Minecraft.getInstance();
        if (!isVisible(mc)) return false;
        double[] ndc = GodView.cursorNdc(mc);
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        double x = (ndc[0] + 1) * width / 2;
        double y = (1 - ndc[1]) * height / 2;
        float scale = Math.min(SCALE, Math.min(1.0F, (width - 12.0F) / PANEL_W));
        int drawW = Math.round(PANEL_W * scale);
        int drawH = Math.round(PANEL_H * scale);
        int left = (width - drawW) / 2;
        int previewH = 0;
        int top = Math.max(6, height - drawH - MARGIN);
        return x >= left - 3 && x <= left + drawW + 3
                && y >= top - 3 && y <= top + drawH + 3 + previewH;
    }

    private static com.cobblemon.mod.common.entity.pokemon.PokemonEntity controlledPokemon(Minecraft mc) {
        if (mc.level == null || ClientState.getCharizardId() < 0 || !ClientState.isSkillStateReady()) return null;
        var entity = mc.level.getEntity(ClientState.getCharizardId());
        if (!(entity instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon) || !pokemon.isAlive()) return null;
        String species = pokemon.getPokemon().getSpecies().getResourceIdentifier().toString();
        return species.equals("cobblemon:charizard") || species.equals("cobblemon:blastoise")
                || species.equals("cobblemon:venusaur") || species.equals("cobblemon:pikachu") ? pokemon : null;
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
        if (mc.screen instanceof PartyScreen) return;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        if (GodView.isActive() && CameraSettings.isPreviewVisible()) drawPreview(ctx, sw, sh);
        boolean ownPanel = isVisible(mc);
        // 按下反馈 / 切换归零每帧维护一次；面板不显示时只更新按键状态，避免关掉面板后误触发
        updateFeedbackState(mc, ownPanel, ownPanel ? hudSpecies(mc) : "");
        // 自己宝可梦倒下后，自己的技能面板不画，但对手血条仍要显示（对局还没结束）
        if (!ownPanel) {
            if (GodView.isActive() && ClientState.isOpponentActive()) {
                drawOpponent(ctx, mc);
            }
            return;
        }

        drawOpponent(ctx, mc);
        float scale = Math.min(SCALE, Math.min(1.0F, (sw - 12.0F) / PANEL_W));
        int drawW = Math.round(PANEL_W * scale);
        int drawH = Math.round(PANEL_H * scale);
        int x = (sw - drawW) / 2;
        int y = Math.max(6, sh - drawH - MARGIN);
        ctx.pose().pushPose();
        ctx.pose().translate(x, y, 0);
        ctx.pose().scale(scale, scale, 1);
        drawPanel(ctx, mc, 0, 0);
        ctx.pose().popPose();
        drawHoveredTooltip(ctx, mc, x, y, scale);
    }

    /** 是否展示"下一只"预览：队伍里还有别的可用宝可梦。 */
    private static boolean hasNextPreview() {
        return false;
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
    private static void drawNextPreview(GuiGraphics ctx, Minecraft mc, int panelX, int plateY, int drawW) {
        String next = ClientState.getTeamNext();
        if (next == null || next.isBlank()) return;
        net.minecraft.resources.ResourceLocation speciesRl = net.minecraft.resources.ResourceLocation.tryParse(
                next.contains(":") ? next : "cobblemon:" + next);
        String name = com.pkmoba.pokemon.SpeciesRegistry.displayName(speciesRl);
        if (name == null || name.isEmpty()) name = next;

        String tag = "下一只";
        // 0.39.2：用户要求删掉"2/2"这种队伍序号，只保留物种名 + "下一只"。
        int textW = mc.font.width(name) + mc.font.width(tag) + 62;
        int w = Math.max(128, Math.min(210, textW));
        int h = NEXT_H;
        int x = panelX + (drawW - w) / 2;

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
        int tagX = x + w - 4 - mc.font.width(tag);
        ctx.drawString(mc.font, tag, tagX, textY, MUTED, false);
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
        boolean venusaur = com.pkmoba.combat.Skill.isVenusaur(speciesId);
        boolean electric = com.pkmoba.combat.Skill.isPikachu(speciesId);
        int panelColor = electric ? 0xFFFFEEA6 : (water ? WATER_PANEL : PANEL);
        int borderColor = electric ? 0xFFD28A0E : (water ? WATER_BORDER : (venusaur ? 0xFF3D6B3D : BORDER));
        int textColor = water ? WATER_TEXT : (venusaur ? 0xFF214D2B : TEXT);
        int mutedColor = water ? WATER_MUTED : (venusaur ? 0xFF648064 : MUTED);
        net.minecraft.resources.ResourceLocation speciesRl = net.minecraft.resources.ResourceLocation.tryParse(
                speciesId.contains(":") ? speciesId : "cobblemon:" + speciesId);
        String displayName = summoned
                ? pokemon.getDisplayName().getString()
                : com.pkmoba.pokemon.SpeciesRegistry.displayName(speciesRl);
        ctx.fill(x - 1, y + 2, x + PANEL_W + 1, y + PANEL_H + 2, 0x66000000);
        ctx.fill(x + 2, y - 2, x + PANEL_W - 2, y + PANEL_H + 2, borderColor);
        ctx.fill(x - 2, y + 2, x + PANEL_W + 2, y + PANEL_H - 2, borderColor);
        ctx.fill(x, y, x + PANEL_W, y + PANEL_H, panelColor);
        ctx.fill(x + 3, y + 3, x + PANEL_W - 3, y + 4,
                water ? 0xFFE9F8FA : (venusaur ? 0xFFE8F6DE : 0xFFFFF8E8));

        // 标题做成挂在面板上沿的小铭牌（原先是浮在面板外的裸文字，与米黄 UI 不搭）
        drawNamePlate(ctx, mc, displayName, x + PANEL_W / 2, y,
                panelColor, borderColor, textColor);

        // 0.38.3：切宠宽限期（ClientState 300ms）内沿用上一帧数值并淡出，宽限期结束仍无数据
        // 才画"同步中"；不再"立刻清零 → 下一包回填"，避免切宠时 UI 一闪一闪。
        float maxHp = Math.max(1.0F, ClientState.getMaxHp());
        float hp = Math.max(0.0F, Math.min(1.0F, ClientState.getHp() / maxHp));
        float valueAlpha = ClientState.getCombatValueAlpha();
        String hpText;
        if (ClientState.isAwaitingSync() || ClientState.getMaxHp() <= 0.0F) {
            hpText = "同步中";
        } else {
            hpText = (int) Math.ceil(ClientState.getHp()) + "/" + (int) ClientState.getMaxHp();
        }
        int barLeft = x + 6;
        int contentRight = x + PANEL_W - 6;
        // 血量数字右对齐放在血条右侧，不再压在血条上（旧版数字画在 y+4、血条占 y+7..y+12，
        // 字形下半截压在填充色上，看起来像叠了一层字）。
        int hpTextX = contentRight - mc.font.width(hpText);
        int barRight = Math.max(barLeft + 20, hpTextX - 5);
        ctx.fill(barLeft, y + 7, barRight, y + 12, water ? 0xFF87A8B5 : 0xFFB5A88F);
        ctx.fill(barLeft, y + 7, barLeft + (int) ((barRight - barLeft) * hp), y + 12,
                withAlpha(hp < 0.3F ? 0xFFC94F3D : 0xFF6E9A52, valueAlpha));
        ctx.drawString(mc.font, hpText, hpTextX, y + 4, withAlpha(textColor, valueAlpha), false);

        // 0.38.3：引导条不再写死 channel 号，改用 SkillKeys 的物种感知映射（与瞄准预览同一份）。
        // channel 4/6 有服务端下发的 charge（大字爆炎/加农水炮/喷水前摇），其余引导只画满条提示。
        int channel = ClientState.getChannel();
        int guidedSlot = SkillKeys.slotForChannel(speciesId, channel);
        boolean chargeBar = channel == 4 || channel == 6 || (electric && channel == 14);
        float charge = Math.max(0.0F, Math.min(1.0F, ClientState.getCharge()));
        boolean casting = channel != 0;
        long now = Util.getMillis();
        for (int i = 0; i < SkillPresentation.COUNT; i++) {
            int cellX = barLeft + i * SkillPresentation.CELL_STEP;
            int iconX = cellX + SkillPresentation.ICON_INSET;
            int iconY = y + SkillPresentation.ICON_Y;
            int iconSize = SkillPresentation.ICON_SIZE;
            // 0.39.4：把这一帧四个图标的屏幕矩形记下来，鼠标悬停时用来画该技能的范围指示
            LAST_ICON_X[i] = iconX;
            LAST_ICON_Y[i] = iconY;
            // 0.38 起耗点按物种不同（水箭龟 4/2/3/4），用物种 id 读取，未召唤也能显示正确。
            int cost = com.pkmoba.combat.Skill.cost(speciesId, i);
            // 切宠宽限期内沿用上一帧的豆数，先别把图标压暗（否则就是一次"闪一下"）。
            boolean affordable = (summoned || ClientState.isCombatGraceActive())
                    && ClientState.getPoints() >= cost;
            int cooldown = cooldownAt(i);
            boolean inCast = casting && guidedSlot == i;

            // 格子底 + 统一外框：四格同宽同高，图标固定 32px 原生像素居中，位置由
            // SkillPresentation 的布局常量统一决定，四格不会再出现大小/位置不一致。
            ctx.fill(cellX, y + SkillPresentation.CELL_TOP, cellX + SkillPresentation.CELL_W,
                    y + SkillPresentation.CELL_BOTTOM,
                    water ? 0x334A829B : (venusaur ? 0x334C7A45 : 0x33765A3C));
            ctx.fill(cellX + 1, y + SkillPresentation.CELL_TOP + 1,
                    cellX + SkillPresentation.CELL_W - 1, y + SkillPresentation.CELL_TOP + 2, 0x55FFFFFF);
            ctx.fill(iconX - 1, iconY - 1, iconX + iconSize + 1, iconY + iconSize + 1, borderColor);
            ctx.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0xFF1B1F22);
            drawIcon(ctx, skillIcon(speciesId, i), iconX, iconY, iconSize,
                    (electric || affordable || inCast) ? 1.0F : 0.48F, pressScale(i));

            if (inCast) {
                // 施法/前摇/引导：整张图标压成灰色（灰色盖住图标本身），不再写"施法中"文字。
                ctx.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, electric ? 0x33FFF27A : 0xB46F7A82);
            } else if (cooldown > 0) {
                // 冷却遮罩：分母与服务端同一张表（Skill.cooldownTicks），自下而上退去。
                int full = Math.max(1, com.pkmoba.combat.Skill.cooldownTicks(speciesId, i));
                int mask = Math.max(1, Math.min(iconSize, (int) (iconSize * cooldown / (float) full)));
                ctx.fill(iconX, iconY + iconSize - mask, iconX + iconSize, iconY + iconSize,
                        electric ? 0x337E5100 : (water ? 0x99174666 : 0x9934291E));
                // 剩余秒数自带深色底：压在遮罩上仍然清晰，0.1s 精度（<1s 显示 0.x），不裁切。
                String remaining = remainText(cooldown);
                int chipW = mc.font.width(remaining) + 4;
                int chipX = iconX + (iconSize - chipW) / 2;
                int chipY = iconY + iconSize - 12;
                ctx.fill(chipX, chipY, chipX + chipW, chipY + 10, 0xC0000000);
                drawCenteredText(ctx, mc, remaining, iconX + iconSize / 2, chipY + 1, 0xFFFFFFFF);
            } else if (!affordable && !electric) {
                ctx.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0x66000000);
            }

            // 引导进度条：只画在映射到的槽位（喷火龙飞翔等无槽位 channel 不会误点亮）。
            // chargeBar（加农水炮/喷水/大字爆炎）按服务端 charge 填；其余引导没有进度数据，
            // 用整条脉冲表示"引导进行中"，绝不画成冷却倒数。
            if (guidedSlot == i) {
                int barTop = iconY + iconSize - 3;
                ctx.fill(iconX + 1, barTop, iconX + iconSize - 1, barTop + 2, 0x66000000);
                if (chargeBar) {
                    int fillRight = iconX + 1 + (int) ((iconSize - 2) * charge);
                    ctx.fill(iconX + 1, barTop, fillRight, barTop + 2,
                            water ? 0xFF62C8E2 : 0xFFFFC35A);
                } else {
                    float pulse = 0.5F + 0.5F * (float) Math.sin(now / 160.0);
                    ctx.fill(iconX + 1, barTop, iconX + iconSize - 1, barTop + 2,
                            withAlpha(water ? 0xFF62C8E2 : 0xFFFFC35A, 0.55F + 0.35F * pulse));
                }
            }

            int cellCenter = cellX + SkillPresentation.CELL_W / 2;
            ctx.fill(cellCenter - 13, y + 48, cellCenter + 13, y + 60, borderColor);
            drawCenteredText(ctx, mc, bindingLabel(i), cellCenter, y + 49,
                    water ? 0xFFE4F5F8 : 0xFFFFF3D4);
            drawCenteredText(ctx, mc, skillName(speciesId, i), cellCenter, y + 64,
                    affordable ? textColor : mutedColor);
        }

        int points = Math.max(0, Math.min(10, (int) ClientState.getPoints()));
        String pointsLabel = points + "/10";
        int beadStep = 12;
        int beadWidth = 9;
        int beadGroupWidth = 10 * beadStep - (beadStep - beadWidth);
        int resourceWidth = beadGroupWidth + 5 + mc.font.width(pointsLabel);
        int resourceX = barLeft + ((contentRight - barLeft) - resourceWidth) / 2;
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
            hint = "C 队伍";
            hintColor = mutedColor;
        }
        drawCenteredText(ctx, mc, hint, x + 25, y + 70, hintColor);
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

    /** 把颜色按系数变淡（RGB 不动、只压 alpha），用于切宠宽限期的旧数值淡出。 */
    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(((color >>> 24) & 0xFF) * alpha)));
        return (a << 24) | (color & 0xFFFFFF);
    }

    // ---- 0.38.10 技能栏统一化：按下反馈 / 冷却文案 / 物种缓存 ----

    /** 取槽位冷却（tick）；用 switch 代替每帧 new int[4]。 */
    private static int cooldownAt(int index) {
        return switch (index) {
            case 0 -> ClientState.getCdQ();
            case 1 -> ClientState.getCdW();
            case 2 -> ClientState.getCdE();
            default -> ClientState.getCdR();
        };
    }

    /** 冷却剩余秒数文案（0.1s 精度，<1s 显示 0.x）；预生成表外的超长冷却再兜底格式化。 */
    private static String remainText(int ticks) {
        if (ticks > 0 && ticks < REMAIN_TEXT.length) return REMAIN_TEXT[ticks];
        return String.format(java.util.Locale.ROOT, "%.1f", ticks / 20.0F);
    }

    /**
     * 物种变化时重建图标与技能名缓存。
     *
     * <p>技能名走 {@code Component.translatable(...).getString()}、图标走
     * {@code ResourceLocation.fromNamespaceAndPath}，每帧四格各建一次太浪费；
     * 只有物种变化（含切宠/未召唤→已召唤）时才重建。
     */
    private static void ensureSpeciesCache(String speciesId) {
        if (speciesId.equals(cachedSpecies) && cachedSkillNames != null && cachedIcons != null) return;
        cachedSpecies = speciesId;
        cachedSkillNames = new String[SkillPresentation.COUNT];
        cachedIcons = new ResourceLocation[SkillPresentation.COUNT];
        for (int i = 0; i < SkillPresentation.COUNT; i++) {
            cachedSkillNames[i] = Component.translatable(
                    com.pkmoba.combat.Skill.translationKey(speciesId, i)).getString();
            cachedIcons[i] = SkillPresentation.icon(speciesId, i);
        }
    }

    private static ResourceLocation skillIcon(String speciesId, int index) {
        ensureSpeciesCache(speciesId);
        return cachedIcons[index];
    }

    private static String skillName(String speciesId, int index) {
        ensureSpeciesCache(speciesId);
        return cachedSkillNames[index];
    }

    /**
     * 按键提示文字（Q/W/E/R 或玩家改键后的名字）。
     *
     * <p>每帧 {@code getTranslatedKeyMessage().getString()} 会新建 Component/String，
     * 这里按"绑定键值"缓存，玩家改键后自动刷新，平时零分配。
     */
    private static String bindingLabel(int index) {
        KeyMapping key = keyBinding(index);
        if (key == null) return SkillKeys.bindingLabel(index);
        int value = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
                .getBoundKeyOf(key).getValue();
        if (BINDING_LABELS[index] == null || BINDING_KEY_VALUES[index] != value) {
            BINDING_LABELS[index] = key.getTranslatedKeyMessage().getString();
            BINDING_KEY_VALUES[index] = value;
        }
        return BINDING_LABELS[index];
    }

    /**
     * 反查四个技能键的 {@link KeyMapping}。
     *
     * <p>{@link SkillKeys} 里的 KeyMapping 是私有字段，HUD 不能直接拿；Fabric 会把模组按键
     * 追加进 {@code mc.options.keyMappings}，这里按翻译键反查一次并缓存。
     */
    private static KeyMapping keyBinding(int index) {
        KeyMapping cached = KEY_BINDINGS[index];
        if (cached != null) return cached;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return null;
        String name = KEY_NAMES[index];
        for (KeyMapping mapping : mc.options.keyMappings) {
            if (name.equals(mapping.getName())) {
                KEY_BINDINGS[index] = mapping;
                return mapping;
            }
        }
        return null;
    }

    /**
     * 每帧维护按下反馈状态（一次按键只播一次动画）。
     *
     * <p>按下沿检测用 KeyMapping 的 {@code isDown()} 自己做，不动 {@link SkillKeys} 的
     * {@code consumeClick()}；另外把 channel 从 0 跳到某个槽位也当作一次按下（玩家改键、
     * 反查失败时的兜底）。切换宝可梦/召唤/死亡时实体 id 或物种会变，一次性状态立刻归零。
     */
    private static void updateFeedbackState(Minecraft mc, boolean listening, String speciesId) {
        int entityId = ClientState.getCharizardId();
        if (!speciesId.equals(feedbackSpecies) || entityId != feedbackEntityId) {
            java.util.Arrays.fill(PRESS_START_MS, 0L);
            java.util.Arrays.fill(KEY_WAS_DOWN, false);
            lastFeedbackChannel = 0;
            feedbackSpecies = speciesId;
            feedbackEntityId = entityId;
        }
        long now = Util.getMillis();
        boolean canPress = listening && mc.screen == null;
        for (int i = 0; i < SkillPresentation.COUNT; i++) {
            KeyMapping key = keyBinding(i);
            if (key == null) {
                KEY_WAS_DOWN[i] = false;
                continue;
            }
            boolean down = key.isDown();
            if (canPress && down && !KEY_WAS_DOWN[i]) {
                PRESS_START_MS[i] = now;
            }
            KEY_WAS_DOWN[i] = down;
        }
        int channel = ClientState.getChannel();
        int slot = channel == 0 ? -1 : SkillKeys.slotForChannel(speciesId, channel);
        if (canPress && lastFeedbackChannel == 0 && channel != 0 && slot >= 0) {
            PRESS_START_MS[slot] = now;
        }
        lastFeedbackChannel = channel;
    }

    /** 按下反馈缩放：0.94 → 1.0 缓出回弹；动画结束后清位，不残留。 */
    private static float pressScale(int index) {
        long start = PRESS_START_MS[index];
        if (start == 0L) return 1.0F;
        long elapsed = Util.getMillis() - start;
        if (elapsed >= PRESS_ANIM_MS) {
            PRESS_START_MS[index] = 0L;
            return 1.0F;
        }
        float t = elapsed / (float) PRESS_ANIM_MS;
        float back = (1.0F - t) * (1.0F - t);
        return 1.0F - (1.0F - PRESS_SCALE) * back;
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

    /**
     * 带按下反馈的图标绘制：以图标中心缩放，外框与格子保持不动（"按下去的是图标本身"）。
     */
    private static void drawIcon(GuiGraphics graphics, ResourceLocation texture, int x, int y, int size,
                                 float alpha, float press) {
        if (press >= 0.999F) {
            drawIcon(graphics, texture, x, y, size, alpha);
            return;
        }
        float centerX = x + size / 2.0F;
        float centerY = y + size / 2.0F;
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0);
        graphics.pose().scale(press, press, 1);
        graphics.pose().translate(-centerX, -centerY, 0);
        drawIcon(graphics, texture, x, y, size, alpha);
        graphics.pose().popPose();
    }

    private static void drawHoveredTooltip(GuiGraphics ctx, Minecraft mc, int x, int y, float scale) {
        double[] cursor = GodView.cursorNdc(mc);
        double mouseX = (cursor[0] + 1) * mc.getWindow().getGuiScaledWidth() / 2.0;
        double mouseY = (1 - cursor[1]) * mc.getWindow().getGuiScaledHeight() / 2.0;
        for (int i = 0; i < SkillPresentation.COUNT; i++) {
            if (SkillPresentation.isSkillIconHovered(mc, i, x, y, scale, mouseX, mouseY)) {
                String speciesId = hudSpecies(mc);
                if (speciesId.isEmpty()) return;
                String skillName = skillName(speciesId, i);
                int cost = com.pkmoba.combat.Skill.cost(speciesId, i);
                ctx.renderTooltip(mc.font, Component.literal(skillName + " · 消耗 " + cost + " 豆"),
                        (int) mouseX, (int) mouseY);
                return;
            }
        }
    }

    /**
     * 面板内的无阴影居中文字。
     *
     * <p>为什么不用 {@code GuiGraphics#drawCenteredString}：它默认带阴影，阴影在设计坐标里
     * 偏移 1px；面板整体套了 0.72 的非整数 pose 缩放后，阴影只偏 0.72 屏幕像素，和字形一起
     * 被最近邻采样，糊成"两行字重叠"的双影（用户反馈的血量数字重影）。面板内所有文字统一走
     * 这里，保持清晰。
     */
    private static void drawCenteredText(GuiGraphics ctx, Minecraft mc, String text, int centerX, int y, int color) {
        ctx.drawString(mc.font, text, centerX - mc.font.width(text) / 2, y, color, false);
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
