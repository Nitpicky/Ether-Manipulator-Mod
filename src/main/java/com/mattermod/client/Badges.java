package com.mattermod.client;

import com.mattermod.item.OrbMode;

import net.minecraft.client.gui.GuiGraphics;

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
            case GOLEM -> "mechatronic";
        };
    }

    /** The Module's own colour (opaque ARGB). */
    public static int color(String module) {
        ensure();
        for (var m : UpgradeVisuals.modules.values())
            if (m.has("sprite") && m.get("sprite").getAsString().equals(module))
                return UpgradeVisuals.hex(m.get("color").getAsString());
        return 0xFFFFFFFF;
    }

    /**
     * Draws a Badge (the 16x16 icon with its baked 2 px black outline, so 20x20) in the given
     * colour, the icon's own top-left at x, y. The sprites live in textures/gui/badge/ and are
     * baked by bake_badges.py; they can be touched up by hand.
     */
    public static void draw(GuiGraphics g, String module, int x, int y, int scale, int argb) {
        var tex =
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        "mattermod", "textures/gui/badge/" + module + ".png");
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.setColor(
                (argb >> 16 & 255) / 255f,
                (argb >> 8 & 255) / 255f,
                (argb & 255) / 255f,
                (argb >>> 24) / 255f);
        int s = 20 * scale;
        g.blit(tex, x - 2 * scale, y - 2 * scale, s, s, 0, 0, 20, 20, 20, 20);
        g.setColor(1, 1, 1, 1);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    private static boolean on(String bits, int w, int h, int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h && bits.charAt(y * w + x) == '1';
    }

    private static void ensure() {
        if (UpgradeVisuals.assets == null) UpgradeVisuals.load();
    }
}
