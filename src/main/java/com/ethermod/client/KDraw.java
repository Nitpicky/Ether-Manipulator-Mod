package com.ethermod.client;

import static com.ethermod.client.UpgradeVisuals.*;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Stateless pixel-art drawing primitives shared by every part of the K-screen. */
final class KDraw {
    private KDraw() {}

    static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h, Palette pal, int fill) {
        g.fill(x, y, x + w, y + h, fill);
        g.fill(x, y, x + w - 1, y + 1, pal.insetShadow());
        g.fill(x, y + 1, x + 1, y + h - 1, pal.insetShadow());
        g.fill(x + 1, y + h - 1, x + w, y + h, pal.border());
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, pal.border());
    }

    static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, Palette pal) {
        frame(g, x, y, w, h, pal, true);
    }

    static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, Palette pal, boolean fill) {
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

    static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    static void additive(GuiGraphicsExtractor g, double x, double y, double w, double h, int c) {
        g.pose().pushMatrix();
        g.pose().translate((float) x, (float) y);
        g.pose().scale((float) w, (float) h);
        g.fill(ModPipelines.GUI_ADDITIVE, 0, 0, 1, 1, c);
        g.pose().popMatrix();
    }

    static void raster(GuiGraphicsExtractor g, Raster r, int x, int y) {
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

    static void sprite(GuiGraphicsExtractor g, Identifier id, int x, int y, int w, int h, int c) {
        var data = modules.get(id.getPath());
        if (data == null) return;
        var r = new Raster(w, h);
        r.centeredSprite(data.get("sprite").getAsString(), 0, 0, w, h, c);
        raster(g, r, x, y);
    }

    static void rline(GuiGraphicsExtractor g, int x, int y, int ex, int ey, int c) {
        Raster r = new Raster(Math.abs(ex - x) + 1, Math.abs(ey - y) + 1);
        r.line(
                x > ex ? r.w - 1 : 0,
                y > ey ? r.h - 1 : 0,
                x > ex ? 0 : r.w - 1,
                y > ey ? 0 : r.h - 1,
                c);
        raster(g, r, Math.min(x, ex), Math.min(y, ey));
    }

    static void lock(GuiGraphicsExtractor g, int x, int y, int c) {
        var r = new Raster(6, 8);
        smallLock(r, 0, 0, 0xff161b1a);
        raster(g, r, x + 1, y + 1);
        r = new Raster(6, 8);
        smallLock(r, 0, 0, c);
        raster(g, r, x, y);
    }

    static void bigLock(GuiGraphicsExtractor g, int x, int y, int c) {
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

    static final String[] GLYPH_A = {".#.", "#.#", "###", "#.#", "#.#"};
    static final String[] GLYPH_B = {"##.", "#.#", "##.", "#.#", "##."};
    static final String[] GLYPH_II = {"#.#", "#.#", "#.#", "#.#", "#.#"};
    static final String[] GLYPH_III = {"#.#.#", "#.#.#", "#.#.#", "#.#.#", "#.#.#"};

    static void glyph(Raster r, String[] rows, int x, int y, int c) {
        for (int yy = 0; yy < rows.length; yy++)
            for (int xx = 0; xx < rows[yy].length(); xx++)
                if (rows[yy].charAt(xx) == '#') r.pixel(x + xx, y + yy, c);
    }

    static void smallLock(Raster r, int x, int y, int c) {
        String[] rows = {".###.", "#...#", "#...#", "#...#", "#####", "#####", "#####"};
        for (int yy = 0; yy < rows.length; yy++)
            for (int xx = 0; xx < 5; xx++)
                if (rows[yy].charAt(xx) == '#') r.pixel(x + xx, y + yy, c);
    }

    static void plus(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        g.fill(x + size / 2 + 1, y + 1, x + size / 2 + 2, y + size + 1, 0xff151515);
        g.fill(x + 1, y + size / 2 + 1, x + size + 1, y + size / 2 + 2, 0xff151515);
        g.fill(x + size / 2, y, x + size / 2 + 1, y + size, c);
        g.fill(x, y + size / 2, x + size, y + size / 2 + 1, c);
    }

    static void hollowPlus(GuiGraphicsExtractor g, int x, int y, int c) {
        int[][] p = {
            {3, 0}, {6, 0}, {6, 3}, {9, 3}, {9, 6}, {6, 6}, {6, 9}, {3, 9}, {3, 6}, {0, 6}, {0, 3},
            {3, 3}, {3, 0}
        };
        for (int i = 1; i < p.length; i++)
            rline(g, x + p[i - 1][0], y + p[i - 1][1], x + p[i][0], y + p[i][1], c);
    }

    static int hsv(float h, float s, float v) {
        return 0xff000000 | (java.awt.Color.HSBtoRGB(h, s, v) & 0xffffff);
    }

}
