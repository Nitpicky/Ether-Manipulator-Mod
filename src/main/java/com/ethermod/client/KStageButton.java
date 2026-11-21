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

import java.util.List;

/** The tier readout and "upgrade" button under the model. */
final class KStageButton {
    void draw(GuiGraphicsExtractor g, KUi ui) {
        var st = ManipulatorStage.get(ui.stack);
        String title = st.title().getString();
        ui.text(g, title, 8 + (71 - ui.font.width(title)) / 2, 70, a().text());
        for (int i = 0; i < 3; i++) {
            g.fill(38 + i * 5, 83, 41 + i * 5, 88, a().shadow());
            g.fill(37 + i * 5, 82, 40 + i * 5, 87, i < ui.stage() ? a().dim() : a().border());
        }
        boolean has = upgradeInventory(ui) >= 0, active = st.next() != null;
        List<Component> tip =
                active
                        ? List.of(
                                label(Lang.tr("gui.ethermod.tier", st.next().title().getString()), a().bright()),
                                label(Lang.tr("gui.ethermod.requires_item", upgradeName(ui)), 0xffedc16e))
                        : List.of();
        ui.control(
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
        ui.center(
                g,
                active ? Lang.tr("gui.ethermod.upgrade") : Lang.tr("gui.ethermod.max_stage"),
                8,
                92,
                71,
                16,
                active ? a().bright() : a().border());
        g.fill(8, 111, 79, 112, mix(p().bg(), p().accent(), .55));
    }

    private String upgradeName(KUi ui) {
        return switch (ManipulatorStage.get(ui.stack)) {
            case STONE -> Lang.tr("item.ethermod.upgrade_iron");
            case IRON -> Lang.tr("item.ethermod.upgrade_diamond");
            default -> Lang.tr("item.ethermod.upgrade_netherite");
        };
    }

    private int upgradeInventory(KUi ui) {
        for (int i = 0; i < ui.mc.player.getInventory().getNonEquipmentItems().size(); i++) {
            var it = ui.mc.player.getInventory().getNonEquipmentItems().get(i);
            if (it.getItem() instanceof TierUpgradeModule m
                    && m.getRequiredStage() == ManipulatorStage.get(ui.stack)) return i;
        }
        return -1;
    }
}
