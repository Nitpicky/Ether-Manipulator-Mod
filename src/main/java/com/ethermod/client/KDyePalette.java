package com.ethermod.client;

import static com.ethermod.client.KDraw.*;
import static com.ethermod.client.KTooltips.label;
import static com.ethermod.client.KUi.send;
import static com.ethermod.client.UiSettings.a;
import static com.ethermod.client.UiSettings.p;
import static com.ethermod.client.UpgradeVisuals.*;

import com.ethermod.item.*;
import com.ethermod.network.payload.TraitPayloads;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;

import java.util.List;

/** The dye swatch grid: pick an unlocked trim colour, paint from a carried dye, or open the custom dialog. */
final class KDyePalette {
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

    private final Runnable openColour;

    KDyePalette(Runnable openColour) {
        this.openColour = openColour;
    }

    void draw(GuiGraphicsExtractor g, KUi ui) {
        int current = ManipulatorColors.body(ui.stack), unlocked = 0;
        for (var dye : DYES)
            if (ManipulatorColors.isUnlocked(
                    ui.stack, true, (dye.getTextureDiffuseColor() & 0xffffff))) unlocked++;
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
                                    && ManipulatorColors.isUnlocked(ui.stack, true, colour & 0xffffff);
            int inv = k > 0 && k < 17 ? dyeInventory(ui, DYES[k - 1]) : -1;
            double strength = open ? 1 : inv >= 0 ? .4 + .6 * (.5 + .5 * Math.sin(ui.now * 3.5)) : .3;
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
            ui.hit(
                    "dye" + k,
                    x,
                    y,
                    11,
                    11,
                    (index == 17 ? count == 16 : open || source >= 0)
                            ? () -> {
                                if (index == 17) {
                                    if (count == 16) openColour.run();
                                } else if (open)
                                    send(new TraitPayloads.PickColour(true, index == 0 ? -1 : c));
                                else if (source >= 0) send(new TraitPayloads.Paint(true, source));
                            }
                            : null,
                    tip,
                    colour,
                    false);
            ui.hover(g, "dye" + k, x, y, 11, 11, colour, false);
        }
    }

    private boolean isDye(int c) {
        for (var d : DYES)
            if ((d.getTextureDiffuseColor() & 0xffffff) == (c & 0xffffff)) return true;
        return false;
    }

    private int dyeInventory(KUi ui, DyeColor dye) {
        var items = ui.mc.player.getInventory().items;
        for (int i = 0; i < items.size(); i++)
            if (items.get(i).getItem() instanceof DyeItem && items.get(i).get(net.minecraft.core.component.DataComponents.DYE) == dye) return i;
        return -1;
    }
}
