package com.ethermod.client;

import static com.ethermod.client.KDraw.*;
import static com.ethermod.client.KUi.send;
import static com.ethermod.client.UiSettings.p;
import static com.ethermod.client.UpgradeVisuals.*;

import com.ethermod.item.*;
import com.ethermod.network.payload.TraitPayloads;
import com.ethermod.trait.*;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * One of the three module panels: the two-node trait tree for a slot, rasterised each frame into a
 * 146x40 texture together with its switch / pick transition animations.
 */
final class KTierPanel {
    private final KUi ui;
    private final UpgradeAudio audio;
    private final IntConsumer openPicker;
    private final Runnable onPick;
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

    KTierPanel(int slot, KUi ui, UpgradeAudio audio, IntConsumer openPicker, Runnable onPick) {
        this.slot = slot;
        this.ui = ui;
        this.audio = audio;
        this.openPicker = openPicker;
        this.onPick = onPick;
        texture = ResourceLocation.fromNamespaceAndPath("ethermod", "ui/panel_" + slot);
    }

    void reset() {
        lastStage = -1;
        lastModule = null;
        switched = -10;
        previous = null;
        release();
    }

    /** Frees the texture when the screen closes. */
    void release() {
        if (dynamic != null) {
            ui.mc.getTextureManager().release(texture);
            dynamic = null;
        }
    }

    void start() {
        audio.switchPanel();
        switched = ui.now;
        for (int i = 0; i < groups.length; i++) groups[i] = i % 2;
        for (int i = groups.length - 1; i > 0; i--) {
            int j = (int) (Math.random() * (i + 1)), v = groups[i];
            groups[i] = groups[j];
            groups[j] = v;
        }
    }

    /** Starts the reveal or the pick cross-fade when the slot's module or choices changed. */
    private void trackChanges(ResourceLocation id, TraitLine line, boolean unlocked, String[] picks) {
        if (lastStage < 0) {
            if (unlocked) start();
        } else if (unlocked && (slot >= lastStage || !Objects.equals(id, lastModule))) {
            start();
            previous = null;
        } else if (!Arrays.equals(picks, lastPicks)) {
            previous = finalPixels == null ? null : finalPixels.clone();
            changed = ui.now;
            switched = -10;
            oldLink = newLink = null;
            for (int t = 0; t < 3; t++)
                if (!picks[t].equals(lastPicks[t])) {
                    changedTier = t;
                    newChoice = choice(line, picks[t], t);
                    int oldChoice = choice(line, lastPicks[t], t);
                    replacing = oldChoice >= 0 && newChoice >= 0;
                    if (replacing && t < 2 && t + 1 < ui.stage()) {
                        oldLink = KPanelFx.connection(t, oldChoice);
                        newLink = KPanelFx.connection(t, newChoice);
                    }
                    break;
                }
        }
    }

    /** Node and link positions in the panel: the two choices of each tier, and the commons. */
    private static final int[] NODE_X = {41, 85, 129}, COMMON_X = {63, 107};

    void draw(GuiGraphics g) {
        int y = 20 + slot * 45;
        var id = TraitBuild.line(ui.stack, slot);
        boolean unlocked = slot < ui.stage();
        var pal = module(id, p());
        TraitLine line =
                id == null
                        ? null
                        : TraitLines.get(ui.mc.level.registryAccess(), id).orElse(null);
        String[] picks = {
            TraitBuild.pick(ui.stack, slot, 0),
            TraitBuild.pick(ui.stack, slot, 1),
            TraitBuild.pick(ui.stack, slot, 2)
        };
        trackChanges(id, line, unlocked, picks);
        lastStage = ui.stage();
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
            ui.text(g, ManipulatorStage.opening(slot).title().getString(), 121, y + 16, p().dim());
            return;
        }
        int[] chosen = {
            choice(line, picks[0], 0), choice(line, picks[1], 1), choice(line, picks[2], 2)
        };
        int[] route = new int[146 * 40];
        Arrays.fill(route, -1);
        int routeLength = drawLinks(r, pal, chosen, route);
        for (int t = 0; t < 3; t++) {
            drawNodes(r, pal, id, line, chosen, t, y);
            if (t < 2) drawCommon(r, pal, id, line, chosen, t, y);
        }
        drawBadge(r, pal, id, line, y);
        finalPixels = r.pixels.clone();
        double age = ui.now - switched;
        if (age < 1.43) KPanelFx.switchPixels(r, pal, age, route, routeLength, groups);
        else if (previous != null && ui.now - changed < .28) {
            int[] fill = KPanelFx.selectionRoute(changedTier, newChoice, ui.stage());
            int total = 0;
            for (int distance : fill) total = Math.max(total, distance);
            KPanelFx.traitFade(r, pal, fill, total, ui.now - changed, previous, oldLink, newLink, newChoice);
        }
        upload(g, r, 84, y);
        drawScanlines(g, pal, y, age);
    }

    /**
     * The links between the nodes, the chosen path lit; fills {@code route} with each lit pixel's
     * distance along the path (for the switch animation) and returns the path's length.
     */
    private int drawLinks(Raster r, Palette pal, int[] chosen, int[] route) {
        int[] nx = NODE_X, cx = COMMON_X;
        int routeLength = 0;
        for (int t = 0; t < 3; t++) {
            int source = t == 0 ? 29 : cx[t - 1] + 10;
            r.owner = t * 2;
            for (int n = 0; n < 2; n++) r.elbow(source, 20, nx[t], 11 + n * 17, pal.ramp()[1]);
            if (t < 2)
                for (int n = 0; n < 2; n++)
                    r.elbow(nx[t] + 10, 11 + n * 17, cx[t], 20, pal.ramp()[1]);
            if (chosen[t] >= 0) {
                r.elbow(source, 20, nx[t], 11 + chosen[t] * 17, pal.accent());
                routeLength = KPanelFx.route(route, source, 20, nx[t], 11 + chosen[t] * 17, routeLength);
                for (int yy = 6 + chosen[t] * 17; yy < 17 + chosen[t] * 17; yy++)
                    for (int xx = nx[t]; xx < nx[t] + 11; xx++)
                        route[yy * 146 + xx] = routeLength + xx - nx[t];
                routeLength += 11;
                if (t < 2 && t + 1 < ui.stage()) {
                    r.owner = t * 2 + 1;
                    r.elbow(nx[t] + 10, 11 + chosen[t] * 17, cx[t], 20, pal.accent());
                    routeLength =
                            KPanelFx.route(
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
        return routeLength;
    }

    /** Tier {@code t}'s two choice nodes, their hover glow, hit areas and A/B letters. */
    private void drawNodes(
            Raster r, Palette pal, ResourceLocation id, TraitLine line, int[] chosen, int t, int y) {
        int[] nx = NODE_X;
        for (int n = 0; n < 2; n++) {
            int yy = 6 + n * 17;
            boolean sel = chosen[t] == n, open = t < ui.stage();
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
                    ui.hit(
                            key,
                            84 + nx[t],
                            y + yy,
                            11,
                            11,
                            open && (t == 0 || chosen[t - 1] >= 0)
                                    ? () -> {
                                        send(new TraitPayloads.Pick(slot, tr, ch));
                                        onPick.run();
                                    }
                                    : null,
                            KTooltips.traitTip(id, trait, t),
                            pal.accent(),
                            open);
            }
            double light = ui.hoverLevels.getOrDefault(key, 0d);
            if (open && light > .001 && ui.now - changed >= .28 && ui.now - switched >= 1.43) {
                int c =
                        mix(
                                sel ? pal.accent() : pal.border(),
                                mix(pal.accent(), pal.ramp()[5], .38),
                                light * .65 * (.6 + .4 * Math.pow(Math.sin(ui.now * 5), 2)));
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
                            open && ui.now - changed >= .28 && ui.now - switched >= 1.43
                                    ? light * .75 * (.6 + .4 * Math.pow(Math.sin(ui.now * 5), 2)) : 0));
        }
    }

    /** The common node after tier {@code t} (II, III): locked until the next stage. */
    private void drawCommon(
            Raster r, Palette pal, ResourceLocation id, TraitLine line, int[] chosen, int t, int y) {
        int[] cx = COMMON_X;
        boolean on = chosen[t] >= 0 && t + 1 < ui.stage();
        r.owner = 14 + t;
        r.outline(
                cx[t],
                15,
                11,
                11,
                on ? pal.accent() : t + 1 < ui.stage() ? pal.border() : pal.ramp()[1],
                on ? pal.ramp()[1] : pal.bg(),
                2);
        if (t + 1 >= ui.stage()) {
            smallLock(r, cx[t] + 3, 17, pal.border());
        }
        double commonHover = ui.hoverLevels.getOrDefault("common" + slot + t, 0d);
        if (commonHover > .001
                && t + 1 < ui.stage()
                && ui.now - changed >= .28
                && ui.now - switched >= 1.43) {
            double strength =
                    commonHover * .65 * (.6 + .4 * Math.pow(Math.sin(ui.now * 5), 2));
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
        if (t + 1 < ui.stage()) {
            String[] numeral = t == 0 ? GLYPH_II : GLYPH_III;
            double emphasis = ui.now - changed >= .28 && ui.now - switched >= 1.43
                    ? commonHover * .75 * (.6 + .4 * Math.pow(Math.sin(ui.now * 5), 2)) : 0;
            glyph(r, numeral, cx[t] + (11 - numeral[0].length()) / 2, 18,
                    mix(on ? pal.accent() : t + 1 < ui.stage() ? pal.border() : pal.ramp()[1], pal.text(), emphasis));
        }
        if (line != null)
            ui.hit(
                    "common" + slot + t,
                    84 + cx[t],
                    y + 15,
                    11,
                    11,
                    null,
                    KTooltips.commonTip(id, line.commons().get(t), t, ui.stage()),
                    pal.accent(),
                    false);
    }

    /** The module badge on the left: opens the module picker. */
    private void drawBadge(Raster r, Palette pal, ResourceLocation id, TraitLine line, int y) {
        r.owner = 16;
        double badgeHover =
                ui.now - changed < .28 || ui.now - switched < 1.43
                        ? 0
                        : ui.hoverLevels.getOrDefault("badge" + slot, 0d);
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
        ui.hit(
                "badge" + slot,
                90,
                y + 8,
                24,
                24,
                () -> openPicker.accept(slot),
                line == null ? List.of() : KTooltips.moduleTip(id, line),
                pal.accent(),
                false);
    }

    /** Smooth moving scanlines are drawn last and clipped inside the panel frame. */
    private void drawScanlines(GuiGraphics g, Palette pal, int y, double age) {
        g.enableScissor(
                ui.left + Math.round(85 * ui.scale),
                ui.top + Math.round((y + 1) * ui.scale),
                ui.left + Math.round(229 * ui.scale),
                ui.top + Math.round((y + 39) * ui.scale));
        g.pose().pushPose();
        g.pose().translate(0, 0, 2);
        double edge = KPanelFx.waveEdge(age);
        if (age >= .2 && age < .88) {
            // flush with the revealed rows (0..floor(edge)), so no dark row opens above the line
            additive(g, 85, y + Math.floor(edge) + .65, 144, .7, pal.accent());
        }
        for (double yy = -4 + (ui.now * 7.5) % 4; yy < 39; yy += 4) {
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
        double beamY = -10 + (ui.now * 19 + slot * 17) % 77;
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

    /** What the texture holds now: a panel at rest is not uploaded again every frame. */
    private int[] uploaded;

    void upload(GuiGraphics g, Raster r, int x, int y) {
        if (dynamic == null) {
            image = new NativeImage(146, 40, false);
            dynamic = new DynamicTexture(image);
            ui.mc.getTextureManager().register(texture, dynamic);
            uploaded = null;
        }
        if (uploaded == null || !Arrays.equals(uploaded, r.pixels)) {
            for (int i = 0; i < r.pixels.length; i++) {
                int c = r.pixels[i];
                image.setPixelRGBA(
                        i % 146, i / 146, abgr(c));
            }
            dynamic.upload();
            if (uploaded == null || uploaded.length != r.pixels.length) uploaded = r.pixels.clone();
            else System.arraycopy(r.pixels, 0, uploaded, 0, uploaded.length);
        }
        g.blit(texture, x, y, 0, 0, 146, 40, 146, 40);
    }

    private static int choice(TraitLine line, String id, int tier) {
        if (line == null || id.isEmpty()) return -1;
        for (int n = 0; n < 2; n++) if (line.tier(tier).get(n).id().equals(id)) return n;
        return -1;
    }
}
