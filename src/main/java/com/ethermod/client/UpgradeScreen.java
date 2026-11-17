package com.ethermod.client;

import static com.ethermod.client.UpgradeVisuals.*;

import com.google.gson.*;
import com.ethermod.item.*;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.network.ModNetwork;
import com.ethermod.network.payload.TraitPayloads;
import com.ethermod.trait.*;
import com.mojang.blaze3d.platform.*;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.*;

import org.joml.Quaternionf;

import java.nio.file.*;
import java.util.*;

/** Native, server-authoritative implementation of the approved K-screen prototype. */
public class UpgradeScreen extends Screen {
    // Exact prototype coordinates, translated from its reference frame (125,80).
    static final int W = 320, H = 158;
    private int left, top;
    private float uiScale = 1;
    private ItemStack stack = ItemStack.EMPTY;
    private final List<Hit> hits = new ArrayList<>();
    private Hit hovered;
    private String hoverKey = "";
    private double hoverAt;
    private double now, lastTime;
    private int mx, my, picker = -1, selectedSet = -1, pendingSave = -1, setScroll, renaming = -1;
    private EditBox rename, hex;
    private boolean colourPicker;
    private float hue = .45f, saturation = .5f, value = .8f;
    private int oldColour;
    private static String primary = "forest", accent = "amethyst";
    private static boolean settingsLoaded;
    private static final DyeColor[] DYES = {
        DyeColor.WHITE,
        DyeColor.LIGHT_GRAY,
        DyeColor.GRAY,
        DyeColor.BLACK,
        DyeColor.BROWN,
        DyeColor.RED,
        DyeColor.ORANGE,
        DyeColor.YELLOW,
        DyeColor.LIME,
        DyeColor.GREEN,
        DyeColor.CYAN,
        DyeColor.LIGHT_BLUE,
        DyeColor.BLUE,
        DyeColor.PURPLE,
        DyeColor.PINK,
        DyeColor.MAGENTA
    };
    private final Panel[] panels = {new Panel(0), new Panel(1), new Panel(2)};
    private final UpgradeAudio audio = new UpgradeAudio();
    private final Map<String, Double> hoverLevels = new HashMap<>();
    private static final ResourceLocation GRID_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ethermod", "ui/grid");
    private DynamicTexture gridTexture;
    private NativeImage gridImage;
    private Quaternionf returnFrom;
    private double returnStart;
    private float vy, yaw, pitch, spinDir = 1;
    private boolean dragging;
    private double dragAt, lastClick;
    private float dragX, dragY;
    private int dragPicker;

    private record Hit(
            String key,
            int x,
            int y,
            int w,
            int h,
            Runnable action,
            List<Component> tip,
            int colour,
            boolean pulse) {
        boolean contains(double a, double b) {
            return a >= x && a < x + w && b >= y && b < y + h;
        }
    }

    public UpgradeScreen() {
        super(Component.translatable("screen.ethermod.upgrade"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Palette p() {
        return themes.get(primary);
    }

    private Palette a() {
        return themes.get(accent);
    }

    private int stage() {
        return TraitBuild.openSlots(stack);
    }

    private double clock() {
        return System.nanoTime() / 1e9;
    }

    @Override
    protected void init() {
        load();
        loadSettings();
        uiScale = Math.min(1f, Math.min((width - 8f) / W, (height - 8f) / H));
        left = Math.round((width - W * uiScale) / 2);
        top = Math.round((height - H * uiScale) / 2);
        rename =
                new EditBox(
                        font, 0, 0, 43, 12, Component.translatable("screen.ethermod.set_name"));
        rename.setMaxLength(Loadouts.MAX_NAME);
        rename.setVisible(false);
        addRenderableWidget(rename);
        hex = new EditBox(font, 0, 0, 108, 12, Component.translatable("screen.ethermod.hex_code"));
        hex.setMaxLength(7);
        hex.setVisible(false);
        addRenderableWidget(hex);
        hex.setResponder(
                s -> {
                    try {
                        if (s.matches("#?[0-9a-fA-F]{6}")) {
                            float[] hsv =
                                    java.awt.Color.RGBtoHSB(
                                            (hex(s) >> 16) & 255,
                                            (hex(s) >> 8) & 255,
                                            hex(s) & 255,
                                            null);
                            hue = hsv[0];
                            saturation = hsv[1];
                            value = hsv[2];
                        }
                    } catch (Exception ignored) {
                    }
                });
        stack = Manipulator.stack(minecraft.player);
        now = clock();
        lastTime = now;
        for (Panel panel : panels) panel.reset();
        audio.open();
    }

    @Override
    public void tick() {
        if (minecraft.player == null) {
            onClose();
            return;
        }
        stack = Manipulator.stack(minecraft.player);
    }

    @Override
    public void renderBackground(GuiGraphics g, int x, int y, float pt) {}

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        // GuiGraphics otherwise flushes each tiny fill separately: batch the pixel-art geometry.
        g.drawManaged(() -> renderNative(g, mouseX, mouseY, pt));
    }

    private void renderNative(GuiGraphics g, int mouseX, int mouseY, float pt) {
        now = clock();
        double dt = Math.min(.05, now - lastTime);
        lastTime = now;
        mx = (int) ((mouseX - left) / uiScale);
        my = (int) ((mouseY - top) / uiScale);
        g.fill(0, 0, width, height, 0x98000000);
        g.pose().pushPose();
        g.pose().translate(left, top, 0);
        g.pose().scale(uiScale, uiScale, 1);
        hits.clear();
        frame(g, 0, 0, W, H, p());
        grid(g);
        frame(g, 0, 0, W, H, p(), false);
        text(g, Lang.tr("screen.ethermod.upgrade"), 8, 8, p().bright());
        control(
                g,
                "theme-primary",
                286,
                5,
                12,
                12,
                p(),
                p().accent(),
                () -> cycle(true),
                List.of(),
                false);
        control(
                g,
                "theme-accent",
                300,
                5,
                12,
                12,
                a(),
                a().accent(),
                () -> cycle(false),
                List.of(),
                false);
        if (Boolean.getBoolean("ethermod.uiDebug")) {
            control(
                    g,
                    "sound-profile",
                    272,
                    5,
                    12,
                    12,
                    p(),
                    p().accent(),
                    () -> audio.auditionNext(),
                    List.of(
                            label(Lang.tr("gui.ethermod.sound", audio.profileName()), p().bright()),
                            label(Lang.tr("gui.ethermod.sound_hint"), p().text())),
                    false);
            center(g, "S", 272, 5, 12, 12, p().text());
        }
        renderModel(g, dt);
        stageButton(g);
        palette(g);
        for (Panel panel : panels) panel.draw(g);
        sets(g);
        audio.observe(stack);
        audio.tick();
        // Modal content owns input; the underlying panels continue their animation.
        if (picker >= 0) {
            hits.clear();
            hits.add(new Hit("dismiss", 0, 0, W, H, () -> picker = -1, List.of(), 0, false));
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            modulePicker(g);
            g.pose().popPose();
        }
        if (colourPicker) {
            hits.clear();
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            colourDialog(g);
            g.pose().popPose();
        }
        hovered = null;
        for (int i = hits.size() - 1; i >= 0; i--)
            if (hits.get(i).contains(mx, my)) {
                hovered = hits.get(i);
                break;
            }
        String key = hovered == null ? "" : hovered.key();
        if (!key.equals(hoverKey)) {
            hoverKey = key;
            hoverAt = now;
            if (hovered != null && hovered.action() != null && !key.equals("dismiss"))
                audio.hover();
        }
        for (Hit h : hits) {
            double target = h == hovered ? 1 : 0, level = hoverLevels.getOrDefault(h.key(), 0d);
            hoverLevels.put(h.key(), level + (target - level) * Math.min(1, dt * 18));
        }
        if (renaming >= 0) {
            rename.setX(241);
            rename.setY(setRowY(renaming - setScroll) + (setRowHeight(renaming - setScroll) - 9) / 2);
            g.pose().pushPose();
            g.pose().translate(0, 0, 450);
            rename.render(g, mx, my, pt);
            g.pose().popPose();
        }
        if (colourPicker) {
            hex.setX(151);
            hex.setY(134);
            g.pose().pushPose();
            g.pose().translate(0, 0, 450);
            hex.render(g, mx, my, pt);
            g.pose().popPose();
        }
        g.pose().popPose();
        if (showTooltips && hovered != null && !hovered.tip().isEmpty() && now - hoverAt >= .180)
            renderColourTooltip(g, hovered, mouseX, mouseY);
    }

    /** Off only for the recorded mod-page GIFs, where a tooltip would be cut by the crop. */
    static boolean showTooltips = true;

    private void renderColourTooltip(GuiGraphics g, Hit hit, int mouseX, int mouseY) {
        var lines = new ArrayList<FormattedCharSequence>();
        int wrap = Math.max(40, Math.min(228, width - 16));
        for (Component line : hit.tip()) lines.addAll(font.split(line, wrap));
        int[] bounds = {0, 0, 0};
        net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner =
                (sw, sh, x, y, w, h) -> {
                    var pos =
                            net.minecraft.client.gui.screens.inventory.tooltip
                                    .DefaultTooltipPositioner.INSTANCE
                                    .positionTooltip(sw, sh, x, y, w, h);
                    bounds[0] = pos.x();
                    bounds[1] = pos.y();
                    bounds[2] = w;
                    return pos;
                };
        g.pose().pushPose();
        g.pose().translate(0, 0, 600);
        g.renderTooltip(font, lines, positioner, mouseX, mouseY);
        var titleColour = hit.tip().getFirst().getStyle().getColor();
        int colour = titleColour != null ? 0xff000000 | titleColour.getValue() : hit.colour();
        // Keep the one-pixel accent inside the tooltip border, at its actual clamped position.
        if (bounds[2] > 0) {
            g.pose().translate(0, 0, 500);
            g.fill(bounds[0] - 2, bounds[1] - 3, bounds[0] + bounds[2] + 2, bounds[1] - 2, colour);
        }
        g.pose().popPose();
    }

    private void grid(GuiGraphics g) {
        // Analytic coverage of one rigid lattice. Shading changes intensity, never line positions.
        double density = uiScale * minecraft.getWindow().getGuiScale();
        int tw = (int) Math.ceil(W * density), th = (int) Math.ceil(H * density);
        double pitch = 6.5;
        if (gridTexture == null || gridImage.getWidth() != tw || gridImage.getHeight() != th) {
            if (gridTexture != null) minecraft.getTextureManager().release(GRID_TEXTURE);
            gridImage = new NativeImage(tw, th, false);
            gridTexture = new DynamicTexture(gridImage);
            minecraft.getTextureManager().register(GRID_TEXTURE, gridTexture);
            gridTexture.setFilter(false, false);
        }
        double[] vertical = new double[tw], horizontal = new double[th];
        for (int x = 0; x < tw; x++) {
            double phase = ((x + .5) / density + now * 2) % pitch,
                    dist = Math.min(phase, pitch - phase);
            vertical[x] = Math.clamp((.5 + .5 / density - dist) * density, 0, 1);
        }
        for (int y = 0; y < th; y++) {
            double phase = ((y + .5) / density + now * 1.3) % pitch,
                    dist = Math.min(phase, pitch - phase);
            horizontal[y] = Math.clamp((.5 + .5 / density - dist) * density, 0, 1);
        }
        int cw = W / 4 + 2, ch = H / 4 + 2;
        double[][] shade = new double[ch][cw];
        for (int y = 0; y < ch; y++)
            for (int x = 0; x < cw; x++) {
                double wx = x * 4, wy = y * 4, nx = wx - now * 1.8, ny = wy - now * 1.2;
                double field =
                        (Math.sin(nx * .061 + Math.cos(ny * .039) * 1.7)
                                        + Math.cos(ny * .081 - nx * .014)
                                        + 2)
                                * .25;
                shade[y][x] =
                        Math.max(
                                .16 * field,
                                Math.max(0, .55 * (1 - Math.hypot(wx - 40, wy - 43) / 61)));
            }
        int[] ink = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = mix(p().bg(), p().accent(), i / 255d);
            ink[i] = (c & 0xff00ff00) | ((c >> 16) & 255) | ((c & 255) << 16);
        }
        for (int y = 0; y < th; y++)
            for (int x = 0; x < tw; x++) {
                double gx = x / density / 4, gy = y / density / 4;
                int cx = (int) gx, cy = (int) gy;
                double fx = gx - cx, fy = gy - cy;
                double upper = shade[cy][cx] * (1 - fx) + shade[cy][cx + 1] * fx,
                        lower = shade[cy + 1][cx] * (1 - fx) + shade[cy + 1][cx + 1] * fx;
                double amount =
                        Math.max(vertical[x], horizontal[y]) * (upper * (1 - fy) + lower * fy);
                gridImage.setPixelRGBA(
                        x, y, ink[(int) Math.clamp(Math.round(amount * 255), 0, 255)]);
            }
        g.flush();
        gridTexture.upload();
        g.enableScissor(
                left + Math.round(3 * uiScale),
                top + Math.round(3 * uiScale),
                left + Math.round((W - 3) * uiScale),
                top + Math.round((H - 3) * uiScale));
        g.blit(GRID_TEXTURE, 0, 0, W, H, 0, 0, tw, th, tw, th);
        g.disableScissor();
    }

    private void stageButton(GuiGraphics g) {
        var st = ManipulatorStage.get(stack);
        String title = st.title().getString();
        text(g, title, 8 + (71 - font.width(title)) / 2, 70, a().text());
        for (int i = 0; i < 3; i++) {
            g.fill(38 + i * 5, 83, 41 + i * 5, 88, a().shadow());
            g.fill(37 + i * 5, 82, 40 + i * 5, 87, i < stage() ? a().dim() : a().border());
        }
        boolean has = upgradeInventory() >= 0, active = st.next() != null;
        List<Component> tip =
                active
                        ? List.of(
                                label(Lang.tr("gui.ethermod.tier", st.next().title().getString()), a().bright()),
                                label(Lang.tr("gui.ethermod.requires_item", upgradeName()), 0xffedc16e))
                        : List.of();
        control(
                g,
                "upgrade",
                8,
                92,
                71,
                16,
                a(),
                active ? a().accent() : a().border(),
                active && has ? () -> send(new TraitPayloads.StageUpgrade()) : null,
                tip,
                false);
        if (active && has) g.fill(9, 93, 78, 107, a().ramp()[1]);
        center(
                g,
                active ? Lang.tr("gui.ethermod.upgrade") : Lang.tr("gui.ethermod.max_stage"),
                8,
                92,
                71,
                16,
                active ? a().bright() : a().border());
        g.fill(8, 111, 79, 112, mix(p().bg(), p().accent(), .55));
    }

    private String upgradeName() {
        return switch (ManipulatorStage.get(stack)) {
            case STONE -> Lang.tr("item.ethermod.upgrade_iron");
            case IRON -> Lang.tr("item.ethermod.upgrade_diamond");
            default -> Lang.tr("item.ethermod.upgrade_netherite");
        };
    }

    private int upgradeInventory() {
        for (int i = 0; i < minecraft.player.getInventory().items.size(); i++) {
            var it = minecraft.player.getInventory().items.get(i);
            if (it.getItem() instanceof TierUpgradeModule m
                    && m.getRequiredStage() == ManipulatorStage.get(stack)) return i;
        }
        return -1;
    }

    private void palette(GuiGraphics g) {
        int current = ManipulatorColors.body(stack), unlocked = 0;
        for (var dye : DYES)
            if (ManipulatorColors.isUnlocked(
                    stack, true, (dye.getTextureDiffuseColor() & 0xffffff))) unlocked++;
        final int count = unlocked;
        for (int k = 0; k < 18; k++) {
            int x = 8 + (k % 6) * 12, y = 115 + (k / 6) * 12;
            int colour =
                    k == 0
                            ? p().bg()
                            : k == 17
                                    ? p().accent()
                                    : (DYES[k - 1].getTextureDiffuseColor() & 0xffffff)
                                            | 0xff000000;
            boolean open =
                    k == 0
                            || k == 17 && count == 16
                            || k > 0
                                    && k < 17
                                    && ManipulatorColors.isUnlocked(stack, true, colour & 0xffffff);
            int inv = k > 0 && k < 17 ? dyeInventory(DYES[k - 1]) : -1;
            double strength = open ? 1 : inv >= 0 ? .4 + .6 * (.5 + .5 * Math.sin(now * 3.5)) : .3;
            int fill = mix(p().bg(), colour, strength);
            inset(g, x, y, 11, 11, p(), fill);
            g.fill(x + 10, y, x + 11, y + 1, p().border());
            g.fill(x, y + 10, x + 1, y + 11, p().border());
            if (k == 17) {
                for (int j = 0; j < 9; j++)
                    g.fill(
                            x + 1 + j,
                            y + 1,
                            x + 2 + j,
                            y + 10,
                            mix(p().bg(), hsv(j / 9f, 1, 1), strength));
            }
            if (k == 0) {
                rline(g, x + 2, y + 2, x + 8, y + 8, p().text());
                rline(g, x + 2, y + 8, x + 8, y + 2, p().text());
            }
            if (!open) {
                if (inv >= 0) {
                    plus(g, x + 2, y + 2, 7, 0xffeeeeee);
                } else lock(g, x + 3, y + 2, 0xffc6c6c6);
            }
            if (k == 0 && current < 0
                    || k > 0 && k < 17 && (current == (colour & 0xffffff))
                    || k == 17 && current >= 0 && !isDye(current))
                border(g, x, y, 11, 11, 0xffeeeeee);
            final int index = k, c = colour & 0xffffff, source = inv;
            List<Component> tip =
                    k == 0
                            ? List.of(
                                    label(Lang.tr("gui.ethermod.colour_default"), a().accent()),
                                    label(Lang.tr("gui.ethermod.colour_default_hint"), a().text()))
                            : k == 17 && count < 16
                            ? List.of(
                                    label(Lang.tr("gui.ethermod.colour_custom"), a().accent()),
                                    label(Lang.tr("gui.ethermod.colours_unlocked", count), a().text()),
                                    label(Lang.tr("gui.ethermod.colour_custom_hint"), a().text()))
                            : !open && k < 17
                                    ? List.of(
                                            label(
                                                    Lang.tr(
                                                            "gui.ethermod.requires_item",
                                                            DyeItem.byColor(DYES[k - 1])
                                                                    .getDescription()
                                                                    .getString()),
                                                    colour))
                                    : List.<Component>of();
            hit(
                    "dye" + k,
                    x,
                    y,
                    11,
                    11,
                    (index == 17 ? count == 16 : open || source >= 0)
                            ? () -> {
                                if (index == 17) {
                                    if (count == 16) openColour();
                                } else if (open)
                                    send(new TraitPayloads.PickColour(true, index == 0 ? -1 : c));
                                else if (source >= 0) send(new TraitPayloads.Paint(true, source));
                            }
                            : null,
                    tip,
                    colour,
                    false);
            hover(g, "dye" + k, x, y, 11, 11, colour, false);
        }
    }

    private boolean isDye(int c) {
        for (var d : DYES)
            if ((d.getTextureDiffuseColor() & 0xffffff) == (c & 0xffffff)) return true;
        return false;
    }

    private int dyeInventory(DyeColor dye) {
        var items = minecraft.player.getInventory().items;
        for (int i = 0; i < items.size(); i++)
            if (items.get(i).getItem() instanceof DyeItem d && d.getDyeColor() == dye) return i;
        return -1;
    }

    private static int setRowHeight(int row) { return row < 2 ? 14 : 13; }
    private static int setRowY(int row) { return 33 + row * 14 + Math.min(row, 2); }

    private void sets(GuiGraphics g) {
        inset(g, 235, 20, 77, 130, a(), a().ramp()[1]);
        text(g, Lang.tr("gui.ethermod.sets"), 238, 23, a().text());
        var list = Loadouts.list(stack);
        if (pendingSave >= 0 && list.size() > pendingSave) pendingSave = -1;
        if (selectedSet >= list.size() && pendingSave < 0) selectedSet = -1;
        setScroll = Math.clamp(setScroll, 0, Math.max(0, list.size() - Loadouts.MAX));
        for (int i = setScroll; i < Math.min(list.size(), setScroll + Loadouts.MAX); i++) {
            int row = i - setScroll;
            int y = setRowY(row), h = setRowHeight(row);
            boolean on = selectedSet == i;
            int c = on ? p().accent() : a().border();
            g.fill(238, y, 309, y + h, on ? mix(p().ramp()[1], p().ramp()[3], .35) : a().bg());
            border(g, 238, y, 71, h, c);
            String name = list.get(i).name();
            while (font.width(name) > 41 && !name.isEmpty())
                name = name.substring(0, name.length() - 1);
            if (renaming != i) text(g, name, 241, y + (h - 9) / 2, on ? p().bright() : a().text());
            var ids = Loadouts.lineIds(list.get(i));
            for (int j = 0; j < Math.min(3, ids.size()); j++) {
                var id = ResourceLocation.tryParse(ids.get(j));
                g.fill(287 + j * 7, y + (h - 5) / 2, 292 + j * 7, y + (h - 5) / 2 + 5, module(id, p()).accent());
            }
            final int index = i;
            hit(
                    "set" + i,
                    238,
                    y,
                    71,
                    h,
                    () -> {
                        if (selectedSet == index) selectedSet = -1;
                        else {
                            selectedSet = index;
                            send(new TraitPayloads.Loadout(Loadouts.LOAD, index, ""));
                        }
                    },
                    List.of(),
                    p().accent(),
                    false);
            hover(g, "set" + i, 238, y, 71, h, p().accent(), false);
        }
        control(
                g,
                "save",
                238,
                133,
                55,
                14,
                p(),
                list.size() < Loadouts.MAX ? p().accent() : p().border(),
                list.size() >= Loadouts.MAX
                        ? null
                        : () -> {
                            int n = list.size();
                            if (n < Loadouts.MAX && pendingSave < 0) {
                                pendingSave = n;
                                selectedSet = n;
                                send(new TraitPayloads.Loadout(Loadouts.SAVE, 0, Lang.tr("gui.ethermod.set_default", n + 1)));
                            }
                        },
                List.of(label(Lang.tr("gui.ethermod.save_set"), p().accent())),
                false);
        hollowPlus(g, 261, 135, p().bright());
        control(
                g,
                "delete",
                295,
                133,
                14,
                14,
                p(),
                0xffc8463c,
                () -> {
                    if (selectedSet >= 0) {
                        send(new TraitPayloads.Loadout(Loadouts.DELETE, selectedSet, ""));
                        selectedSet = -1;
                    } else
                        for (int i = 0; i < stage(); i++)
                            send(new TraitPayloads.Equip(i, Optional.empty()));
                },
                List.of(
                        label(
                                selectedSet < 0 ? Lang.tr("gui.ethermod.clear_build") : Lang.tr("gui.ethermod.delete_set"),
                                0xfff2928a)),
                false);
        var r = new Raster(14, 14);
        r.sprite("trash", 2, 2, 0xfff2928a);
        raster(g, r, 295, 133);
    }

    private void modulePicker(GuiGraphics g) {
        frame(g, 84, 17, 215, 145, a());
        text(g, Lang.tr("gui.ethermod.choose_module"), 91, 23, a().bright());
        var lines = TraitLines.all(minecraft.level.registryAccess());
        for (int i = 0; i < lines.size(); i++) {
            // 5-pixel margins on the sides and bottom, 3-pixel gaps between buttons
            int x = 89 + (i % 2) * 104, y = 35 + (i / 2) * 25;
            final int slot = picker;
            var e = lines.get(i);
            int used = TraitBuild.slotOf(stack, e.getKey());
            boolean available =
                    used < 0 && com.ethermod.trait.ModuleUnlocks.known(stack, e.getKey());
            var pal = module(e.getKey(), p());
            control(
                    g,
                    "module-pick" + i,
                    x,
                    y,
                    101,
                    22,
                    pal,
                    available ? pal.accent() : pal.border(),
                    available
                            ? () -> {
                                send(new TraitPayloads.Equip(slot, Optional.of(e.getKey())));
                                picker = -1;
                                selectedSet = -1;
                            }
                            : null,
                    moduleTip(e.getKey(), e.getValue()),
                    false);
            sprite(g, e.getKey(), x, y, 22, 22, available ? pal.accent() : pal.border());
            text(g, Lang.moduleName(e.getKey(), e.getValue()), x + 25, y + 7, available ? pal.accent() : pal.dim());
        }
    }

    private void openColour() {
        colourPicker = true;
        oldColour = ManipulatorColors.body(stack);
        int c = oldColour < 0 ? 0x75d5b9 : oldColour;
        float[] v = java.awt.Color.RGBtoHSB(c >> 16 & 255, c >> 8 & 255, c & 255, null);
        hue = v[0];
        saturation = v[1];
        value = v[2];
        hex.setValue(String.format("#%06X", c));
        hex.setVisible(true);
        hex.setFocused(false);
    }

    private void colourDialog(GuiGraphics g) {
        frame(g, 119, 39, 152, 130, a());
        text(g, Lang.tr("gui.ethermod.custom_trim"), 126, 46, a().bright());
        for (int x = 0; x < 88; x++)
            for (int y = 0; y < 64; y++)
                g.fill(127 + x, 60 + y, 128 + x, 61 + y, hsv(hue, x / 87f, 1 - y / 63f));
        for (int y = 0; y < 64; y++) g.fill(221, 60 + y, 229, 61 + y, hsv(y / 63f, 1, 1));
        int cx = 127 + Math.round(saturation * 87), cy = 60 + Math.round((1 - value) * 63);
        border(g, cx - 2, cy - 2, 5, 5, 0xff000000);
        border(g, cx - 1, cy - 1, 3, 3, 0xffffffff);
        border(g, 219, 58 + Math.round(hue * 63), 12, 4, 0xffffffff);
        text(g, Lang.tr("gui.ethermod.colour_new"), 236, 60, a().text());
        g.fill(236, 71, 263, 87, hsv(hue, saturation, value));
        text(g, Lang.tr("gui.ethermod.colour_old"), 236, 96, a().text());
        g.fill(236, 107, 263, 123, 0xff000000 | oldColour);
        text(g, Lang.tr("gui.ethermod.hex"), 127, 136, a().text());
        hit("sv", 127, 60, 88, 64, () -> pickColourPoint(mx, my, 1), List.of(), 0, false);
        hit("hue", 221, 60, 8, 64, () -> pickColourPoint(mx, my, 2), List.of(), 0, false);
        control(
                g,
                "cancel-colour",
                127,
                152,
                64,
                12,
                p(),
                p().accent(),
                () -> closeColour(),
                List.of(),
                false);
        center(g, Lang.tr("gui.cancel"), 127, 152, 64, 12, p().text());
        boolean valid = hex.getValue().matches("#?[0-9a-fA-F]{6}");
        control(
                g,
                "apply-colour",
                197,
                152,
                66,
                12,
                p(),
                valid ? p().accent() : p().border(),
                valid
                        ? () -> {
                            send(
                                    new TraitPayloads.PickColour(
                                            true, hex(hex.getValue()) & 0xffffff));
                            closeColour();
                        }
                        : null,
                List.of(),
                false);
        center(g, Lang.tr("gui.ethermod.apply"), 197, 152, 66, 12, p().bright());
    }

    private void closeColour() {
        colourPicker = false;
        hex.setVisible(false);
        dragPicker = 0;
    }

    private void pickColourPoint(double x, double y, int kind) {
        dragPicker = kind;
        if (kind == 1) {
            saturation = (float) Math.clamp((x - 127) / 87, 0, 1);
            value = 1 - (float) Math.clamp((y - 60) / 63, 0, 1);
        } else hue = (float) Math.clamp((y - 60) / 63, 0, 1);
        hex.setValue(String.format("#%06X", hsv(hue, saturation, value) & 0xffffff));
    }

    /** The model's idle turntable speed, in radians a second. */
    private static final float SPIN = .7f;

    private void renderModel(GuiGraphics g, double dt) {
        if (returnFrom != null) {
            // double click: the tilt settles back level, the spin picks up again
            float progress = (float) Math.clamp((now - returnStart) / .45, 0, 1);
            pitch += (0 - pitch) * (1 - (float) Math.pow(.8, dt * 60));
            if (progress == 1) returnFrom = null;
        }
        if (!dragging) {
            // a flick coasts on, then eases back into the advert-style turn, whichever way it
            // was last thrown
            vy += (SPIN * spinDir - vy) * (1 - (float) Math.pow(.97, dt * 60));
            yaw += vy * (float) dt;
            pitch += (0 - pitch) * (1 - (float) Math.pow(.985, dt * 60));
        }
        g.pose().pushPose();
        g.pose().translate(40, 43, 130);
        g.pose().scale(46, -46, 46);
        g.pose().mulPose(new Quaternionf().rotateX(.25f + pitch).rotateY(yaw));
        Lighting.setupFor3DItems();
        minecraft
                .getItemRenderer()
                .renderStatic(
                        stack,
                        ItemDisplayContext.FIXED,
                        0xf000f0,
                        OverlayTexture.NO_OVERLAY,
                        g.pose(),
                        g.bufferSource(),
                        minecraft.level,
                        0);
        g.flush();
        Lighting.setupFor3DItems();
        g.pose().popPose();
    }

    private final class Panel {
        final int slot;
        final ResourceLocation texture;
        DynamicTexture dynamic;
        NativeImage image;
        ResourceLocation lastModule;
        int lastStage = -1;
        String[] lastPicks = {"", "", ""};
        int[] previous, finalPixels, oldLink, newLink;
        double switched = -10, changed = -10;
        int changedTier = -1, newChoice = -1;
        boolean replacing;
        int[] groups = new int[17];

        Panel(int slot) {
            this.slot = slot;
            texture = ResourceLocation.fromNamespaceAndPath("ethermod", "ui/panel_" + slot);
        }

        void reset() {
            lastStage = -1;
            lastModule = null;
            switched = -10;
            previous = null;
            if (dynamic != null) {
                minecraft.getTextureManager().release(texture);
                dynamic = null;
            }
        }

        void start() {
            audio.switchPanel();
            switched = now;
            for (int i = 0; i < groups.length; i++) groups[i] = i % 2;
            for (int i = groups.length - 1; i > 0; i--) {
                int j = (int) (Math.random() * (i + 1)), v = groups[i];
                groups[i] = groups[j];
                groups[j] = v;
            }
        }

        void draw(GuiGraphics g) {
            int y = 20 + slot * 45;
            var id = TraitBuild.line(stack, slot);
            boolean unlocked = slot < stage();
            var pal = module(id, p());
            TraitLine line =
                    id == null
                            ? null
                            : TraitLines.get(minecraft.level.registryAccess(), id).orElse(null);
            String[] picks = {
                TraitBuild.pick(stack, slot, 0),
                TraitBuild.pick(stack, slot, 1),
                TraitBuild.pick(stack, slot, 2)
            };
            if (lastStage < 0) {
                if (unlocked) start();
            } else if (unlocked && (slot >= lastStage || !Objects.equals(id, lastModule))) {
                start();
                previous = null;
            } else if (!Arrays.equals(picks, lastPicks)) {
                previous = finalPixels == null ? null : finalPixels.clone();
                changed = now;
                switched = -10;
                oldLink = newLink = null;
                for (int t = 0; t < 3; t++)
                    if (!picks[t].equals(lastPicks[t])) {
                        changedTier = t;
                        newChoice = choice(line, picks[t], t);
                        int oldChoice = choice(line, lastPicks[t], t);
                        replacing = oldChoice >= 0 && newChoice >= 0;
                        if (replacing && t < 2 && t + 1 < stage()) {
                            oldLink = connection(t, oldChoice);
                            newLink = connection(t, newChoice);
                        }
                        break;
                    }
            }
            lastStage = stage();
            lastModule = id;
            lastPicks = picks.clone();
            Raster r = new Raster(146, 40);
            r.box(0, 0, 146, 40, pal.panel());
            r.box(0, 0, 145, 1, pal.insetShadow());
            r.box(0, 0, 1, 39, pal.insetShadow());
            r.box(1, 39, 145, 1, pal.border());
            r.box(145, 1, 1, 39, pal.border());
            if (!unlocked) {
                r.outline(6, 8, 24, 24, p().border(), p().bg(), 2);
                raster(g, r, 84, y);
                bigLock(g, 98, y + 14, p().dim());
                text(g, ManipulatorStage.opening(slot).title().getString(), 121, y + 16, p().dim());
                return;
            }
            int[] chosen = {
                choice(line, picks[0], 0), choice(line, picks[1], 1), choice(line, picks[2], 2)
            };
            int[] route = new int[146 * 40];
            Arrays.fill(route, -1);
            int routeLength = 0;
            int[] nx = {41, 85, 129}, cx = {63, 107};
            for (int t = 0; t < 3; t++) {
                int source = t == 0 ? 29 : cx[t - 1] + 10;
                r.owner = t * 2;
                for (int n = 0; n < 2; n++) r.elbow(source, 20, nx[t], 11 + n * 17, pal.ramp()[1]);
                if (t < 2)
                    for (int n = 0; n < 2; n++)
                        r.elbow(nx[t] + 10, 11 + n * 17, cx[t], 20, pal.ramp()[1]);
                if (chosen[t] >= 0) {
                    r.elbow(source, 20, nx[t], 11 + chosen[t] * 17, pal.accent());
                    routeLength = route(route, source, 20, nx[t], 11 + chosen[t] * 17, routeLength);
                    for (int yy = 6 + chosen[t] * 17; yy < 17 + chosen[t] * 17; yy++)
                        for (int xx = nx[t]; xx < nx[t] + 11; xx++)
                            route[yy * 146 + xx] = routeLength + xx - nx[t];
                    routeLength += 11;
                    if (t < 2 && t + 1 < stage()) {
                        r.owner = t * 2 + 1;
                        r.elbow(nx[t] + 10, 11 + chosen[t] * 17, cx[t], 20, pal.accent());
                        routeLength =
                                route(
                                        route,
                                        nx[t] + 12,
                                        11 + chosen[t] * 17,
                                        cx[t] - 1,
                                        20,
                                        routeLength);
                        for (int yy = 15; yy < 26; yy++)
                            for (int xx = cx[t]; xx < cx[t] + 11; xx++)
                                route[yy * 146 + xx] = routeLength + xx - cx[t];
                        routeLength += 11;
                    }
                }
            }
            for (int t = 0; t < 3; t++) {
                for (int n = 0; n < 2; n++) {
                    int yy = 6 + n * 17;
                    boolean sel = chosen[t] == n, open = t < stage();
                    r.owner = 5 + t * 3 + n;
                    r.outline(
                            nx[t],
                            yy,
                            11,
                            11,
                            sel ? pal.accent() : open ? pal.border() : pal.ramp()[1],
                            sel ? pal.ramp()[1] : pal.bg(),
                            0);
                    int tr = t, ch = n;
                    String key = "node" + slot + t + n;
                    if (line != null) {
                        var trait = line.tier(t).get(n);
                        if (!trait.id().isEmpty())
                            hit(
                                    key,
                                    84 + nx[t],
                                    y + yy,
                                    11,
                                    11,
                                    open && (t == 0 || chosen[t - 1] >= 0)
                                            ? () -> {
                                                send(new TraitPayloads.Pick(slot, tr, ch));
                                                selectedSet = -1;
                                            }
                                            : null,
                                    traitTip(id, trait, t),
                                    pal.accent(),
                                    open);
                    }
                    double light = hoverLevels.getOrDefault(key, 0d);
                    if (open && light > .001 && now - changed >= .28 && now - switched >= 1.43) {
                        int c =
                                mix(
                                        sel ? pal.accent() : pal.border(),
                                        mix(pal.accent(), pal.ramp()[5], .38),
                                        light * .65 * (.6 + .4 * Math.pow(Math.sin(now * 5), 2)));
                        r.outline(
                                nx[t],
                                yy,
                                11,
                                11,
                                c,
                                mix(sel ? pal.ramp()[1] : pal.bg(), pal.accent(), light * .13),
                                0);
                    }
                    // A marks the first choice of a tier, B the second
                    r.owner = 5 + t * 3 + n;
                    glyph(
                            r,
                            n == 0 ? GLYPH_A : GLYPH_B,
                            nx[t] + 4,
                            yy + 3,
                            mix(sel ? pal.accent() : open ? pal.border() : pal.ramp()[1], pal.text(),
                                    open && now - changed >= .28 && now - switched >= 1.43
                                            ? light * .75 * (.6 + .4 * Math.pow(Math.sin(now * 5), 2)) : 0));
                }
                if (t < 2) {
                    boolean on = chosen[t] >= 0 && t + 1 < stage();
                    r.owner = 14 + t;
                    r.outline(
                            cx[t],
                            15,
                            11,
                            11,
                            on ? pal.accent() : t + 1 < stage() ? pal.border() : pal.ramp()[1],
                            on ? pal.ramp()[1] : pal.bg(),
                            2);
                    if (t + 1 >= stage()) {
                        smallLock(r, cx[t] + 3, 17, pal.border());
                    }
                    double commonHover = hoverLevels.getOrDefault("common" + slot + t, 0d);
                    if (commonHover > .001
                            && t + 1 < stage()
                            && now - changed >= .28
                            && now - switched >= 1.43) {
                        double strength =
                                commonHover * .65 * (.6 + .4 * Math.pow(Math.sin(now * 5), 2));
                        r.outline(
                                cx[t],
                                15,
                                11,
                                11,
                                mix(
                                        on ? pal.accent() : pal.border(),
                                        mix(pal.accent(), pal.ramp()[5], .38),
                                        strength),
                                mix(on ? pal.ramp()[1] : pal.bg(), pal.accent(), commonHover * .12),
                                2);
                    }
                    if (t + 1 < stage()) {
                        String[] numeral = t == 0 ? GLYPH_II : GLYPH_III;
                        double emphasis = now - changed >= .28 && now - switched >= 1.43
                                ? commonHover * .75 * (.6 + .4 * Math.pow(Math.sin(now * 5), 2)) : 0;
                        glyph(r, numeral, cx[t] + (11 - numeral[0].length()) / 2, 18,
                                mix(on ? pal.accent() : t + 1 < stage() ? pal.border() : pal.ramp()[1], pal.text(), emphasis));
                    }
                    if (line != null)
                        hit(
                                "common" + slot + t,
                                84 + cx[t],
                                y + 15,
                                11,
                                11,
                                null,
                                commonTip(id, line.commons().get(t), t),
                                pal.accent(),
                                false);
                }
            }
            r.owner = 16;
            double badgeHover =
                    now - changed < .28 || now - switched < 1.43
                            ? 0
                            : hoverLevels.getOrDefault("badge" + slot, 0d);
            int badge = mix(pal.accent(), pal.ramp()[5], badgeHover * .75);
            r.outline(6, 8, 24, 24, badge, pal.ramp()[1], 2);
            if (line != null) {
                var module = modules.get(id.getPath());
                if (module != null)
                    r.centeredSprite(module.get("sprite").getAsString(), 6, 8, 24, 24, badge);
            } else {
                // a 2px plus, centred on the even-sized socket
                for (int k = 0; k < 2; k++) {
                    r.line(13, 19 + k, 22, 19 + k, pal.dim());
                    r.line(17 + k, 15, 17 + k, 24, pal.dim());
                }
            }
            hit(
                    "badge" + slot,
                    90,
                    y + 8,
                    24,
                    24,
                    () -> picker = slot,
                    line == null ? List.of() : moduleTip(id, line),
                    pal.accent(),
                    false);
            finalPixels = r.pixels.clone();
            double age = now - switched;
            if (age < 1.43) switchPixels(r, pal, age, route, routeLength);
            else if (previous != null && now - changed < .28) {
                int[] fill = selectionRoute(changedTier, newChoice);
                int total = 0;
                for (int distance : fill) total = Math.max(total, distance);
                traitFade(r, pal, fill, total);
            }
            upload(g, r, 84, y);
            // Smooth moving scanlines are drawn last and clipped inside the panel frame.
            g.enableScissor(
                    left + Math.round(85 * uiScale),
                    top + Math.round((y + 1) * uiScale),
                    left + Math.round(229 * uiScale),
                    top + Math.round((y + 39) * uiScale));
            g.pose().pushPose();
            g.pose().translate(0, 0, 2);
            double edge = waveEdge(age);
            if (age >= .2 && age < .88) {
                additive(g, 85, y + edge + .65, 144, .7, pal.accent());
            }
            for (double yy = -4 + (now * 7.5) % 4; yy < 39; yy += 4) {
                double distance = yy + .6 - edge,
                        boost =
                                age >= .2 && age < 1.43 && distance >= 0 && distance <= 6
                                        ? Math.sin(Math.PI * distance / 6)
                                        : 0;
                int colour = mix(pal.accent(), pal.bright(), boost),
                        alpha = (int) (20 + 64 * boost);
                for (int band = 0; band < 4; band++) {
                    double strength = 1 - Math.abs((band + .5) / 2 - 1);
                    additive(
                            g,
                            85,
                            y + yy + band * .3,
                            144,
                            .3,
                            ((int) (alpha * strength) << 24) | (colour & 0xffffff));
                }
            }
            double beamY = -10 + (now * 19 + slot * 17) % 77;
            for (int band = 0; band < 9; band++) {
                double strength = band < 7 ? band / 7d : (9 - band) / 2d;
                additive(
                        g,
                        85,
                        y + beamY + band,
                        144,
                        1,
                        ((int) (24 * strength) << 24) | (pal.accent() & 0xffffff));
            }
            g.pose().popPose();
            g.disableScissor();
        }

        void switchPixels(Raster r, Palette pal, double age, int[] route, int length) {
            for (int i = 0; i < r.pixels.length; i++) {
                int yy = i / 146, x = i % 146, target = r.pixels[i];
                if (age < .05) {
                    r.pixels[i] =
                            x == 0 || x == 145 || yy == 0 || yy == 39 ? p().border() : p().panel();
                    continue;
                }
                if (age < .125) {
                    r.pixels[i] = pal.dim();
                    continue;
                }
                if (age < .2) {
                    int base =
                            x == 0 || x == 145 || yy == 0 || yy == 39 ? pal.border() : pal.panel();
                    r.pixels[i] = mix(pal.dim(), base, (age - .125) / .075);
                    continue;
                }
                if (x == 0 || x == 145 || yy == 0 || yy == 39) continue;
                double edge = waveEdge(age), distance = edge - yy;
                if (distance < 0) {
                    r.pixels[i] = pal.panel();
                    continue;
                }
                int owner = r.owners[i];
                double resolved = Math.clamp((distance - 2) / 12, 0, 1), pulse = 0;
                if (owner >= 0) {
                    int bottom =
                            owner < 5
                                    ? 33
                                    : owner < 14
                                            ? 15 + ((owner - 5) % 3) * 17
                                            : owner < 16 ? 25 : 31;
                    double lo = .2, hi = .88;
                    for (int k = 0; k < 18; k++) {
                        double mid = (lo + hi) / 2;
                        if (waveEdge(mid) < bottom + 14) lo = mid;
                        else hi = mid;
                    }
                    resolved = Math.clamp((age - hi - .035) / .13, 0, 1);
                    double burst = 1.23 + groups[owner] * .09;
                    if (age >= burst)
                        pulse =
                                age < burst + .035
                                        ? 1
                                        : 1 - Math.clamp((age - burst - .035) / .055, 0, 1);
                }
                resolved = Math.floor(resolved * 5 + 1e-8) / 5;
                if (route[i] >= 0) {
                    double progress =
                            Math.clamp(((age - .91) / .28 * (length + 8) - route[i]) / 8, 0, 1);
                    target = mix(pal.panel(), target, progress * progress * (3 - 2 * progress));
                }
                if (pulse > 0) target = mix(target, liftTone(target, pal), pulse);
                r.pixels[i] = mix(pal.accent(), target, resolved);
            }
        }

        void traitFade(Raster r, Palette pal, int[] path, int length) {
            double elapsed = now - changed;
            for (int i = 0; i < r.pixels.length; i++) {
                int old = previous[i], target = r.pixels[i];
                if (oldLink != null && (oldLink[i] >= 0 || newLink[i] >= 0)) {
                    if (elapsed < .12) {
                        r.pixels[i] = mix(old, pal.panel(), elapsed / .12);
                        continue;
                    }
                    if (elapsed < .13) {
                        r.pixels[i] = pal.panel();
                        continue;
                    }
                    if (newLink[i] >= 0) {
                        int total = 0;
                        for (int d : newLink) total = Math.max(total, d);
                        double f =
                                Math.clamp(
                                        ((elapsed - .13) / .15 * (total + 8) - newLink[i]) / 8,
                                        0,
                                        1);
                        r.pixels[i] = mix(pal.panel(), target, f * f * (3 - 2 * f));
                    } else r.pixels[i] = mix(pal.panel(), target, (elapsed - .13) / .15);
                    continue;
                }
                if (old == target) continue;
                double amount = Math.clamp(elapsed / .12, 0, 1);
                if (path[i] >= 0 && newChoice >= 0) {
                    double progress =
                            Math.clamp((elapsed / .28 * (length + 8) - path[i]) / 8, 0, 1);
                    amount = progress * progress * (3 - 2 * progress);
                }
                r.pixels[i] = mix(old, target, amount);
            }
        }

        int[] connection(int tier, int n) {
            int[] d = new int[146 * 40];
            Arrays.fill(d, -1);
            route(
                    d,
                    new int[] {41, 85, 129}[tier] + 11,
                    11 + n * 17,
                    new int[] {63, 107}[tier] - 1,
                    20,
                    0);
            return d;
        }

        int[] selectionRoute(int tier, int n) {
            int[] d = new int[146 * 40];
            Arrays.fill(d, -1);
            if (tier < 0 || n < 0) return d;
            int x = new int[] {41, 85, 129}[tier],
                    source = tier == 0 ? 29 : new int[] {73, 117}[tier - 1];
            int len = route(d, source, 20, x, 11 + n * 17, 0);
            for (int yy = 6 + n * 17; yy < 17 + n * 17; yy++)
                for (int xx = x; xx < x + 11; xx++) d[yy * 146 + xx] = len + xx - x;
            len += 11;
            if (tier < 2 && tier + 1 < stage()) {
                int cx = new int[] {63, 107}[tier];
                len = route(d, x + 11, 11 + n * 17, cx - 1, 20, len);
                for (int yy = 15; yy < 26; yy++)
                    for (int xx = cx; xx < cx + 11; xx++) d[yy * 146 + xx] = len + xx - cx;
            }
            return d;
        }

        void upload(GuiGraphics g, Raster r, int x, int y) {
            if (dynamic == null) {
                image = new NativeImage(146, 40, false);
                dynamic = new DynamicTexture(image);
                minecraft.getTextureManager().register(texture, dynamic);
            }
            for (int i = 0; i < r.pixels.length; i++) {
                int c = r.pixels[i];
                image.setPixelRGBA(
                        i % 146, i / 146, (c & 0xff00ff00) | ((c >> 16) & 255) | ((c & 255) << 16));
            }
            dynamic.upload();
            g.blit(texture, x, y, 0, 0, 146, 40, 146, 40);
        }
    }

    private static double waveEdge(double age) {
        double e = age - .2;
        if (e <= .5) return 38 * Math.pow(Math.max(0, e / .5), 2.2);
        double p = Math.clamp((e - .5) / .18, 0, 1);
        return 38 + 16 * (1 - Math.pow(1 - p, 2.4));
    }

    private static int liftTone(int colour, Palette pal) {
        int nearest = 0;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < pal.ramp().length; i++) {
            long d = 0;
            for (int shift : new int[] {0, 8, 16}) {
                int delta = ((colour >> shift) & 255) - ((pal.ramp()[i] >> shift) & 255);
                d += (long) delta * delta;
            }
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        return pal.ramp()[Math.min(nearest + 1, pal.ramp().length - 1)];
    }

    private int choice(TraitLine line, String id, int tier) {
        if (line == null || id.isEmpty()) return -1;
        for (int n = 0; n < 2; n++) if (line.tier(tier).get(n).id().equals(id)) return n;
        return -1;
    }

    private static int route(int[] d, int x, int y, int ex, int ey, int start) {
        int mid = (x + ex) / 2;
        int[][] points = {{x, y}, {mid, y}, {mid, ey}, {ex, ey}};
        for (int p = 1; p < points.length; p++) {
            x = points[p - 1][0];
            y = points[p - 1][1];
            for (; ; ) {
                if (x >= 0 && x < 146 && y >= 0 && y < 40) d[y * 146 + x] = start++;
                if (x == points[p][0] && y == points[p][1]) break;
                if (x != points[p][0]) x += Integer.signum(points[p][0] - x);
                else y += Integer.signum(points[p][1] - y);
            }
        }
        return start;
    }

    private List<Component> traitTip(ResourceLocation id, Trait trait, int tier) {
        int c = module(id, p()).accent();
        var out = new ArrayList<Component>();
        out.add(label(Lang.traitName(trait), c));
        out.add(rich(Lang.traitDesc(trait), c));
        return out;
    }

    private List<Component> commonTip(ResourceLocation id, Trait trait, int common) {
        int c = module(id, p()).accent();
        var out = new ArrayList<Component>();
        out.add(label(Lang.traitName(trait), c));
        if (stage() < common + 2)
            out.add(
                    label(
                            Lang.tr(
                                    "gui.ethermod.requires_tier",
                                    ManipulatorStage.opening(common + 1).title().getString()),
                            0xffdbad72));
        out.add(rich(Lang.traitDesc(trait), c));
        return out;
    }

    private List<Component> moduleTip(ResourceLocation id, TraitLine line) {
        int c = module(id, p()).accent();
        var out = new ArrayList<Component>();
        out.add(label(Lang.tr("gui.ethermod.module_named", Lang.moduleName(id, line)), c));
        var all = rules.getAsJsonObject("modules");
        if (all.has(id.getPath()))
            appendLines(out, all.getAsJsonObject(id.getPath()).get("body"), c, false);
        else out.add(rich(Lang.traitDesc(line.badge()), c));
        return out;
    }

    private void appendLines(List<Component> out, JsonElement element, int c, boolean condition) {
        if (element == null) return;
        if (element.isJsonArray()) {
            for (var e : element.getAsJsonArray()) appendLines(out, e, c, condition);
        } else
            out.add(
                    condition
                            ? label(element.getAsString(), 0xffdbad72)
                            : rich(element.getAsString(), c));
    }

    private MutableComponent rich(String source, int colour) {
        if (Lang.english())
            source =
                    source.replaceAll("(?i)orb\\s+mode", "Mode")
                        .replaceAll("(?i)\\bmodule\\b", "Module")
                        .replaceAll("(?i)\\bbeam\\b", "Beam")
                        .replaceAll("(?i)\\bmode\\b", "Mode")
                        .replaceAll("(?i)\\btier\\b", "Tier")
                        .replaceAll("(?i)\\bcommon\\b", "Common");
        var root = Component.empty();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(
                                "\\[(/?)(b|u|i|when|color(?:=#[0-9A-Fa-f]{6})?)\\]|[+-]?\\d+(?:\\.\\d+)?(?:x\\d+|%|x)?|[A-Z][A-Za-z-]*"
                                    + " Modes?")
                        .matcher(source);
        int end = 0;
        Style style = Style.EMPTY.withColor(0xffcbd0d0);
        var styles = new ArrayDeque<Style>();
        while (m.find()) {
            root.append(Component.literal(source.substring(end, m.start())).withStyle(style));
            String token = m.group();
            if (token.startsWith("[")) {
                if (m.group(1).equals("/")) {
                    if (!styles.isEmpty()) style = styles.pop();
                } else {
                    styles.push(style);
                    String tag = m.group(2);
                    if (tag.equals("b")) style = style.withBold(true);
                    if (tag.equals("u")) style = style.withUnderlined(true);
                    if (tag.equals("when")) style = style.withColor(0xdbad72);
                    if (tag.startsWith("color="))
                        style = style.withColor(hex(tag.substring(6)) & 0xffffff);
                }
            } else
                root.append(
                        Component.literal(token)
                                .withStyle(
                                        style.withColor(
                                                Character.isDigit(token.charAt(0))
                                                                || token.startsWith("+")
                                                                || token.startsWith("-")
                                                        ? 0xe9bf67
                                                        : colour & 0xffffff)));
            end = m.end();
        }
        root.append(Component.literal(source.substring(end)).withStyle(style));
        return root;
    }

    private static MutableComponent label(String s, int c) {
        return Component.literal(s)
                .withStyle(style -> style.withColor(c & 0xffffff).withItalic(false));
    }

    private void hit(
            String key,
            int x,
            int y,
            int w,
            int h,
            Runnable action,
            List<Component> tip,
            int c,
            boolean pulse) {
        hits.add(new Hit(key, x, y, w, h, action, tip, c, pulse));
    }

    private void control(
            GuiGraphics g,
            String key,
            int x,
            int y,
            int w,
            int h,
            Palette pal,
            int c,
            Runnable action,
            List<Component> tip,
            boolean pulse) {
        g.fill(x, y, x + w, y + h, pal.bg());
        int hi = mix(c, 0xffffffff, .27), sh = mix(c, 0xff000000, .4);
        g.fill(x, y, x + w - 1, y + 1, hi);
        g.fill(x, y + 1, x + 1, y + h - 1, hi);
        g.fill(x + 1, y + h - 1, x + w, y + h, sh);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, sh);
        g.fill(x + w - 1, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + 1, y + h, c);
        if (action != null || !tip.isEmpty()) hit(key, x, y, w, h, action, tip, c, pulse);
        if (action != null) hover(g, key, x, y, w, h, c, pulse);
    }

    private void hover(
            GuiGraphics g, String key, int x, int y, int w, int h, int c, boolean pulse) {
        double level = hoverLevels.getOrDefault(key, 0d);
        if (level < .005) return;
        int alpha = (int) (level * 22);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, (alpha << 24) | (c & 0xffffff));
        border(g, x, y, w, h, ((int) (level * 100) << 24) | (c & 0xffffff));
    }

    private void inset(GuiGraphics g, int x, int y, int w, int h, Palette pal, int fill) {
        g.fill(x, y, x + w, y + h, fill);
        g.fill(x, y, x + w - 1, y + 1, pal.insetShadow());
        g.fill(x, y + 1, x + 1, y + h - 1, pal.insetShadow());
        g.fill(x + 1, y + h - 1, x + w, y + h, pal.border());
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, pal.border());
    }

    private void frame(GuiGraphics g, int x, int y, int w, int h, Palette pal) {
        frame(g, x, y, w, h, pal, true);
    }

    private void frame(GuiGraphics g, int x, int y, int w, int h, Palette pal, boolean fill) {
        if (fill) g.fill(x + 3, y + 3, x + w - 3, y + h - 3, pal.bg());
        g.fill(x + 5, y, x + w - 5, y + 1, 0xff000000);
        g.fill(x + 5, y + 1, x + w - 5, y + 3, pal.border());
        g.fill(x, y + 5, x + 1, y + h - 5, 0xff000000);
        g.fill(x + 1, y + 5, x + 3, y + h - 5, pal.border());
        g.fill(x + 5, y + h - 3, x + w - 5, y + h - 1, pal.shadow());
        g.fill(x + 5, y + h - 1, x + w - 5, y + h, 0xff000000);
        g.fill(x + w - 3, y + 5, x + w - 1, y + h - 5, pal.shadow());
        g.fill(x + w - 1, y + 5, x + w, y + h - 5, 0xff000000);
        var corners = assets.getAsJsonObject("frame");
        String[] keys = {"tl", "tr", "bl", "br"};
        int[] xs = {x, x + w - 5, x, x + w - 5}, ys = {y, y, y + h - 5, y + h - 5};
        for (int k = 0; k < 4; k++) {
            var rows = corners.getAsJsonArray(keys[k]);
            for (int j = 0; j < 5; j++) {
                String row = rows.get(j).getAsString();
                for (int i = 0; i < 5; i++) {
                    char t = row.charAt(i);
                    if (t != '.')
                        g.fill(
                                xs[k] + i,
                                ys[k] + j,
                                xs[k] + i + 1,
                                ys[k] + j + 1,
                                t == 'B'
                                        ? 0xff000000
                                        : t == 'H'
                                                ? pal.border()
                                                : t == 'S'
                                                        ? pal.shadow()
                                                        // J: where the highlight meets the shadow
                                                        : t == 'J'
                                                                ? mix(pal.border(), pal.shadow(), .5)
                                                                : pal.bg());
                }
            }
        }
    }

    private void border(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    private void additive(GuiGraphics g, double x, double y, double w, double h, int c) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale((float) w, (float) h, 1);
        g.fill(net.minecraft.client.renderer.RenderType.lightning(), 0, 0, 1, 1, c);
        g.pose().popPose();
    }

    private void raster(GuiGraphics g, Raster r, int x, int y) {
        for (int yy = 0; yy < r.h; yy++) {
            int start = 0;
            while (start < r.w) {
                int c = r.pixels[yy * r.w + start], end = start + 1;
                while (end < r.w && r.pixels[yy * r.w + end] == c) end++;
                if ((c >>> 24) > 0) g.fill(x + start, y + yy, x + end, y + yy + 1, c);
                start = end;
            }
        }
    }

    private void sprite(GuiGraphics g, ResourceLocation id, int x, int y, int w, int h, int c) {
        var data = modules.get(id.getPath());
        if (data == null) return;
        var r = new Raster(w, h);
        r.centeredSprite(data.get("sprite").getAsString(), 0, 0, w, h, c);
        raster(g, r, x, y);
    }

    private void text(GuiGraphics g, String s, int x, int y, int c) {
        g.drawString(font, s, x, y, c, true);
    }

    private void center(GuiGraphics g, String s, int x, int y, int w, int h, int c) {
        text(g, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2, c);
    }

    private void rline(GuiGraphics g, int x, int y, int ex, int ey, int c) {
        Raster r = new Raster(Math.abs(ex - x) + 1, Math.abs(ey - y) + 1);
        r.line(
                x > ex ? r.w - 1 : 0,
                y > ey ? r.h - 1 : 0,
                x > ex ? 0 : r.w - 1,
                y > ey ? 0 : r.h - 1,
                c);
        raster(g, r, Math.min(x, ex), Math.min(y, ey));
    }

    private void lock(GuiGraphics g, int x, int y, int c) {
        var r = new Raster(6, 8);
        smallLock(r, 0, 0, 0xff161b1a);
        raster(g, r, x + 1, y + 1);
        r = new Raster(6, 8);
        smallLock(r, 0, 0, c);
        raster(g, r, x, y);
    }

    private void bigLock(GuiGraphics g, int x, int y, int c) {
        String[] rows = {
            "..####..",
            ".#....#.",
            "#......#",
            "#......#",
            "#......#",
            "#......#",
            "########",
            "########",
            "########",
            "########",
            "########"
        };
        var r = new Raster(8, 11);
        for (int yy = 0; yy < 11; yy++)
            for (int xx = 0; xx < 8; xx++) if (rows[yy].charAt(xx) == '#') r.pixel(xx, yy, c);
        raster(g, r, x, y);
    }

    private static final String[] GLYPH_A = {".#.", "#.#", "###", "#.#", "#.#"};
    private static final String[] GLYPH_B = {"##.", "#.#", "##.", "#.#", "##."};
    private static final String[] GLYPH_II = {"#.#", "#.#", "#.#", "#.#", "#.#"};
    private static final String[] GLYPH_III = {"#.#.#", "#.#.#", "#.#.#", "#.#.#", "#.#.#"};

    private static void glyph(Raster r, String[] rows, int x, int y, int c) {
        for (int yy = 0; yy < rows.length; yy++)
            for (int xx = 0; xx < rows[yy].length(); xx++)
                if (rows[yy].charAt(xx) == '#') r.pixel(x + xx, y + yy, c);
    }

    private static void smallLock(Raster r, int x, int y, int c) {
        String[] rows = {".###.", "#...#", "#...#", "#...#", "#####", "#####", "#####"};
        for (int yy = 0; yy < rows.length; yy++)
            for (int xx = 0; xx < 5; xx++)
                if (rows[yy].charAt(xx) == '#') r.pixel(x + xx, y + yy, c);
    }

    private void plus(GuiGraphics g, int x, int y, int size, int c) {
        g.fill(x + size / 2 + 1, y + 1, x + size / 2 + 2, y + size + 1, 0xff151515);
        g.fill(x + 1, y + size / 2 + 1, x + size + 1, y + size / 2 + 2, 0xff151515);
        g.fill(x + size / 2, y, x + size / 2 + 1, y + size, c);
        g.fill(x, y + size / 2, x + size, y + size / 2 + 1, c);
    }

    private void hollowPlus(GuiGraphics g, int x, int y, int c) {
        int[][] p = {
            {3, 0}, {6, 0}, {6, 3}, {9, 3}, {9, 6}, {6, 6}, {6, 9}, {3, 9}, {3, 6}, {0, 6}, {0, 3},
            {3, 3}, {3, 0}
        };
        for (int i = 1; i < p.length; i++)
            rline(g, x + p[i - 1][0], y + p[i - 1][1], x + p[i][0], y + p[i][1], c);
    }

    private static int hsv(float h, float s, float v) {
        return 0xff000000 | (java.awt.Color.HSBtoRGB(h, s, v) & 0xffffff);
    }

    private void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload packet) {
        ModNetwork.send(packet);
    }

    private void cycle(boolean first) {
        String current = first ? primary : accent,
                next = colours.get((colours.indexOf(current) + 1) % colours.size());
        if (first) primary = next;
        else accent = next;
        saveSettings();
    }

    private void loadSettings() {
        if (settingsLoaded) return;
        settingsLoaded = true;
        try {
            var file = minecraft.gameDirectory.toPath().resolve("config/ethermod-ui.json");
            var settings = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (themes.containsKey(settings.get("primary").getAsString()))
                primary = settings.get("primary").getAsString();
            if (themes.containsKey(settings.get("accent").getAsString()))
                accent = settings.get("accent").getAsString();
        } catch (Exception ignored) {
        }
    }

    private void saveSettings() {
        try {
            var path = minecraft.gameDirectory.toPath().resolve("config/ethermod-ui.json");
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path, "{\"primary\":\"" + primary + "\",\"accent\":\"" + accent + "\"}");
        } catch (Exception e) {
            com.ethermod.EtherMod.LOGGER.warn("Could not save UI theme", e);
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        double lx = (x - left) / uiScale, ly = (y - top) / uiScale;
        now = clock();
        if (colourPicker && hex.mouseClicked(lx, ly, button)) {
            setFocused(hex);
            return true;
        }
        if (renaming >= 0) {
            if (rename.mouseClicked(lx, ly, button)) {
                setFocused(rename);
                return true;
            }
            finishRename();
        }
        if (button == 0
                && picker < 0
                && !colourPicker
                && lx >= 17
                && lx < 63
                && ly >= 20
                && ly < 66) {
            if (now - lastClick < .25) {
                returnFrom = new Quaternionf();
                returnStart = now;
                dragging = false;
            } else {
                dragging = true;
                returnFrom = null;
                dragAt = now;
                dragX = (float) lx;
                dragY = (float) ly;
                vy = 0;
            }
            lastClick = now;
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            var h = hits.get(i);
            if (h.contains(lx, ly)) {
                if (button == 1 && h.key().startsWith("badge") && picker < 0 && !colourPicker) {
                    int slot = Integer.parseInt(h.key().substring(5));
                    send(new TraitPayloads.Equip(slot, Optional.empty()));
                    selectedSet = -1;
                    audio.press("delete");
                    return true;
                }
                if (button != 0) return true;
                if (h.key().startsWith("set")
                        && now - lastClick < .25
                        && h.key().equals(hoverKey)) {
                    renaming = Integer.parseInt(h.key().substring(3));
                    rename.setValue(Loadouts.list(stack).get(renaming).name());
                    rename.setVisible(true);
                    rename.setFocused(true);
                    setFocused(rename);
                    rename.moveCursorToEnd(false);
                    rename.setHighlightPos(0);
                    lastClick = 0;
                    return true;
                }
                lastClick = now;
                if (h.action() != null) {
                    audio.press(h.key());
                    h.action().run();
                } else if (h.key().startsWith("node")) audio.refused();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        double lx = (x - left) / uiScale, ly = (y - top) / uiScale;
        if (colourPicker && dragPicker != 0) {
            pickColourPoint(lx, ly, dragPicker);
            return true;
        }
        if (dragging) {
            double t = clock(), dt = Math.max(.008, t - dragAt);
            // a turntable: sideways drags turn it, up/down tilts it (within limits), so the model
            // always follows the cursor the way it is dragged
            float turn = (float) (dx / uiScale) * .035f, tilt = (float) (dy / uiScale) * .035f;
            yaw += turn;
            pitch = Math.clamp(pitch + tilt, -1.1f, 1.1f);
            vy = (float) (vy * .3 + turn / dt * .7);
            dragAt = t;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double x, double y, int b) {
        dragPicker = 0;
        if (dragging) {
            dragging = false;
            if (clock() - dragAt > .08) vy = 0;
            vy = Math.clamp(vy, -18, 18);
            if (Math.abs(vy) > .3f) spinDir = Math.signum(vy);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if ((x - left) / uiScale >= 235 && picker < 0 && !colourPicker) {
            setScroll = Math.clamp(setScroll - (int) Math.signum(vertical), 0, Math.max(0, Loadouts.list(stack).size() - Loadouts.MAX));
            return true;
        }
        return false;
    }

    private void finishRename() {
        if (renaming >= 0 && !rename.getValue().isBlank())
            send(new TraitPayloads.Loadout(Loadouts.RENAME, renaming, rename.getValue()));
        renaming = -1;
        rename.setVisible(false);
        setFocused(null);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (renaming < 0
                && !hex.isFocused()
                && com.ethermod.event.ManipulatorKeys.SKILL_TREE_KEY.matches(key, scan)) {
            onClose();
            return true;
        }
        if (key == 256) {
            if (colourPicker) {
                closeColour();
                return true;
            }
            if (picker >= 0) {
                picker = -1;
                return true;
            }
            if (renaming >= 0) {
                renaming = -1;
                rename.setVisible(false);
                return true;
            }
        }
        if (key == 257 && renaming >= 0) {
            finishRename();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void removed() {
        audio.stop();
        for (var panel : panels)
            if (panel.dynamic != null) {
                minecraft.getTextureManager().release(panel.texture);
                panel.dynamic = null;
            }
        saveSettings();
        if (gridTexture != null) {
            minecraft.getTextureManager().release(GRID_TEXTURE);
            gridTexture = null;
        }
        super.removed();
    }
}
