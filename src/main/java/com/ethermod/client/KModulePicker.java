package com.ethermod.client;

import static com.ethermod.client.KDraw.*;
import static com.ethermod.client.KUi.send;
import static com.ethermod.client.UiSettings.a;
import static com.ethermod.client.UiSettings.p;
import static com.ethermod.client.UpgradeVisuals.*;

import com.ethermod.network.payload.TraitPayloads;
import com.ethermod.trait.*;

import net.minecraft.client.gui.GuiGraphics;

import java.util.Optional;

/** The modal grid of modules offered for an empty or occupied slot. */
final class KModulePicker {
    void draw(GuiGraphics g, KUi ui, int picker, Runnable onPicked) {
        frame(g, 84, 17, 215, 145, a());
        ui.text(g, Lang.tr("gui.ethermod.choose_module"), 91, 23, a().bright());
        var lines = TraitLines.all(ui.mc.level.registryAccess());
        for (int i = 0; i < lines.size(); i++) {
            // 5-pixel margins on the sides and bottom, 3-pixel gaps between buttons
            int x = 89 + (i % 2) * 104, y = 35 + (i / 2) * 25;
            final int slot = picker;
            var e = lines.get(i);
            int used = TraitBuild.slotOf(ui.stack, e.getKey());
            boolean available =
                    used < 0 && com.ethermod.trait.ModuleUnlocks.known(ui.stack, e.getKey());
            var pal = module(e.getKey(), p());
            ui.control(
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
                                onPicked.run();
                            }
                            : null,
                    KTooltips.moduleTip(e.getKey(), e.getValue()),
                    false);
            sprite(g, e.getKey(), x, y, 22, 22, available ? pal.accent() : pal.border());
            ui.text(g, Lang.moduleName(e.getKey(), e.getValue()), x + 25, y + 7, available ? pal.accent() : pal.dim());
        }
    }
}
