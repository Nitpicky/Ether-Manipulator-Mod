package com.ethermod.client;

import com.google.gson.*;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared prototype palettes and one-bit sprites, loaded as ordinary resource-pack assets. */
final class UpgradeVisuals {
    static JsonObject assets, rules;
    static final List<String> colours = new ArrayList<>();
    static final Map<String, Palette> themes = new LinkedHashMap<>();
    static final Map<String, JsonObject> modules = new HashMap<>();

    /**
     * Resource reload: reads the (possibly changed) assets again if they were loaded, so an open
     * screen keeps working; if they can't be read now, the next use tries again.
     */
    static void reset() {
        if (assets == null) return;
        try {
            load();
        } catch (RuntimeException e) {
            assets = null;
        }
    }

    static void load() {
        assets = read("screen.json");
        rules = read("tooltips.json");
        colours.clear();
        themes.clear();
        modules.clear();
        for (var e : assets.getAsJsonObject("themePalettes").entrySet()) {
            colours.add(e.getKey());
            var value = e.getValue().getAsJsonObject();
            themes.put(e.getKey(), new Palette(ramp(value), false));
        }
        for (var e : assets.getAsJsonArray("modules"))
            modules.put(e.getAsJsonObject().get("key").getAsString(), e.getAsJsonObject());
    }

    private static JsonObject read(String name) {
        try (var stream =
                        Minecraft.getInstance()
                                .getResourceManager()
                                .open(
                                        ResourceLocation.fromNamespaceAndPath(
                                                "ethermod", "ui/" + name));
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("Missing upgrade screen asset: " + name, e);
        }
    }

    static int hex(String s) {
        return 0xff000000 | Integer.parseInt(s.replace("#", ""), 16);
    }

    static int[] ramp(JsonObject obj) {
        JsonArray a = obj.getAsJsonArray(obj.has("ramp") ? "ramp" : "palette");
        int[] result = new int[a.size()];
        for (int i = 0; i < a.size(); i++) result[i] = hex(a.get(i).getAsString());
        return result;
    }

    static Palette module(ResourceLocation id, Palette fallback) {
        var m = id == null ? null : modules.get(id.getPath());
        return m == null ? fallback : new Palette(ramp(m), true);
    }

    /** A Set panel button's own fixed ramp ("add", "copy", "paste", "delete"), whatever the theme. */
    static Palette button(String key) {
        var all = assets.getAsJsonObject("buttonPalettes");
        var b = all == null ? null : all.getAsJsonObject(key);
        return b == null ? UiSettings.p() : new Palette(ramp(b), false);
    }

    static int mix(int a, int b, double t) {
        t = Math.clamp(t, 0, 1);
        int r = 0xff000000;
        for (int shift : new int[] {0, 8, 16})
            r |=
                    ((int) Math.round(((a >> shift) & 255) * (1 - t) + ((b >> shift) & 255) * t))
                            << shift;
        return r;
    }

    /** ARGB to the byte order NativeImage.setPixelRGBA expects (red and blue swapped). */
    static int abgr(int c) {
        return (c & 0xff00ff00) | ((c >> 16) & 255) | ((c & 255) << 16);
    }

    record Palette(int[] ramp, boolean module) {
        int bg() {
            return ramp[0];
        }

        int panel() {
            return module ? bg() : ramp[1];
        }

        int border() {
            return ramp[2];
        }

        int dim() {
            return ramp[3];
        }

        int accent() {
            return ramp[4];
        }

        int text() {
            return ramp[5];
        }

        int bright() {
            return ramp[6];
        }

        int shadow() {
            return mix(bg(), 0xff000000, .55);
        }

        int insetShadow() {
            return mix(bg(), 0xff000000, .35);
        }
    }

    /**
     * Pixel buffer preserves node shapes through colour resolution; never colours bounding
     * rectangles.
     */
    static final class Raster {
        final int w, h;
        final int[] pixels, owners;
        int owner = -1;

        Raster(int w, int h) {
            this.w = w;
            this.h = h;
            pixels = new int[w * h];
            owners = new int[w * h];
            Arrays.fill(owners, -1);
        }

        void pixel(int x, int y, int c) {
            if (x >= 0 && y >= 0 && x < w && y < h) {
                pixels[y * w + x] = c;
                owners[y * w + x] = owner;
            }
        }

        void box(int x, int y, int width, int height, int c) {
            for (int yy = y; yy < y + height; yy++)
                for (int xx = x; xx < x + width; xx++) pixel(xx, yy, c);
        }

        void outline(int x, int y, int width, int height, int c, int fill, int cut) {
            for (int yy = 0; yy < height; yy++) {
                int pad = Math.max(0, Math.max(cut - yy, cut - (height - 1 - yy)));
                if (yy == 0 || yy == height - 1) box(x + pad, y + yy, width - 2 * pad, 1, c);
                else {
                    box(x + pad, y + yy, 1, 1, c);
                    box(x + width - 1 - pad, y + yy, 1, 1, c);
                    if (fill != 0) box(x + pad + 1, y + yy, width - pad * 2 - 2, 1, fill);
                }
            }
        }

        void line(int x, int y, int endX, int endY, int c) {
            int dx = Math.abs(endX - x),
                    sx = x < endX ? 1 : -1,
                    dy = -Math.abs(endY - y),
                    sy = y < endY ? 1 : -1,
                    err = dx + dy;
            for (; ; ) {
                pixel(x, y, c);
                if (x == endX && y == endY) break;
                int e = err * 2;
                if (e >= dy) {
                    err += dy;
                    x += sx;
                }
                if (e <= dx) {
                    err += dx;
                    y += sy;
                }
            }
        }

        void elbow(int x, int y, int endX, int endY, int c) {
            int mid = (x + endX) / 2;
            line(x, y, mid, y, c);
            line(mid, y, mid, endY, c);
            line(mid, endY, endX, endY, c);
        }

        void sprite(String name, int x, int y, int c) {
            var icon = assets.getAsJsonObject("icons").getAsJsonObject(name);
            if (icon == null) return;
            String bits = icon.get("bits").getAsString();
            int sw = icon.get("w").getAsInt();
            for (int i = 0; i < bits.length(); i++)
                if (bits.charAt(i) == '1') pixel(x + i % sw, y + i / sw, c);
        }

        void centeredSprite(String name, int x, int y, int width, int height, int c) {
            var icon = assets.getAsJsonObject("icons").getAsJsonObject(name);
            if (icon == null) return;
            int sw = icon.get("w").getAsInt(),
                    sh = icon.get("h").getAsInt(),
                    l = 0,
                    t = 0,
                    r = sw,
                    b = sh;
            if (icon.has("bounds")) {
                var a = icon.getAsJsonArray("bounds");
                l = a.get(0).getAsInt();
                t = a.get(1).getAsInt();
                r = a.get(2).getAsInt();
                b = a.get(3).getAsInt();
            }
            sprite(name, x + (width - (r - l)) / 2 - l, y + (height - (b - t)) / 2 - t, c);
        }
    }
}
