package com.ethermod.client;

import com.ethermod.item.OrbMode;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Module Badges as icons: each mode is shown by the Badge of the Module it comes from (the K
 * screen's one-bit sprites), in that Module's colour. Beam mode is Plasmic, and Kinetic while a
 * right click charges.
 */
public final class Badges {

    private Badges() {}

    /** The Module (sprite name) a mode belongs to; Beam is Plasmic, or Kinetic when charging. */
    public static String module(OrbMode mode, boolean kinetic) {
        return switch (mode) {
            case ENERGY -> kinetic ? "kinetic" : "plasmic";
            case WATER, FROST, VACUUM -> "hydraulic";
            case LAVA, IGNITE -> "pyrotechnic";
            case GROWTH -> "botanic";
            case LIGHT -> "photonic";
            case TETHER -> "gravitic";
            case GUST -> "pneumatic";
            case MASS -> "tectonic";
        };
    }

    /** Colours found so far, for the assets they were read from (asked several times a frame). */
    private static final java.util.Map<String, Integer> COLORS = new java.util.HashMap<>();

    private static Object colorsFrom;

    /** Badge textures by Module. */
    private static final java.util.Map<String, net.minecraft.resources.Identifier> TEXTURES =
            new java.util.HashMap<>();

    /** The Module's own colour (opaque ARGB). */
    public static int color(String module) {
        ensure();
        if (colorsFrom != UpgradeVisuals.assets) {
            COLORS.clear();
            colorsFrom = UpgradeVisuals.assets;
        }
        Integer known = COLORS.get(module);
        if (known != null) return known;
        int found = 0xFFFFFFFF;
        for (var m : UpgradeVisuals.modules.values())
            if (m.has("sprite") && m.get("sprite").getAsString().equals(module)) {
                found = UpgradeVisuals.hex(m.get("color").getAsString());
                break;
            }
        COLORS.put(module, found);
        return found;
    }

    /**
     * Draws a Badge (the 16x16 icon with its baked 2 px black outline, so 20x20) in the given
     * colour, the icon's own top-left at x, y. The sprites live in textures/gui/badge/ and are
     * baked by bake_badges.py; they can be touched up by hand.
     */
    public static void draw(GuiGraphicsExtractor g, String module, int x, int y, int scale, int argb) {
        var tex =
                TEXTURES.computeIfAbsent(
                        module,
                        k ->
                                net.minecraft.resources.Identifier.fromNamespaceAndPath(
                                        "ethermod", "textures/gui/badge/" + k + ".png"));
        int s = 20 * scale;
        g.blit(
                net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                tex,
                x - 2 * scale,
                y - 2 * scale,
                0,
                0,
                s,
                s,
                20,
                20,
                20,
                20,
                argb);
    }

    private static boolean on(String bits, int w, int h, int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h && bits.charAt(y * w + x) == '1';
    }

    private static void ensure() {
        if (UpgradeVisuals.assets == null) UpgradeVisuals.load();
    }
}
