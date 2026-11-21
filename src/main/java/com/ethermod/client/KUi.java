package com.ethermod.client;

import static com.ethermod.client.KDraw.border;
import static com.ethermod.client.UpgradeVisuals.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import com.ethermod.network.ModNetwork;
import com.ethermod.trait.TraitBuild;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-screen frame context shared by the K-screen parts: the Manipulator being shown, the frame
 * clock, layout, the registered hit areas with their hover animation, and the interactive control
 * primitives that register against them.
 */
final class KUi {
    /** The screen's own hit list, kept there so tours can read it reflectively. */
    final List<KHit> hits;

    final Map<String, Double> hoverLevels = new HashMap<>();
    Minecraft mc;
    Font font;
    ItemStack stack = ItemStack.EMPTY;
    double now;
    /** Mouse position in K-window units, as of the last rendered frame. */
    int mx, my;
    /** Where the K-window sits on the GUI, and its scale. */
    int left, top;
    float scale = 1;

    KUi(List<KHit> hits) {
        this.hits = hits;
    }

    void bind(Minecraft mc, Font font) {
        this.mc = mc;
        this.font = font;
    }

    void layout(int left, int top, float scale) {
        this.left = left;
        this.top = top;
        this.scale = scale;
    }

    int stage() {
        return TraitBuild.openSlots(stack);
    }

    static void send(CustomPacketPayload packet) {
        ModNetwork.send(packet);
    }

    void hit(
            String key,
            int x,
            int y,
            int w,
            int h,
            Runnable action,
            List<Component> tip,
            int c,
            boolean pulse) {
        hits.add(new KHit(key, x, y, w, h, action, tip, c, pulse));
    }

    void control(
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

    /** The control the mouse went down on, until it is released (for the pressed look). */
    String pressedKey;

    /** How a ramp button's icon should be drawn: its colour and a 1px drop while pressed. */
    record Look(int icon, int dy) {}

    /**
     * A button drawn from one hue ramp: dark fill (step 0), lit bevel top-left (4), corners (3) and
     * dark bevel bottom-right (2), icon on step 5. Hovered: fill 1, icon 6. Pressed: the bevel
     * sinks (light and dark edges swap), fill 2, icon 6 one pixel lower.
     */
    Look rampControl(
            GuiGraphics g, String key, int x, int y, int w, int h, Palette r, Runnable action, List<Component> tip) {
        int[] c = r.ramp();
        boolean over = action != null && mx >= x && mx < x + w && my >= y && my < y + h;
        boolean down = over && key.equals(pressedKey);
        int hi = down ? c[2] : c[4], sh = down ? c[4] : c[2];
        g.fill(x, y, x + w, y + h, c[down ? 2 : over ? 1 : 0]);
        g.fill(x, y, x + w - 1, y + 1, hi);
        g.fill(x, y + 1, x + 1, y + h - 1, hi);
        g.fill(x + 1, y + h - 1, x + w, y + h, sh);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, sh);
        g.fill(x + w - 1, y, x + w, y + 1, c[3]);
        g.fill(x, y + h - 1, x + 1, y + h, c[3]);
        if (action != null || !tip.isEmpty()) hit(key, x, y, w, h, action, tip, c[4], false);
        return new Look(over ? c[6] : c[5], down ? 1 : 0);
    }

    void hover(
            GuiGraphics g, String key, int x, int y, int w, int h, int c, boolean pulse) {
        double level = hoverLevels.getOrDefault(key, 0d);
        if (level < .005) return;
        int alpha = (int) (level * 22);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, (alpha << 24) | (c & 0xffffff));
        border(g, x, y, w, h, ((int) (level * 100) << 24) | (c & 0xffffff));
    }

    void text(GuiGraphics g, String s, int x, int y, int c) {
        g.drawString(font, s, x, y, c, true);
    }

    void center(GuiGraphics g, String s, int x, int y, int w, int h, int c) {
        text(g, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2, c);
    }
}
