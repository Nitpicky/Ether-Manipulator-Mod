package com.ethermod.client;

import static com.ethermod.client.UiSettings.p;
import static com.ethermod.client.UpgradeVisuals.*;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** The animated lattice behind the K-screen, rasterised to a dynamic texture at screen density. */
final class KGrid {
    private static final Identifier GRID_TEXTURE =
            Identifier.fromNamespaceAndPath("ethermod", "ui/grid");
    private DynamicTexture gridTexture;
    private NativeImage gridImage;

    /**
     * The lattice drifts a couple of GUI pixels a second, so it is rasterised and uploaded at most
     * 30 times a second (and at once when the size or colours change); frames in between draw the
     * last upload. The work arrays are kept between frames.
     */
    private static final double BAKE_INTERVAL = 1 / 30d;

    private double bakedAt = Double.NEGATIVE_INFINITY;
    private int bakedBg, bakedAccent;
    private double[] vertical = new double[0], horizontal = new double[0];
    private double[][] shade;
    private final int[] ink = new int[256];

    void release(KUi ui) {
        if (gridTexture != null) {
            ui.mc.getTextureManager().release(GRID_TEXTURE);
            gridTexture = null;
        }
    }

    void draw(GuiGraphics g, KUi ui) {
        // Analytic coverage of one rigid lattice. Shading changes intensity, never line positions.
        double density = ui.scale * ui.mc.getWindow().getGuiScale();
        int tw = (int) Math.ceil(UpgradeScreen.W * density), th = (int) Math.ceil(UpgradeScreen.H * density);
        double pitch = 6.5;
        if (gridTexture == null || gridImage.getWidth() != tw || gridImage.getHeight() != th) {
            if (gridTexture != null) ui.mc.getTextureManager().release(GRID_TEXTURE);
            gridImage = new NativeImage(tw, th, false);
            gridTexture = new DynamicTexture(gridImage);
            ui.mc.getTextureManager().register(GRID_TEXTURE, gridTexture);
            gridTexture.setFilter(false, false);
            bakedAt = Double.NEGATIVE_INFINITY;
        }
        int bg = p().bg(), accent = p().accent();
        if (bg != bakedBg || accent != bakedAccent || ui.now < bakedAt || ui.now - bakedAt >= BAKE_INTERVAL) {
            bakedAt = ui.now;
            bakedBg = bg;
            bakedAccent = accent;
            bake(ui, density, tw, th, pitch, bg, accent);
            g.flush();
            gridTexture.upload();
        }
        g.enableScissor(
                ui.left + Math.round(3 * ui.scale),
                ui.top + Math.round(3 * ui.scale),
                ui.left + Math.round((UpgradeScreen.W - 3) * ui.scale),
                ui.top + Math.round((UpgradeScreen.H - 3) * ui.scale));
        g.blit(GRID_TEXTURE, 0, 0, UpgradeScreen.W, UpgradeScreen.H, 0, 0, tw, th, tw, th);
        g.disableScissor();
    }

    /** Rasterises the lattice for this moment into the grid image. */
    private void bake(KUi ui, double density, int tw, int th, double pitch, int bg, int accent) {
        if (vertical.length != tw) vertical = new double[tw];
        if (horizontal.length != th) horizontal = new double[th];
        for (int x = 0; x < tw; x++) {
            double phase = ((x + .5) / density + ui.now * 2) % pitch,
                    dist = Math.min(phase, pitch - phase);
            vertical[x] = Math.clamp((.5 + .5 / density - dist) * density, 0, 1);
        }
        for (int y = 0; y < th; y++) {
            double phase = ((y + .5) / density + ui.now * 1.3) % pitch,
                    dist = Math.min(phase, pitch - phase);
            horizontal[y] = Math.clamp((.5 + .5 / density - dist) * density, 0, 1);
        }
        int cw = UpgradeScreen.W / 4 + 2, ch = UpgradeScreen.H / 4 + 2;
        if (shade == null) shade = new double[ch][cw];
        for (int y = 0; y < ch; y++)
            for (int x = 0; x < cw; x++) {
                double wx = x * 4, wy = y * 4, nx = wx - ui.now * 1.8, ny = wy - ui.now * 1.2;
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
        for (int i = 0; i < 256; i++) {
            int c = mix(bg, accent, i / 255d);
            ink[i] = abgr(c);
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
    }
}
